package app.tvplayer.player

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.util.Locale
import java.io.File
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Format
import androidx.media3.common.C
import android.net.Uri
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.tvplayer.core.live.Lineup
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.live.Reconnect
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.SettingsRepository
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.ProgrammeEntity
import app.tvplayer.data.db.ProgressEntity
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Loading : PlaybackStatus
    data object Playing : PlaybackStatus
    data class Reconnecting(val attempt: Int) : PlaybackStatus
    data object TryingBackup : PlaybackStatus
    data object Failed : PlaybackStatus
    data object NoChannels : PlaybackStatus
    data object Finished : PlaybackStatus
    data class UpNext(val title: String, val seconds: Int) : PlaybackStatus
}

/** The group name that stands for the viewer's favourite channels. */
const val FAVOURITES_GROUP = "\u0000favourites"

/** A film or episode to play. */
data class VodItem(
    val url: String,
    val title: String,
    val poster: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Start at the beginning even if the viewer got part-way before. */
    val fromStart: Boolean = false,
)

/**
 * Plays live channels and keeps them playing.
 *
 * When a stream drops it reconnects up to three times, waiting a little longer each time. If that
 * fails it moves on to the channel's next copy (the provider's other qualities and backups) before
 * giving up. A stream that sits buffering for too long is treated as dropped.
 *
 * Its properties are Compose state, so screens redraw when they change.
 */
