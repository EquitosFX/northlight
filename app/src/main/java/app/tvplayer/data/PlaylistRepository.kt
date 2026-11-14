package app.tvplayer.data

import app.tvplayer.core.channels.ChannelVariants
import app.tvplayer.core.playlist.M3uParser
import app.tvplayer.core.xtream.XtreamAccount
import app.tvplayer.core.xtream.XtreamException
import app.tvplayer.core.xtream.XtreamParser
import app.tvplayer.core.xtream.XtreamUrls
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.PlaylistEntity
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
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where an import has got to, for the Settings screen to show. */
sealed interface ImportState {
    data object Idle : ImportState
    data class Working(val playlistName: String, val step: String, val count: Int) : ImportState
    data class Done(val playlistName: String, val live: Int, val movies: Int, val series: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * Adds, refreshes and removes playlists.
 *
 * Imports run in the app-wide [scope], so they carry on if the user leaves the Settings screen.
 * Rows go into the database in batches as they are parsed; the full list is never held in memory.
 * A refresh is all-or-nothing: if the download fails part-way, the previous channels stay.
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
        try {
            val result = db.runInTransaction(
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
                    db.playlists().updateBlocking(
                        playlist.copy(
                            guideUrls = guideUrls,
                            refreshedAt = System.currentTimeMillis(),
                            liveCount = batch.live,
                            movieCount = batch.movies,
                            seriesCount = batch.series,
                        ),
                    )
                    batch
                },
            )
            state.value = ImportState.Done(playlist.name, result.live, result.movies, result.series)
        } catch (e: Exception) {
            // A playlist that never finished its first import is not kept.
            if (isNew) db.playlists().deleteBlocking(playlistId)
            throw e
        }
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
        const val USER_AGENT = "Northlight/0.1 (Android TV)"
    }
}
