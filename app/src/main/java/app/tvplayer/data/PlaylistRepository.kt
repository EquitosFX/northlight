package app.tvplayer.data

import app.tvplayer.core.backup.PlaylistBackup
import app.tvplayer.player.CatchupPlan
import java.time.ZoneOffset
import java.time.ZoneId
import app.tvplayer.player.VodItem
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.catchup.CatchupWindow
import app.tvplayer.core.catchup.CatchupException
import app.tvplayer.core.catchup.Catchup
import app.tvplayer.core.vod.EpisodeNames
import app.tvplayer.core.channels.ChannelVariants
import app.tvplayer.core.guide.ChannelRef
import app.tvplayer.core.guide.EpgMatcher
import app.tvplayer.core.guide.GuideChannel
import app.tvplayer.core.guide.XmltvParser
import app.tvplayer.core.playlist.M3uParser
import app.tvplayer.core.xtream.XtreamAccount
import app.tvplayer.core.xtream.XtreamException
import app.tvplayer.core.xtream.XtreamParser
import app.tvplayer.core.xtream.XtreamUrls
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.MatchRow
import app.tvplayer.data.db.PlaylistEntity
import app.tvplayer.data.db.ProgrammeEntity
import app.tvplayer.data.db.VodRow
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Callable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class Episode(
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String?,
    val durationSecs: Int?,
)

data class SeriesDetails(val plot: String?, val episodes: List<Episode>)

/** Where an import has got to, for the Settings screen to show. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val playlistName: String, val step: String, val count: Int) : ImportState
    data class Done(
        val playlistName: String,
        val live: Int,
        val movies: Int,
        val series: Int,
        val programmes: Int,
        /** Set when the channels came in but the TV guide did not. */
        val guideNote: String? = null,
    ) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * Adds, refreshes and removes playlists, and brings in their TV guides.
 *
 * Imports run in the app-wide [scope], so they carry on if the user leaves the Settings screen.
 * Rows go into the database in batches as they are parsed; the full list is never held in memory.
 * A refresh is all-or-nothing: if the download fails part-way, the previous channels stay.
 * A guide that fails to download never undoes a successful channel import.
 */
class PlaylistRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** When films and series were added, for "Recently added". */
    private val added: AddedDatesStore? = null,
    /** Where downloaded guides wait to be read, and what each guide was last time (to skip unchanged ones). */
    private val guideDir: java.io.File? = null,
) {
    /** Each playlist's guide fingerprint last stored, and when its guide was last checked. */
    private val guideMarks = java.util.Properties()
    private val guideMarksFile get() = guideDir?.let { java.io.File(it, "guides.properties") }

    init {
        runCatching { guideMarksFile?.takeIf { it.exists() }?.inputStream()?.use(guideMarks::load) }
    }

    private fun saveGuideMarks() = synchronized(guideMarks) {
        runCatching {
            guideDir?.mkdirs()
            guideMarksFile?.outputStream()?.use { guideMarks.store(it, null) }
        }
    }

    /** When [playlist]'s guide was last downloaded or found unchanged. */
    private fun guideCheckedAt(playlist: PlaylistEntity): Long =
        maxOf(playlist.guideRefreshedAt ?: 0L, guideMarks.getProperty("checked.${playlist.id}")?.toLongOrNull() ?: 0L)

    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()

    /** Every playlist, read now (call off the main thread). */
    fun allBlocking(): List<PlaylistEntity> = db.playlists().getAllBlocking()

    /** A handful of live channels for the Home screen. */
    val firstLiveChannels: Flow<List<ChannelEntity>> = db.channels().observeFirstLive(60)

    private val state = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = state.asStateFlow()

    fun addXtream(name: String, server: String, username: String, password: String) = runJob {
        val account = XtreamAccount(server, username.trim(), password.trim())
        val label = name.trim().ifEmpty { hostOf(account.baseUrl) }
        state.value = ImportState.Working(label, "Signing in", 0)

        val auth = XtreamParser.parseAuth(fetchText(XtreamUrls.api(account)))
        if (!auth.authorised) throw XtreamException("The server rejected that username or password.")

        val id = db.playlists().insertBlocking(
            PlaylistEntity(
                name = label,
                type = PlaylistEntity.TYPE_XTREAM,
                address = account.baseUrl,
                username = account.username,
                password = account.password,
                guideUrls = XtreamUrls.guide(account),
                addedAt = System.currentTimeMillis(),
                accountStatus = auth.status,
                expiresAt = auth.expiresAtSeconds?.times(1000),
            ),
        )
        importInto(id, isNew = true)
    }

    fun addM3u(name: String, link: String) = runJob {
        val address = link.trim()
        val label = name.trim().ifEmpty { hostOf(address) }
        state.value = ImportState.Working(label, "Downloading", 0)

        val id = db.playlists().insertBlocking(
            PlaylistEntity(
                name = label,
                type = PlaylistEntity.TYPE_M3U,
                address = address,
                addedAt = System.currentTimeMillis(),
            ),
        )
        importInto(id, isNew = true)
    }

    fun refresh(playlistId: Long) = runJob { importInto(playlistId, isNew = false) }

    /**
     * Adds playlists from a backup (any already here are kept as they are) and loads them one after
     * another. [onDone] gets each backup playlist's id here, in the same order.
     */
    fun restore(backups: List<PlaylistBackup>, onDone: (List<Long>) -> Unit) = runJob {
        val existing = db.playlists().getAllBlocking()
        val ids = backups.map { backup ->
            existing.firstOrNull { it.address == backup.address && it.username == backup.username }?.id
                ?: db.playlists().insertBlocking(
                    PlaylistEntity(
                        name = backup.name,
                        type = backup.type,
                        address = backup.address,
                        username = backup.username,
                        password = backup.password,
                        guideUrls = backup.guideUrls,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
        }
        onDone(ids)
        var failed: Exception? = null
        for ((index, id) in ids.withIndex()) {
            if (existing.any { it.id == id && it.refreshedAt != null }) continue
            try {
                importInto(id, isNew = true)
            } catch (e: Exception) {
                failed = failed ?: IOException("${backups[index].name}: ${explain(e)}")
            }
        }
        failed?.let { throw it }
    }

    /**
     * Refreshes playlists not refreshed in the last [maxAgeHours], quietly, one after another.
     * Called when the app starts and every hour while it runs.
     */
    fun refreshStale(maxAgeHours: Int) {
        if (maxAgeHours <= 0 || state.value is ImportState.Working) return
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - maxAgeHours * 3_600_000L
            val stale = db.playlists().getAllBlocking().filter { (it.refreshedAt ?: 0L) < cutoff && it.refreshedAt != null }
            for (playlist in stale) {
                if (state.value is ImportState.Working) return@launch
                state.value = ImportState.Working(playlist.name, "Refreshing", 0)
                runCatching { quietly { importInto(playlist.id, isNew = false) } }
                state.value = ImportState.Idle
            }
        }
    }

    fun remove(playlistId: Long) {
        scope.launch(Dispatchers.IO) {
            db.playlists().deleteBlocking(playlistId)
            added?.remove(playlistId)
        }
    }

    /** Re-downloads any TV guide not updated in the last 12 hours. Called when the app starts. */
    fun refreshStaleGuides() {
        if (state.value is ImportState.Working) return
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - GUIDE_MAX_AGE
            val stale = db.playlists().getAllBlocking().filter { playlist ->
                playlist.guideUrls.isNotBlank() &&
                    playlist.refreshedAt != null &&
                    guideCheckedAt(playlist) < cutoff
            }
            for (playlist in stale) {
                if (state.value is ImportState.Working) return@launch
                runCatching { quietly { importGuide(playlist) } }
                // A quiet background refresh: no message unless something is already showing.
                if (state.value is ImportState.Working) state.value = ImportState.Idle
            }
        }
    }

    /** Fetches a series' seasons and episodes from its Xtream Codes server. */
    // A series' episode list, kept for half an hour so going back to a show doesn't ask the server again.
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SeriesDetails>>()

    /** The episode list fetched last time this session (however long ago), to show while it's checked. */
    fun cachedSeriesDetails(series: VodRow): SeriesDetails? =
        seriesCache["${series.playlistId}|${series.remoteId}|${series.name}"]?.second

    private fun freshSeriesDetails(series: VodRow): SeriesDetails? =
        seriesCache["${series.playlistId}|${series.remoteId}|${series.name}"]
            ?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.second

    suspend fun seriesDetails(series: VodRow, fresh: Boolean = false): SeriesDetails {
        if (!fresh) freshSeriesDetails(series)?.let { return it }
        return loadSeriesDetails(series).also {
            seriesCache["${series.playlistId}|${series.remoteId}|${series.name}"] = System.currentTimeMillis() to it
        }
    }

    private suspend fun loadSeriesDetails(series: VodRow): SeriesDetails = withContext(Dispatchers.IO) {
        val playlist = db.playlists().getBlocking(series.playlistId) ?: throw IOException("That playlist no longer exists.")
        val seriesId = series.remoteId
        if (playlist.type != PlaylistEntity.TYPE_XTREAM || seriesId == null) {
            // M3U playlists list each episode as its own entry: gather the ones belonging to this show.
            val key = EpisodeNames.showKey(series.name)
            val episodes = db.channels().seriesEntries(series.playlistId).mapNotNull { row ->
                val name = EpisodeNames.parse(row.name) ?: return@mapNotNull null
                if (EpisodeNames.showKey(name.show) != key) return@mapNotNull null
                Episode(name.season, name.episode, name.title ?: "Episode ${name.episode}", row.url, null, null)
            }.distinctBy { it.season to it.number }.sortedWith(compareBy({ it.season }, { it.number }))
            // A series entry that isn't an episode is played as it is.
            return@withContext SeriesDetails(null, episodes.ifEmpty { listOf(Episode(1, 1, series.name, series.url, null, null)) })
        }
        val account = XtreamAccount(playlist.address, playlist.username.orEmpty(), playlist.password.orEmpty())
        val info = XtreamParser.parseSeriesInfo(fetchText(XtreamUrls.api(account, "get_series_info") + "&series_id=$seriesId"))
        SeriesDetails(
            plot = info.plot,
            episodes = info.episodes.map {
                Episode(it.season, it.number, it.title, XtreamUrls.episode(account, it.id, it.extension), it.plot, it.durationSecs)
            },
        )
    }

    private val serverZones = HashMap<Long, ZoneId>()

    /**
     * The film-like item for watching [programme] again on [channel] (catch-up). Xtream Codes servers
     * want the start in their own local time, so the server's time zone is asked for once per playlist.
     */
    suspend fun catchupItem(channel: LineupChannel, programme: ProgrammeEntity, shiftMinutes: Int = 0): VodItem = withContext(Dispatchers.IO) {
        val row = channel.variantIds.firstNotNullOfOrNull { id -> db.channels().getBlocking(id)?.takeIf { (it.catchupDays ?: 0) > 0 } }
            ?: throw IOException("This channel doesn't offer catch-up.")
        val playlist = db.playlists().getBlocking(row.playlistId) ?: throw IOException("That playlist no longer exists.")
        val type = row.catchupType ?: if (playlist.type == PlaylistEntity.TYPE_XTREAM) "xc" else null
        val zone = if (type == "xc" && playlist.type == PlaylistEntity.TYPE_XTREAM) {
            serverZones.getOrPut(playlist.id) {
                val account = XtreamAccount(playlist.address, playlist.username.orEmpty(), playlist.password.orEmpty())
                runCatching { XtreamParser.parseAuth(fetchText(XtreamUrls.api(account))).serverTimezone }
                    .getOrNull()?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
            }
        } else {
            ZoneOffset.UTC
        }
        val shift = shiftMinutes * 60_000L
        val plan = CatchupPlan(row.url, type, row.catchupSource, zone, programme.start, programme.stop, shift)
        val url = try {
            plan.urlFrom(0)
        } catch (e: CatchupException) {
            throw IOException(e.message)
        }
        VodItem(
            url = url,
            title = "${channel.name} · ${programme.title}",
            poster = channel.logo,
            userAgent = row.userAgent,
            referrer = row.referrer,
            fromStart = true,
            catchupPlan = plan,
        )
    }

    /** A plain-English reason for a failed download, for showing on screen. */
    fun explainError(e: Exception): String = explain(e)

    /** An Xtream Codes account as the provider reports it: when it ends, and how many streams it allows. */
    data class Subscription(
        val endsAt: Long?,
        val status: String?,
        val maxConnections: Int?,
        val activeConnections: Int?,
        val trial: Boolean,
    )

    private val subscriptions = HashMap<Long, Pair<Long, Subscription>>()

    /** What the provider says about [playlist]'s account (Xtream Codes only), asked at most every few minutes. */
    suspend fun subscription(playlist: PlaylistEntity, maxAgeMillis: Long = 5 * 60_000L): Subscription? = withContext(Dispatchers.IO) {
        if (playlist.type != PlaylistEntity.TYPE_XTREAM) return@withContext null
        val cached = synchronized(subscriptions) { subscriptions[playlist.id] }
        if (cached != null && System.currentTimeMillis() - cached.first < maxAgeMillis) return@withContext cached.second
        val account = XtreamAccount(playlist.address, playlist.username.orEmpty(), playlist.password.orEmpty())
        val auth = runCatching { XtreamParser.parseAuth(fetchText(XtreamUrls.api(account))) }.getOrNull()
            ?: return@withContext cached?.second
        val found = Subscription(
            endsAt = auth.expiresAtSeconds?.takeIf { it > 0 }?.times(1000),
            status = auth.status,
            maxConnections = auth.maxConnections,
            activeConnections = auth.activeConnections,
            trial = auth.isTrial,
        )
        synchronized(subscriptions) { subscriptions[playlist.id] = System.currentTimeMillis() to found }
        found
    }

    /** Clears a finished or failed message once the user has moved on. */
    fun acknowledge() {
        if (state.value !is ImportState.Working) state.value = ImportState.Idle
    }

    private fun runJob(block: () -> Unit) {
        if (state.value is ImportState.Working) return // one import at a time
        state.value = ImportState.Working("", "Starting", 0)
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                state.value = ImportState.Failed(explain(e))
            }
        }
    }

    private fun importInto(playlistId: Long, isNew: Boolean) {
        val playlist = db.playlists().getBlocking(playlistId) ?: throw IOException("That playlist no longer exists.")
        val imported = try {
            db.runInTransaction(
                Callable {
                    db.channels().deleteForPlaylistBlocking(playlistId)
                    val batch = Batch(playlist)
                    val guideUrls = if (playlist.type == PlaylistEntity.TYPE_XTREAM) {
                        importXtream(playlist, batch)
                    } else {
                        importM3u(playlist, batch)
                    }
                    batch.flush()
                    if (batch.total == 0) throw IOException("Nothing was found at that address.")
                    // When each film and series was added (kept beside the database, saved with it).
                    runCatching { added?.save(playlistId, batch.dates) }
                    val updated = playlist.copy(
                        guideUrls = guideUrls,
                        refreshedAt = System.currentTimeMillis(),
                        liveCount = batch.live,
                        movieCount = batch.movies,
                        seriesCount = batch.series,
                    )
                    db.playlists().updateBlocking(updated)
                    updated to batch
                },
            )
        } catch (e: Exception) {
            // A playlist that never finished its first import is not kept.
            if (isNew) db.playlists().deleteBlocking(playlistId)
            throw e
        }

        val (updated, batch) = imported
        var programmes = 0
        var guideNote: String? = null
        if (updated.guideUrls.isBlank()) {
            guideNote = "This playlist doesn't name a TV guide."
        } else {
            try {
                programmes = importGuide(updated)
            } catch (e: Exception) {
                guideNote = "The TV guide didn't load: ${explain(e)}"
            }
        }
        state.value = ImportState.Done(updated.name, batch.live, batch.movies, batch.series, programmes, guideNote)
    }

    /**
     * Downloads every guide the playlist names, links guide channels to the playlist's channels,
     * and stores programmes from a day ago to a week ahead for the channels that matched.
     * Returns the number of programmes stored.
     */
    /**
     * Runs a background refresh at low priority, so the guide, menus and the channel playing get the
     * TV box first and the refresh takes what's left. (Blocking work only: it stays on this thread.)
     */
    private inline fun <T> quietly(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = runCatching { android.os.Process.getThreadPriority(tid) }.getOrDefault(android.os.Process.THREAD_PRIORITY_DEFAULT)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        try {
            return block()
        } finally {
            runCatching { android.os.Process.setThreadPriority(before) }
        }
    }

    private fun importGuide(playlist: PlaylistEntity): Int {
        val urls = playlist.guideUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return 0
        val began = android.os.SystemClock.uptimeMillis()
        var unchanged = false
        // Downloaded to files first (when there's room), so an unchanged guide can be recognised and
        // left as it is: no reading it again and rewriting every programme, which is the slow part.
        val files = downloadGuides(playlist, urls)
        try {
            val fingerprint = files?.let { guideFingerprint(playlist, it) }
            if (fingerprint != null && playlist.programmeCount > 0 && guideMarks.getProperty("hash.${playlist.id}") == fingerprint) {
                unchanged = true
                guideMarks.setProperty("checked.${playlist.id}", System.currentTimeMillis().toString())
                saveGuideMarks()
                return playlist.programmeCount
            }
            val stored = readGuide(playlist, urls, files?.map { it.first })
            if (fingerprint != null) {
                guideMarks.setProperty("hash.${playlist.id}", fingerprint)
                guideMarks.remove("checked.${playlist.id}")
                saveGuideMarks()
            }
            return stored
        } finally {
            files?.forEach { it.first?.delete() }
            // For Settings › Refresh and check › Speed: everything else is slower while this runs.
            val took = (android.os.SystemClock.uptimeMillis() - began) / 1000
            val at = java.time.LocalTime.now().withNano(0).withSecond(0)
            app.tvplayer.ui.components.Timing.note(
                "TV guide download",
                if (unchanged) "unchanged since last time, checked in ${took} s at $at" else "took ${took} s, finished at $at",
            )
        }
    }

    /**
     * Downloads each of [playlist]'s guides to a file, with its SHA-256; a guide that failed has no file.
     * Null when there isn't room (then the guides are read straight from the internet, as before).
     */
    private fun downloadGuides(playlist: PlaylistEntity, urls: List<String>): List<Pair<java.io.File?, String?>>? {
        val dir = guideDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        if (dir.usableSpace < 500L * 1024 * 1024) return null
        state.value = ImportState.Working(playlist.name, "TV guide", 0)
        val result = urls.mapIndexed { index, url ->
            val file = java.io.File(dir, "guide-${playlist.id}-$index.tmp")
            runCatching {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                fetch(url) { input ->
                    java.security.DigestInputStream(input, digest).use { hashed -> file.outputStream().use { hashed.copyTo(it, 64 * 1024) } }
                }
                file to digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrElse {
                file.delete()
                // One broken guide out of several should not lose the others; a single one failing is an error.
                if (urls.size == 1) throw it
                null to null
            }
        }
        return result
    }

    /** One fingerprint for all of a playlist's guides, and its channels as they were matched to them. */
    private fun guideFingerprint(playlist: PlaylistEntity, files: List<Pair<java.io.File?, String?>>): String? {
        if (files.any { it.second == null }) return null
        return files.joinToString("|") { it.second.orEmpty() } + "|channels:" + (playlist.refreshedAt ?: 0L)
    }

    /** Reads [playlist]'s guides into the database: from [files] where downloaded already, else from the internet. */
    private fun readGuide(playlist: PlaylistEntity, urls: List<String>, files: List<java.io.File?>? = null): Int {
        val channels = db.channels().matchRowsBlocking(playlist.id)
        val now = System.currentTimeMillis()
        // A week of past programmes is kept, for catch-up.
        val keepFrom = now - 7 * DAY
        val keepTo = now + 7 * DAY
        state.value = ImportState.Working(playlist.name, "TV guide", 0)

        val total = db.runInTransaction(
            Callable {
                db.programmes().deleteForPlaylistBlocking(playlist.id)
                val matched = HashMap<String, String>() // variantKey -> guide channel id
                val rows = ArrayList<ProgrammeEntity>(GUIDE_BATCH)
                var stored = 0

                fun flush() {
                    if (rows.isEmpty()) return
                    db.programmes().insertAllBlocking(rows.toList())
                    stored += rows.size
                    rows.clear()
                    state.value = ImportState.Working(playlist.name, "TV guide", stored)
                }

                for ((index, url) in urls.withIndex()) {
                    val guideChannels = ArrayList<GuideChannel>()
                    var wanted: Set<String>? = null
                    val file = files?.getOrNull(index)
                    // Downloaded already but that guide failed: skip it, as reading it would fail again.
                    if (files != null && file == null) continue
                    try {
                        val open: ((InputStream) -> Unit) -> Unit = { read ->
                            if (file != null) file.inputStream().buffered(64 * 1024).use { read(it) } else fetch(url, read)
                        }
                        open { input ->
                            XmltvParser.parse(
                                input,
                                onChannel = { guideChannels.add(it) },
                                onProgramme = { programme ->
                                    // Guides list their channels first, so matching happens once, at the first programme.
                                    val ids = wanted ?: matchNew(channels, guideChannels, matched).also { wanted = it }
                                    if (programme.channelId in ids &&
                                        programme.stopMillis > keepFrom &&
                                        programme.startMillis < keepTo
                                    ) {
                                        rows.add(
                                            ProgrammeEntity(
                                                playlistId = playlist.id,
                                                channelGuideId = programme.channelId,
                                                start = programme.startMillis,
                                                stop = programme.stopMillis,
                                                title = programme.title,
                                                subTitle = programme.subTitle,
                                                description = programme.description,
                                                category = programme.categories.firstOrNull(),
                                                season = programme.season,
                                                episode = programme.episode,
                                            ),
                                        )
                                        if (rows.size >= GUIDE_BATCH) flush()
                                    }
                                },
                            )
                        }
                    } catch (e: Exception) {
                        // One broken guide out of several should not lose the others.
                        if (urls.size == 1) throw e
                    }
                    if (wanted == null) matchNew(channels, guideChannels, matched)
                }
                flush()

                for (channel in channels) {
                    db.channels().setEpgIdBlocking(playlist.id, channel.variantKey, matched[channel.variantKey])
                }
                db.playlists().getBlocking(playlist.id)?.let { current ->
                    db.playlists().updateBlocking(
                        current.copy(guideRefreshedAt = System.currentTimeMillis(), programmeCount = stored),
                    )
                }
                stored
            },
        )
        return total
    }

    /**
     * Matches the channels not matched yet against one guide's channel list, records the matches in
     * [matched], and returns the guide channel ids newly matched (the ones to keep programmes for).
     * A guide with no channel list is matched on the playlist's own guide ids alone.
     */
    private fun matchNew(
        channels: List<MatchRow>,
        guideChannels: List<GuideChannel>,
        matched: MutableMap<String, String>,
    ): Set<String> {
        val pending = channels.filter { it.variantKey !in matched }
        val found = HashSet<String>()
        if (guideChannels.isEmpty()) {
            for (channel in pending) {
                val id = channel.guideId?.takeIf { it.isNotBlank() } ?: continue
                matched[channel.variantKey] = id
                found.add(id)
            }
            return found
        }
        val results = EpgMatcher.match(pending.map { ChannelRef(name = it.name, tvgId = it.guideId) }, guideChannels)
        pending.zip(results).forEach { (channel, match) ->
            if (match != null) {
                matched[channel.variantKey] = match.guideId
                found.add(match.guideId)
            }
        }
        return found
    }

    /** Returns the guide address for the account. */
    private fun importXtream(playlist: PlaylistEntity, batch: Batch): String {
        val account = XtreamAccount(playlist.address, playlist.username.orEmpty(), playlist.password.orEmpty())

        batch.step = "Live channels"
        val liveGroups = categories(account, "get_live_categories")
        fetch(XtreamUrls.api(account, "get_live_streams")) { input ->
            XtreamParser.parseStreams(input) { stream ->
                batch.add(
                    kind = ChannelEntity.KIND_LIVE,
                    name = stream.name,
                    url = XtreamUrls.live(account, stream.streamId),
                    remoteId = stream.streamId,
                    logo = stream.icon,
                    group = liveGroups[stream.categoryId],
                    number = stream.number,
                    guideId = stream.guideChannelId,
                    catchupDays = stream.catchupDays,
                    catchupType = if (stream.catchupDays != null) "xc" else null,
                )
            }
        }

        // Not every provider has films or series; their absence should not fail the whole import.
        batch.step = "Films"
        optional {
            val filmGroups = categories(account, "get_vod_categories")
            fetch(XtreamUrls.api(account, "get_vod_streams")) { input ->
                XtreamParser.parseStreams(input) { film ->
                    batch.add(
                        kind = ChannelEntity.KIND_MOVIE,
                        name = film.name,
                        url = XtreamUrls.movie(account, film.streamId, film.containerExtension),
                        remoteId = film.streamId,
                        logo = film.icon,
                        group = filmGroups[film.categoryId],
                        added = film.added,
                    )
                }
            }
        }

        batch.step = "Series"
        optional {
            val seriesGroups = categories(account, "get_series_categories")
            fetch(XtreamUrls.api(account, "get_series")) { input ->
                XtreamParser.parseSeries(input) { series ->
                    batch.add(
                        kind = ChannelEntity.KIND_SERIES,
                        name = series.name,
                        url = XtreamUrls.series(account, series.seriesId),
                        remoteId = series.seriesId,
                        logo = series.cover,
                        group = seriesGroups[series.categoryId],
                        added = series.added,
                    )
                }
            }
        }
        return XtreamUrls.guide(account)
    }

    /** Returns the guide addresses named at the top of the playlist, one per line. */
    private fun importM3u(playlist: PlaylistEntity, batch: Batch): String {
        batch.step = "Reading playlist"
        var guideUrls = emptyList<String>()
        fetch(playlist.address) { input ->
            M3uParser.parse(input.reader(), onHeader = { header -> guideUrls = header.guideUrls }) { entry ->
                batch.add(
                    kind = entry.kind.name.lowercase(),
                    name = entry.name,
                    url = entry.url,
                    logo = entry.logo,
                    group = entry.group,
                    number = entry.channelNumber,
                    guideId = entry.tvgId,
                    catchupDays = entry.catchupDays,
                    catchupType = entry.catchupType,
                    catchupSource = entry.catchupSource,
                    userAgent = entry.userAgent,
                    referrer = entry.referrer,
                )
            }
        }
        return guideUrls.joinToString("\n")
    }

    private fun categories(account: XtreamAccount, action: String): Map<String?, String> {
        val names = HashMap<String?, String>()
        optional {
            fetch(XtreamUrls.api(account, action)) { input ->
                XtreamParser.parseCategories(input) { category -> names[category.id] = category.name }
            }
        }
        return names
    }

    private inline fun optional(block: () -> Unit) {
        try {
            block()
        } catch (e: IOException) {
            // skipped
        } catch (e: XtreamException) {
            // skipped
        }
    }

    /** Collects rows and writes them to the database a few hundred at a time. */
    private inner class Batch(private val playlist: PlaylistEntity) {
        private val rows = ArrayList<ChannelEntity>(BATCH_SIZE)
        var step = ""
        var total = 0
        var live = 0
        var movies = 0
        var series = 0
        /** Each film and series' key and the provider's date for it, if any (see [AddedDatesStore]). */
        val dates = ArrayList<Pair<String, Long?>>()

        fun add(
            kind: String,
            name: String,
            url: String,
            remoteId: Long? = null,
            logo: String? = null,
            group: String? = null,
            number: Int? = null,
            guideId: String? = null,
            catchupDays: Int? = null,
            catchupType: String? = null,
            catchupSource: String? = null,
            userAgent: String? = null,
            referrer: String? = null,
            added: Long? = null,
        ) {
            val isLive = kind == ChannelEntity.KIND_LIVE
            if (!isLive) dates += AddedDatesStore.key(kind, remoteId, url) to added
            val info = if (isLive) ChannelVariants.analyse(name) else null
            rows.add(
                ChannelEntity(
                    playlistId = playlist.id,
                    kind = kind,
                    name = name,
                    baseName = info?.baseName ?: name,
                    variantKey = info?.key ?: ChannelVariants.keyOf(name),
                    quality = info?.quality?.rank ?: 0,
                    isBackup = info?.isBackup ?: false,
                    url = url,
                    remoteId = remoteId,
                    logo = logo,
                    groupName = group,
                    number = number,
                    guideId = guideId,
                    // Until a guide is imported, trust the playlist's own guide id.
                    epgId = guideId?.takeIf { it.isNotBlank() },
                    catchupDays = catchupDays,
                    catchupType = catchupType,
                    catchupSource = catchupSource,
                    userAgent = userAgent,
                    referrer = referrer,
                    position = total,
                ),
            )
            total++
            when (kind) {
                ChannelEntity.KIND_LIVE -> live++
                ChannelEntity.KIND_MOVIE -> movies++
                ChannelEntity.KIND_SERIES -> series++
            }
            if (rows.size >= BATCH_SIZE) flush()
        }

        fun flush() {
            if (rows.isEmpty()) return
            db.channels().insertAllBlocking(rows.toList())
            rows.clear()
            state.value = ImportState.Working(playlist.name, step, total)
        }
    }

    private fun <T> fetch(url: String, block: (InputStream) -> T): T {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The server answered with error ${response.code}.")
            val body = response.body ?: throw IOException("The server sent nothing back.")
            block(body.byteStream())
        }
    }

    private fun fetchText(url: String): String = fetch(url) { input -> input.readBytes().toString(Charsets.UTF_8) }

    private fun explain(e: Exception): String = when (e) {
        is XtreamException -> e.message ?: "The server did not answer as expected."
        is UnknownHostException -> "Couldn't find that server. Check the address and the internet connection."
        is SocketTimeoutException -> "The server took too long to answer."
        is ConnectException -> "Couldn't connect to that server."
        is IllegalArgumentException -> "That address doesn't look right."
        is org.xml.sax.SAXException -> "The guide file couldn't be read."
        is IOException -> e.message ?: "The download failed."
        else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun hostOf(address: String): String =
        try {
            URI(address).host ?: address
        } catch (e: Exception) {
            address
        }

    private companion object {
        const val BATCH_SIZE = 500
        const val GUIDE_BATCH = 1000
        const val DAY = 86_400_000L
        const val GUIDE_MAX_AGE = 12 * 3_600_000L
        const val USER_AGENT = "Northlight/0.1 (Android TV)"
    }
}
