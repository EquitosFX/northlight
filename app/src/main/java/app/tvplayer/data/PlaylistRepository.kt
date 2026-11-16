package app.tvplayer.data

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
) {
    val playlists: Flow<List<PlaylistEntity>> = db.playlists().observeAll()

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

    fun remove(playlistId: Long) {
        scope.launch(Dispatchers.IO) { db.playlists().deleteBlocking(playlistId) }
    }

    /** Re-downloads any TV guide not updated in the last 12 hours. Called when the app starts. */
    fun refreshStaleGuides() {
        if (state.value is ImportState.Working) return
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - GUIDE_MAX_AGE
            val stale = db.playlists().getAllBlocking().filter { playlist ->
                playlist.guideUrls.isNotBlank() &&
                    playlist.refreshedAt != null &&
                    (playlist.guideRefreshedAt ?: 0L) < cutoff
            }
            for (playlist in stale) {
                if (state.value is ImportState.Working) return@launch
                runCatching { importGuide(playlist) }
                // A quiet background refresh: no message unless something is already showing.
                if (state.value is ImportState.Working) state.value = ImportState.Idle
            }
        }
    }

    /** Fetches a series' seasons and episodes from its Xtream Codes server. */
    suspend fun seriesDetails(series: VodRow): SeriesDetails = withContext(Dispatchers.IO) {
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
    private fun importGuide(playlist: PlaylistEntity): Int {
        val urls = playlist.guideUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) return 0
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

                for (url in urls) {
                    val guideChannels = ArrayList<GuideChannel>()
                    var wanted: Set<String>? = null
                    try {
                        fetch(url) { input ->
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
        ) {
            val isLive = kind == ChannelEntity.KIND_LIVE
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
