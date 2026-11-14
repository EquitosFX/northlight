package app.tvplayer.player

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
}

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
    context: Context,
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

    /** Goes up by one whenever the info banner should appear. */
    var bannerToken by mutableIntStateOf(0)
        private set

    val visibleChannels: List<LineupChannel> get() = lineup.inGroup(group)

    private var variantIndex = 0
    private var attempt = 0
    private var stopped = false
    private var loadJob: Job? = null
    private var tuneJob: Job? = null
    private var retryJob: Job? = null
    private var stallJob: Job? = null
    private var guideJob: Job? = null

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

    /** Loads the channel list and plays [channelId], or else the last channel watched, or else the first. */
    fun open(channelId: Long?) {
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
            val lastKey = if (channelId == null) settings.lastChannel.first() else null
            val start = channelId?.let { built.findById(it) }
                ?: lastKey?.let { built.findByKey(it) }
                ?: built.channels.first()
            tune(start)
        }
    }

    fun tune(channel: LineupChannel) {
        cancelRecovery()
        current = channel
        variantIndex = 0
        attempt = 0
        streamInfo = null
        bannerToken++
        scope.launch { settings.setLastChannel(channel.key) }
        followGuide(channel)
        playVariant(PlaybackStatus.Loading)
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
        if (current == null) return
        cancelRecovery()
        variantIndex = 0
        attempt = 0
        playVariant(PlaybackStatus.Loading)
    }

    /** The app went into the background: let go of the stream. */
    fun onStop() {
        stopped = true
        cancelRecovery()
        tuneJob?.cancel()
        player.stop()
    }

    /** The app came back: pick the stream up again at the live point. */
    fun onStart() {
        if (!stopped) return
        stopped = false
        if (current != null) {
            attempt = 0
            playVariant(PlaybackStatus.Loading)
        }
    }

    /** Stops playing and forgets the channel. */
    fun close() {
        loadJob?.cancel()
        tuneJob?.cancel()
        guideJob?.cancel()
        nowNext = emptyList()
        cancelRecovery()
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
            player.setMediaSource(sourceFor(row))
            player.prepare()
            player.play()
            watchForStall()
        }
    }

    private fun recover() {
        val channel = current ?: return
        if (stopped) return
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

    private fun sourceFor(row: ChannelEntity): MediaSource {
        val data = OkHttpDataSource.Factory(http).setUserAgent(row.userAgent ?: USER_AGENT)
        row.referrer?.let { data.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder().setUri(row.url)
        if (row.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return DefaultMediaSourceFactory(data, extractors).createMediaSource(item.build())
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
                // A live stream should never end; the server has dropped it.
                Player.STATE_ENDED -> recover()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
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
        const val USER_AGENT = "Northlight/0.1 (Android TV)"
    }
}