@OptIn(UnstableApi::class)
class LivePlayerController(
    private val context: Context,
    private val http: OkHttpClient,
    private val channels: ChannelsRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    var lineup by mutableStateOf(Lineup.EMPTY)
        private set

    /** The group the channel list and channel up/down are limited to; null means every channel. */
    var group by mutableStateOf<String?>(null)
        private set

    var current by mutableStateOf<LineupChannel?>(null)
        private set

    /** The copy of [current] being played. */
    var playingRow by mutableStateOf<ChannelEntity?>(null)
        private set

    var status by mutableStateOf<PlaybackStatus>(PlaybackStatus.Idle)
        private set

    /** For example "1080p · 50fps · 5.1", once the stream has started. */
    var streamInfo by mutableStateOf<String?>(null)
        private set

    /** What's on the current channel now and next, when the TV guide knows. */
    var nowNext by mutableStateOf<List<ProgrammeEntity>>(emptyList())
        private set

    /** The film or episode playing, or null when watching live TV. */
    var vod by mutableStateOf<VodItem?>(null)
        private set

    /** Goes up by one whenever the film controls should appear. */
    var controlsToken by mutableIntStateOf(0)
        private set

    /** Goes up by one whenever the info banner should appear. */
    var bannerToken by mutableIntStateOf(0)
        private set

    /** True while live TV is playing from the pause-and-rewind recording. */
    var timeshiftActive by mutableStateOf(false)
        private set

    /** Keys of the viewer's favourite channels. */
    var favourites by mutableStateOf<Set<String>>(emptySet())
        private set

    val visibleChannels: List<LineupChannel>
        get() = if (group == FAVOURITES_GROUP) {
            lineup.channels.filter { it.key in favourites }
        } else {
            lineup.inGroup(group)
        }

    private var variantIndex = 0
    private var attempt = 0
    private var stopped = false
    private var loadJob: Job? = null
    private var tuneJob: Job? = null
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var guideJob: Job? = null
    private var progressJob: Job? = null
    private var resumeAt = 0L
    private var queue: List<VodItem> = emptyList()
    private var upNextJob: Job? = null
    private var timeshift: TimeshiftSession? = null
    private val timeshiftRoot = File(context.cacheDir, "timeshift").also { TimeshiftSession.clearAll(it) }

    /** Subtitle files added to the film playing (from OpenSubtitles). */
    private var addedSubtitles: List<MediaItem.SubtitleConfiguration> = emptyList()

    // Lets players start on any frame in a TS stream, which makes channel changes noticeably quicker.
    private val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        // Start playing after one second of video rather than the default two and a half.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build())
        .build()
        .also { exo ->
            exo.playWhenReady = true
            exo.addListener(Listener())
        }

    init {
        scope.launch { channels.favouriteKeys.collect { favourites = it } }
    }

    fun toggleFavourite(channel: LineupChannel) {
        scope.launch { channels.toggleFavourite(channel.key) }
    }

    /** Loads the channel list and plays [channelId], or else the last channel watched, or else the first. */
    fun open(channelId: Long?, key: String? = null) {
        leaveVod()
        loadJob?.cancel()
        cancelRecovery()
        status = PlaybackStatus.Loading
        loadJob = scope.launch {
            val built = channels.lineup()
            lineup = built
            group = null
            if (built.isEmpty) {
                player.stop()
                current = null
                playingRow = null
                status = PlaybackStatus.NoChannels
                return@launch
            }
            val lastKey = if (channelId == null && key == null) settings.lastChannel.first() else null
            val start = channelId?.let { built.findById(it) }
                ?: key?.let { built.findByKey(it) }
                ?: lastKey?.let { built.findByKey(it) }
                ?: built.channels.first()
            tune(start)
        }
    }

    fun tune(channel: LineupChannel) {
        leaveVod()
        cancelRecovery()
        current = channel
        variantIndex = 0
        attempt = 0
        streamInfo = null
        bannerToken++
        scope.launch {
            settings.setLastChannel(channel.key)
            channels.recordWatched(channel)
        }
        followGuide(channel)
        playVariant(PlaybackStatus.Loading)
    }

    /** Plays a film or episode, carrying on from where the viewer left it if they got part-way. */
    fun playVod(item: VodItem, episodes: List<VodItem> = listOf(item)) {
        leaveVod()
        stopTimeshift()
        addedSubtitles = emptyList()
        queue = episodes
        loadJob?.cancel()
        tuneJob?.cancel()
        guideJob?.cancel()
        cancelRecovery()
        current = null
        playingRow = null
        nowNext = emptyList()
        streamInfo = null
        vod = item
        attempt = 0
        status = PlaybackStatus.Loading
        controlsToken++
        tuneJob = scope.launch {
            val saved = channels.progressFor(item.url)
            resumeAt = if (item.fromStart) 0L else saved?.positionMs?.takeIf { position ->
                position > MIN_RESUME_MILLIS && (saved.durationMs <= 0 || position < saved.durationMs - END_MARGIN_MILLIS)
            } ?: 0L
            startVod(item, resumeAt)
        }
    }

    /** Whether there's another episode after this one. */
    val hasNextEpisode: Boolean
        get() = queue.indexOfFirst { it.url == vod?.url }.let { it >= 0 && it + 1 < queue.size }

    /** Moves to the next (+1) or previous (-1) episode, when playing from a series. */
    fun skipEpisode(delta: Int) {
        val index = queue.indexOfFirst { it.url == vod?.url }
        val target = queue.getOrNull(index + delta) ?: return
        playVod(target, queue)
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
        controlsToken++
    }

    /** Whether live TV can be rewound and fast-forwarded right now. */
    val liveSeekable: Boolean
        get() = vod == null && (timeshiftActive || (player.isCurrentMediaItemSeekable && player.duration > 60_000))

    fun seekBy(millis: Long) {
        val duration = player.duration
        var target = (player.currentPosition + millis).coerceAtLeast(0)
        if (duration > 0) {
            // Live TV can't go past the live point; a film stops just short of its end.
            target = target.coerceAtMost(if (vod == null) duration else duration - 1_000)
        }
        player.seekTo(target)
        controlsToken++
    }

    /** How far behind live the picture is, in milliseconds (0 when watching live). */
    fun behindLiveMillis(): Long {
        if (vod != null || !liveSeekable) return 0
        val duration = player.duration
        if (duration <= 0) return 0
        return (duration - player.currentPosition - LIVE_OFFSET_MILLIS).coerceAtLeast(0)
    }

    /** Jumps back to live after pausing or rewinding. */
    fun goLive() {
        player.seekToDefaultPosition()
        player.play()
        controlsToken++
    }

    /** A choice in the subtitle or audio menu. */
    data class TrackOption(val label: String, val selected: Boolean, internal val group: Tracks.Group?, internal val index: Int)

    fun trackOptions(type: Int): List<TrackOption> {
        val options = ArrayList<TrackOption>()
        player.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                options += TrackOption(trackLabel(group.getTrackFormat(i), type, options.size + 1), group.isTrackSelected(i), group, i)
            }
        }
        return options
    }

    /** Shows the chosen subtitle or audio track; for subtitles, null turns them off. */
    fun selectTrack(type: Int, option: TrackOption?) {
        val params = player.trackSelectionParameters.buildUpon()
        val group = option?.group
        if (group == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            params.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        player.trackSelectionParameters = params.build()
        controlsToken++
    }

    /** Adds a downloaded subtitle file to the film playing and shows it. */
    fun addSubtitles(file: File, language: String) {
        val item = vod ?: return
        addedSubtitles = addedSubtitles + MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setLabel("OpenSubtitles")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val position = player.currentPosition
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .build()
        startVod(item, position)
        controlsToken++
    }

    private fun trackLabel(format: Format, type: Int, number: Int): String {
        val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.UK).ifBlank { it } }
        val name = format.label?.takeIf { it.isNotBlank() }
        val kind = when {
            type == C.TRACK_TYPE_TEXT && format.sampleMimeType == MimeTypes.APPLICATION_CEA608 -> "Closed captions"
            type == C.TRACK_TYPE_TEXT && format.sampleMimeType == MimeTypes.APPLICATION_DVBSUBS -> "Subtitles"
            type == C.TRACK_TYPE_AUDIO && format.channelCount >= 6 -> "Surround"
            else -> null
        }
        return listOfNotNull(name ?: language, kind).distinct().joinToString(" · ").ifEmpty {
            if (type == C.TRACK_TYPE_AUDIO) "Audio $number" else "Subtitles $number"
        }
    }


    fun showControls() {
        controlsToken++
    }

    private fun startVod(item: VodItem, from: Long) {
        if (stopped) return
        player.setMediaSource(sourceFor(item.url, item.userAgent, item.referrer, addedSubtitles))
        player.prepare()
        if (from > 0) player.seekTo(from)
        player.play()
        watchForStall()
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_SAVE_MILLIS)
                saveVodProgress()
            }
        }
    }

    private fun saveVodProgress() {
        val item = vod ?: return
        val position = player.currentPosition
        val duration = player.duration.coerceAtLeast(0)
        if (position < MIN_RESUME_MILLIS) return
        scope.launch {
            if (duration > 0 && position > duration - END_MARGIN_MILLIS) {
                channels.clearProgress(item.url)
            } else {
                channels.saveProgress(
                    ProgressEntity(
                        url = item.url,
                        title = item.title,
                        poster = item.poster,
                        userAgent = item.userAgent,
                        referrer = item.referrer,
                        positionMs = position,
                        durationMs = duration,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /** Saves where a film got to before something else plays. */
    private fun leaveVod() {
        upNextJob?.cancel()
        if (vod == null) return
        saveVodProgress()
        progressJob?.cancel()
        vod = null
    }

    private fun recoverVod() {
        val item = vod ?: return
        if (attempt < Reconnect.MAX_ATTEMPTS) {
            attempt++
            status = PlaybackStatus.Reconnecting(attempt)
            val position = player.currentPosition
            retryJob = scope.launch {
                delay(Reconnect.delayMillis(attempt))
                player.stop()
                startVod(item, position)
            }
        } else {
            player.stop()
            status = PlaybackStatus.Failed
        }
    }

    /** What's on [channel] now, for the channel list. */
    suspend fun nowOn(channel: LineupChannel): ProgrammeEntity? = channels.nowAndNext(channel).firstOrNull()

    /** Keeps [nowNext] up to date for as long as [channel] is playing. */
    private fun followGuide(channel: LineupChannel) {
        guideJob?.cancel()
        nowNext = emptyList()
        if (channel.guideId == null) return
        guideJob = scope.launch {
            while (true) {
                nowNext = channels.nowAndNext(channel)
                delay(GUIDE_REFRESH_MILLIS)
            }
        }
    }

    /** Channel up (+1) or down (-1) within the chosen group, wrapping round at the ends. */
    fun zap(delta: Int) {
        val list = visibleChannels
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.key == current?.key }
        tune(if (index < 0) list.first() else list[Lineup.step(list.size, index, delta)])
    }

    fun chooseGroup(name: String?) {
        group = name
    }

    fun showBanner() {
        bannerToken++
    }

    /** Starts the current channel again from its best copy. */
    fun retry() {
        vod?.let { item ->
            cancelRecovery()
            attempt = 0
            status = PlaybackStatus.Loading
            startVod(item, player.currentPosition)
            return
        }
        if (current == null) return
        cancelRecovery()
        variantIndex = 0
        attempt = 0
        playVariant(PlaybackStatus.Loading)
    }

    /** The app went into the background: let go of the stream. */
    fun onStop() {
        if (vod != null) {
            saveVodProgress()
            resumeAt = player.currentPosition
            progressJob?.cancel()
        }
        stopped = true
        cancelRecovery()
        tuneJob?.cancel()
        stopTimeshift()
        player.stop()
    }

    /** The app came back: pick the stream up again at the live point. */
    fun onStart() {
        if (!stopped) return
        stopped = false
        val item = vod
        if (item != null) {
            attempt = 0
            status = PlaybackStatus.Loading
            startVod(item, resumeAt)
        } else if (current != null) {
            attempt = 0
            playVariant(PlaybackStatus.Loading)
        }
    }

    /** Stops playing and forgets the channel. */
    fun close() {
        leaveVod()
        loadJob?.cancel()
        tuneJob?.cancel()
        guideJob?.cancel()
        nowNext = emptyList()
        cancelRecovery()
        stopTimeshift()
        player.stop()
        player.clearMediaItems()
        current = null
        playingRow = null
        streamInfo = null
        status = PlaybackStatus.Idle
    }

    fun release() {
        close()
        player.release()
    }

    private fun playVariant(showing: PlaybackStatus) {
        val channel = current ?: return
        val id = channel.variantIds.getOrNull(variantIndex)
        if (id == null) {
            status = PlaybackStatus.Failed
            return
        }
        tuneJob?.cancel()
        stopTimeshift()
        status = showing
        tuneJob = scope.launch {
            val row = channels.row(id)
            if (row == null) {
                // The playlist was refreshed or removed underneath us.
                status = PlaybackStatus.Failed
                return@launch
            }
            playingRow = row
            if (stopped) return@launch
            val minutes = settings.timeshiftMinutes.first()
            val session = if (minutes > 0 && !isHls(row.url)) startTimeshift(row, minutes) else null
            if (stopped) return@launch
            if (session != null) {
                player.setMediaSource(timeshiftSource(session))
            } else {
                player.setMediaSource(sourceFor(row.url, row.userAgent, row.referrer))
            }
            player.prepare()
            player.play()
            watchForStall()
        }
    }

    /**
     * Starts recording [row] so it can be paused and rewound, and waits for the first slice.
     * Returns null (play the stream directly instead) if the recording doesn't get going.
     */
    private suspend fun startTimeshift(row: ChannelEntity, minutes: Int): TimeshiftSession? {
        val session = TimeshiftSession(
            http = http,
            url = row.url,
            userAgent = row.userAgent ?: USER_AGENT,
            referrer = row.referrer,
            root = timeshiftRoot,
            windowSeconds = minutes * 60.0,
            scope = scope,
            onFailed = { scope.launch(Dispatchers.Main) { onTimeshiftFailed() } },
        )
        timeshift = session
        val started = withTimeoutOrNull(TIMESHIFT_START_MILLIS) { runCatching { session.ready.await() }.isSuccess } ?: false
        if (!started || timeshift !== session) {
            session.stop()
            if (timeshift === session) timeshift = null
            return null
        }
        timeshiftActive = true
        return session
    }

    private fun stopTimeshift() {
        timeshift?.stop()
        timeshift = null
        timeshiftActive = false
    }

    /** The recording lost the stream for good: try the channel's next copy, or give up. */
    private fun onTimeshiftFailed() {
        val channel = current ?: return
        if (!timeshiftActive) return
        stopTimeshift()
        if (variantIndex + 1 < channel.variantIds.size) {
            variantIndex++
            attempt = 0
            playVariant(PlaybackStatus.TryingBackup)
        } else {
            player.stop()
            status = PlaybackStatus.Failed
        }
    }

    private fun timeshiftSource(session: TimeshiftSession): MediaSource {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(session.playlist))
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_OFFSET_MILLIS)
                    .setMinOffsetMs(2_000)
                    .build(),
            )
            .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context), extractors).createMediaSource(item)
    }

    private fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)


    private fun recover() {
        if (stopped) return
        if (vod != null) {
            cancelRecovery()
            recoverVod()
            return
        }
        val channel = current ?: return
        cancelRecovery()
        when {
            attempt < Reconnect.MAX_ATTEMPTS -> {
                attempt++
                status = PlaybackStatus.Reconnecting(attempt)
                retryJob = scope.launch {
                    delay(Reconnect.delayMillis(attempt))
                    player.stop()
                    player.prepare()
                    player.play()
                    watchForStall()
                }
            }
            variantIndex + 1 < channel.variantIds.size -> {
                variantIndex++
                attempt = 0
                playVariant(PlaybackStatus.TryingBackup)
            }
            else -> {
                player.stop()
                status = PlaybackStatus.Failed
            }
        }
    }

    private fun watchForStall() {
        stallJob?.cancel()
        stallJob = scope.launch {
            delay(STALL_MILLIS)
            val state = player.playbackState
            if (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE) recover()
        }
    }

    private fun cancelRecovery() {
        retryJob?.cancel()
        stallJob?.cancel()
    }

    private fun sourceFor(
        url: String,
        userAgent: String?,
        referrer: String?,
        subtitles: List<MediaItem.SubtitleConfiguration> = emptyList(),
    ): MediaSource {
        val data = OkHttpDataSource.Factory(http).setUserAgent(userAgent ?: USER_AGENT)
        referrer?.let { data.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(url).setSubtitleConfigurations(subtitles)
        if (isHls(url)) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        // Subtitle files added from OpenSubtitles are local files; everything else comes over the network.
        val factory = if (subtitles.isEmpty()) data else DefaultDataSource.Factory(context, data)
        return DefaultMediaSourceFactory(factory, extractors).createMediaSource(item.build())
    }

    private fun updateStreamInfo() {
        val parts = ArrayList<String>()
        player.videoFormat?.let { video ->
            if (video.height > 0) parts.add(resolutionLabel(video.height))
            if (video.frameRate > 0f) parts.add("${video.frameRate.roundToInt()}fps")
        }
        player.audioFormat?.let { audio ->
            when {
                audio.channelCount >= 8 -> parts.add("7.1")
                audio.channelCount >= 6 -> parts.add("5.1")
                audio.channelCount == 2 -> parts.add("Stereo")
                audio.channelCount == 1 -> parts.add("Mono")
            }
        }
        streamInfo = parts.joinToString(" · ").ifEmpty { null }
    }

    private fun resolutionLabel(height: Int): String = when {
        height >= 2000 -> "4K"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> "${height}p"
    }

    private inner class Listener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    stallJob?.cancel()
                    attempt = 0
                    status = PlaybackStatus.Playing
                    updateStreamInfo()
                }
                Player.STATE_BUFFERING -> if (status == PlaybackStatus.Playing) watchForStall()
                Player.STATE_ENDED -> {
                    val item = vod
                    if (item != null) {
                        // A film that reaches its end is done: nothing to resume.
                        progressJob?.cancel()
                        stallJob?.cancel()
                        scope.launch { channels.clearProgress(item.url) }
                        val next = queue.getOrNull(queue.indexOfFirst { it.url == item.url } + 1)
                        if (next == null) {
                            status = PlaybackStatus.Finished
                        } else {
                            upNextJob?.cancel()
                            upNextJob = scope.launch {
                                for (seconds in UP_NEXT_SECONDS downTo 1) {
                                    status = PlaybackStatus.UpNext(next.title, seconds)
                                    delay(1_000)
                                }
                                playVod(next, queue)
                            }
                        }
                    } else {
                        // A live stream should never end; the server has dropped it.
                        recover()
                    }
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (vod == null && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Paused or rewound past the oldest part kept: carry on from the oldest part still there.
                if (timeshiftActive) player.seekTo(5_000) else player.seekToDefaultPosition()
                player.prepare()
                return
            }
            recover()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateStreamInfo()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateStreamInfo()
        }
    }

    private companion object {
        const val STALL_MILLIS = 20_000L
        const val GUIDE_REFRESH_MILLIS = 60_000L
        const val PROGRESS_SAVE_MILLIS = 10_000L
        const val MIN_RESUME_MILLIS = 30_000L
        const val END_MARGIN_MILLIS = 120_000L
        const val UP_NEXT_SECONDS = 10
        const val USER_AGENT = "Northlight/0.1 (Android TV)"
        const val TIMESHIFT_START_MILLIS = 12_000L
        /** How far behind the newest recorded slice "live" plays, so it never runs dry. */
        const val LIVE_OFFSET_MILLIS = 5_000L
    }
}
