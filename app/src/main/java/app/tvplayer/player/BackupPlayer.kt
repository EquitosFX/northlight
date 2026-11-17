package app.tvplayer.player

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** A stream for the backup player: its address and headers, and where to start in it. */
data class BackupStream(
    val url: String,
    val title: String,
    val userAgent: String?,
    val referrer: String?,
    val live: Boolean,
    val startMs: Long = 0,
    /** The viewer's lip-sync setting, in milliseconds. */
    val audioDelayMs: Int = 0,
)

/**
 * The backup player: VLC's engine, used when the normal player can't play a stream. It copes with
 * streams that are slightly out of spec, unusual codecs and odd server behaviour. OK pauses, Left and
 * Right skip 10 seconds (films), Up and Down change channel (live), Back closes it.
 */
@Composable
fun BackupPlayerLayer(
    stream: BackupStream,
    onZap: ((Int) -> Unit)?,
    onClose: () -> Unit,
) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf("Opening with the backup player…") }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var bannerToken by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    // VLC's engine and player live as long as this layer.
    val engine = remember(stream) {
        runCatching {
            val vlc = LibVLC(context, arrayListOf("--network-caching=2000", "--http-reconnect", "--no-stats"))
            vlc to MediaPlayer(vlc)
        }.onFailure { problem = "The backup player isn't available on this device." }.getOrNull()
    }
    val layout = remember(stream) { runCatching { VLCVideoLayout(context) }.getOrNull() }

    DisposableEffect(engine, layout) {
        val (vlc, player) = engine ?: return@DisposableEffect onDispose { }
        if (layout != null) player.attachViews(layout, null, false, false)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    if (stream.audioDelayMs != 0) runCatching { player.setAudioDelay(stream.audioDelayMs * 1_000L) }
                    layout?.keepScreenOn = true
                    playing = true
                    state = "Playing with the backup player"
                }
                // Paused, the TV may sleep as usual (no picture left burning in).
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    layout?.keepScreenOn = false
                    playing = false
                }
                MediaPlayer.Event.Buffering -> if (event.buffering < 100f) state = "Buffering… ${event.buffering.toInt()}%"
                MediaPlayer.Event.TimeChanged -> position = event.timeChanged
                MediaPlayer.Event.LengthChanged -> length = event.lengthChanged
                MediaPlayer.Event.EncounteredError -> problem = "The backup player couldn't play this channel either. It may be off air."
                MediaPlayer.Event.EndReached -> if (!stream.live) onClose()
            }
        }
        val media = Media(vlc, Uri.parse(stream.url)).apply {
            stream.userAgent?.let { addOption(":http-user-agent=$it") }
            stream.referrer?.let { addOption(":http-referrer=$it") }
            if (stream.startMs > 0) addOption(":start-time=${stream.startMs / 1000}")
            setHWDecoderEnabled(true, false)
        }
        player.media = media
        media.release()
        player.play()
        onDispose {
            runCatching {
                player.setEventListener(null)
                player.stop()
                player.detachViews()
                player.release()
                vlc.release()
            }
        }
    }

    // Pressing Home stops the backup player's sound too; it carries on when Skyfall comes back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(engine, lifecycle) {
        val player = engine?.second
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> runCatching { player?.pause() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> runCatching { if (player?.isPlaying == false) player.play() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bannerToken) {
        bannerVisible = true
        delay(5_000)
        if (problem == null) bannerVisible = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { onClose() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val player = engine?.second ?: return@onPreviewKeyEvent false
                bannerToken++
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!stream.live && player.isSeekable) player.time = (player.time - 10_000).coerceAtLeast(0)
                        true
                    }
                    Key.DirectionRight -> {
                        if (!stream.live && player.isSeekable) player.time = player.time + 10_000
                        true
                    }
                    // The same way round as the main player: Up is the next channel.
                    Key.DirectionUp, Key.ChannelUp -> {
                        onZap?.invoke(1)
                        onZap != null
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        onZap?.invoke(-1)
                        onZap != null
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        if (layout != null) {
            AndroidView(
                factory = {
                    layout.apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        isFocusable = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (bannerVisible || problem != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 48.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Label(text = stream.title, style = AppType.headline, maxLines = 1)
                Label(text = problem ?: state, style = AppType.title, color = if (problem != null) colors.accent else colors.textDim)
                val times = if (!stream.live && length > 0) "  ·  ${clockText(position)} / ${clockText(length)}" else ""
                Label(
                    text = (if (stream.live) "Up/Down change channel" else "Left/Right skip 10 s") +
                        "  ·  OK ${if (playing) "pauses" else "plays"}  ·  Back to close$times",
                    style = AppType.label,
                    color = colors.textFaint,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) { Label(text = "BACKUP PLAYER", style = AppType.caption, color = colors.accent) }
    }
}

private fun clockText(millis: Long): String {
    val s = millis / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
