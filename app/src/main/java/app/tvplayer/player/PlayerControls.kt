package app.tvplayer.player

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.key.key
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import app.tvplayer.core.subtitles.SubtitleResult
import app.tvplayer.data.SubtitlesRepository
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The icons on the player buttons, drawn rather than taken from a font so they're crisp at any size. */
enum class ControlIcon { REWIND, PLAY, PAUSE, FORWARD, LIVE, SUBTITLES, AUDIO, CHANNELS, NEXT, SLEEP }

private enum class Panel { NONE, SUBTITLES, AUDIO, FIND }

/**
 * The player's controls, shown when OK is pressed while watching: a timeline, then play/pause,
 * rewind, fast-forward, back to live, subtitles, audio, and channels or the next episode.
 * They hide themselves after a few seconds without a key press (unless paused or a menu is open).
 */
@Composable
fun PlayerControls(
    controller: LivePlayerController,
    subtitles: SubtitlesRepository,
    /** What's playing, shown large above the buttons: the channel and programme, or the film. */
    info: @Composable () -> Unit,
    onChannels: (() -> Unit)?,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val live = controller.vod == null
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var behind by remember { mutableLongStateOf(0L) }
    var seekable by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var touched by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    // Nothing is highlighted when the controls open: focus waits on an invisible spot. OK from there
    // pauses straight away; an arrow press highlights Pause (Up: the progress bar).
    val restFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    var resting by remember { mutableStateOf(true) }
    var okPressed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { restFocus.requestFocus() }
        while (true) {
            position = controller.mediaPosition()
            duration = controller.mediaDuration()
            playing = controller.player.isPlaying
            seekable = controller.mediaSeekable
            behind = controller.behindLiveMillis()
            delay(250)
        }
    }
    // Hide after a while without a key press, but not while paused or choosing from a menu.
    LaunchedEffect(touched, panel, playing) {
        if (panel != Panel.NONE || !playing) return@LaunchedEffect
        delay(HIDE_MILLIS)
        onHide()
    }
    LaunchedEffect(panel) {
        if (panel != Panel.NONE) {
            runCatching { panelFocus.requestFocus() }
        } else if (!resting) {
            runCatching { playFocus.requestFocus() }
        }
    }
    BackHandler {
        if (panel == Panel.FIND) panel = Panel.SUBTITLES else if (panel != Panel.NONE) panel = Panel.NONE else onHide()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) touched++
                false
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim, colors.scrim)))
                .padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            info()
            Spacer(Modifier.height(4.dp))
            Timeline(
                live = live,
                seekable = seekable,
                position = position,
                duration = duration,
                behind = behind,
                startClock = controller.vod?.catchupPlan?.startMs,
                focusRequester = timelineFocus,
                onSeek = { controller.seekToMedia(it) },
            )
            // Scrolls sideways if the buttons don't all fit, so none is ever off the edge.
            Row(
                // Room at each end for the highlighted button, which grows a little.
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(restFocus)
                        .onPreviewKeyEvent { event ->
                            val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                            // OK pauses when it's let go, not pressed: Pause/Play is highlighted then, and
                            // highlighting it on the press made the release click it too, playing straight away again.
                            // (Only a press that started here: letting go of the OK that opened the controls doesn't count.)
                            if (ok && event.type == KeyEventType.KeyUp && okPressed) {
                                okPressed = false
                                resting = false
                                controller.togglePause()
                                playing = !playing
                                runCatching { playFocus.requestFocus() }
                                return@onPreviewKeyEvent true
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                // Up goes straight to the progress bar, to move along it; the others to Pause.
                                Key.DirectionUp -> {
                                    resting = false
                                    if (!seekable || runCatching { timelineFocus.requestFocus() }.isFailure) {
                                        runCatching { playFocus.requestFocus() }
                                    }
                                    true
                                }
                                Key.DirectionLeft, Key.DirectionRight, Key.DirectionDown -> {
                                    resting = false
                                    runCatching { playFocus.requestFocus() }
                                    true
                                }
                                // OK again pauses (above, when the button is let go).
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    okPressed = true
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusProperties { canFocus = resting }
                        .focusable(),
                )
                ControlButton(ControlIcon.REWIND, "5 min", enabled = seekable) { controller.seekBy(-BIG_STEP_MILLIS) }
                ControlButton(ControlIcon.REWIND, "30 s", enabled = seekable) { controller.seekBy(-STEP_MILLIS) }
                ControlButton(
                    if (playing) ControlIcon.PAUSE else ControlIcon.PLAY,
                    if (playing) "Pause" else "Play",
                    modifier = Modifier.focusRequester(playFocus),
                ) { controller.togglePause() }
                ControlButton(ControlIcon.FORWARD, "30 s", enabled = seekable && (!live || behind > 2_000)) {
                    controller.seekBy(STEP_MILLIS)
                }
                ControlButton(ControlIcon.FORWARD, "5 min", enabled = seekable && (!live || behind > 2_000)) {
                    controller.seekBy(BIG_STEP_MILLIS)
                }
                if (live) {
                    ControlButton(ControlIcon.LIVE, if (behind > 2_000) "Go live" else "Live", enabled = behind > 2_000 || !playing) {
                        controller.goLive()
                    }
                }
                Spacer(Modifier.width(6.dp))
                ControlButton(ControlIcon.SUBTITLES, "Subtitles") { panel = Panel.SUBTITLES }
                ControlButton(ControlIcon.AUDIO, "Audio") { panel = Panel.AUDIO }
                if (onChannels != null) ControlButton(ControlIcon.CHANNELS, "Channels") { onChannels() }
                // Each press moves on: off, 30 min, 1 hour, 1½ hours, 2 hours, off.
                // A fixed width, so the button doesn't grow off the edge as the time is added.
                ControlButton(ControlIcon.SLEEP, sleepLabel(controller.sleepMinutes), modifier = Modifier.width(112.dp)) {
                    controller.cycleSleep()
                }
                if (!live && controller.hasNextEpisode) ControlButton(ControlIcon.NEXT, "Next episode") { controller.skipEpisode(+1) }
            }
            if (live && !seekable) {
                Label(
                    text = when {
                        controller.timeshiftMinutes == 0 -> "Pause and rewind live TV is off. Turn it on in Settings › Player."
                        controller.status != PlaybackStatus.Playing -> "Rewinding is ready once the channel starts playing."
                        else -> "This channel can't be rewound."
                    },
                    style = AppType.caption,
                    color = colors.textFaint,
                )
            }
        }

        if (panel != Panel.NONE) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(360.dp)
                    .background(colors.scrim)
                    .padding(horizontal = 20.dp, vertical = 28.dp),
            ) {
                when (panel) {
                    Panel.SUBTITLES -> TrackPanel(
                        heading = "Subtitles",
                        controller = controller,
                        type = C.TRACK_TYPE_TEXT,
                        focus = panelFocus,
                        extra = if (live) null else "Find on OpenSubtitles…" to { panel = Panel.FIND },
                        onDone = { panel = Panel.NONE },
                    )
                    Panel.AUDIO -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TrackPanel(
                            heading = "Audio",
                            controller = controller,
                            type = C.TRACK_TYPE_AUDIO,
                            focus = panelFocus,
                            extra = null,
                            onDone = { panel = Panel.NONE },
                        )
                        AudioSyncRows(controller)
                    }
                    Panel.FIND -> FindSubtitles(controller = controller, subtitles = subtitles, focus = panelFocus, onDone = { panel = Panel.NONE })
                    Panel.NONE -> Unit
                }
            }
        }
    }
}

/**
 * The progress bar. Up from the buttons highlights it; then Left and Right move a marker along it
 * (hold to go faster: 10 s steps, then 30 s, 2 min and 5 min) and playback jumps there a moment after
 * you stop, or straight away with OK.
 */
@Composable
private fun Timeline(
    live: Boolean,
    seekable: Boolean,
    position: Long,
    duration: Long,
    behind: Long,
    /** For catch-up: the programme's start, so the marker can show the time of day. */
    startClock: Long?,
    focusRequester: FocusRequester,
    onSeek: (Long) -> Unit,
) {
    val colors = AppTheme.colors
    if (live && !seekable) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveChip(on = true)
        }
        return
    }
    var focused by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Long?>(null) }
    // Jump once the marker has stayed put for a moment, so holding Left/Right doesn't restart the stream at every step.
    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        delay(SCRUB_SETTLE_MILLIS)
        onSeek(target)
        delay(1_500)
        pending = null
    }
    val shown = pending ?: position
    val fraction = if (duration > 0) (shown.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val playedFraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || duration <= 0) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                val step = when {
                    repeat < 3 -> 10_000L
                    repeat < 12 -> 30_000L
                    repeat < 30 -> 120_000L
                    else -> 300_000L
                }.coerceAtLeast(if (startClock != null) 60_000L else 0L) // catch-up servers work in whole minutes
                when (event.key) {
                    Key.DirectionLeft -> {
                        pending = ((pending ?: position) - step).coerceIn(0, duration)
                        true
                    }
                    Key.DirectionRight -> {
                        pending = ((pending ?: position) + step).coerceIn(0, duration)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        pending?.let(onSeek)
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 8.dp else 5.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(playedFraction).background(colors.accent))
        }
        if (focused || pending != null) {
            val knob = 18.dp
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth - knob) * fraction)
                    .size(knob)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (pending != null) colors.text else colors.accent)
                    .border(2.dp, colors.accent, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val target = pending
        if (target != null) {
            val clockText = startClock?.let { "  (${app.tvplayer.ui.components.clockTime(it + target)})" }.orEmpty()
            Label(text = "Jump to ${clock(target)}$clockText  ·  OK to go now", style = AppType.label, color = colors.accent)
            return@Row
        }
        if (focused) {
            Label(text = "Left/Right to move along  ·  hold to go faster", style = AppType.label, color = colors.accent)
            Spacer(Modifier.weight(1f))
        }
        if (live) {
            LiveChip(on = behind <= 2_000)
            Spacer(Modifier.width(10.dp))
            Label(
                text = if (behind > 2_000) "${clock(behind)} behind live" else "Watching live",
                style = AppType.label,
                color = colors.textDim,
            )
            Spacer(Modifier.weight(1f))
            Label(text = "Rewind up to ${clock(duration)}", style = AppType.label, color = colors.textFaint)
        } else {
            Label(text = "${clock(position)} / ${if (duration > 0) clock(duration) else "--:--"}", style = AppType.label, color = colors.textDim)
        }
    }
}

@Composable
private fun LiveChip(on: Boolean) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) Color(0xFFE5484D) else colors.glassStrong)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Label(text = "LIVE", style = AppType.overline, color = Color.White)
    }
}

@Composable
private fun ControlButton(
    icon: ControlIcon,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        container = colors.glass,
        focusedContainer = Color.White,
        outlined = false,
    ) { focused ->
        val tint = when {
            focused -> Color(0xFF0A0F1E)
            enabled -> colors.text
            else -> colors.textFaint.copy(alpha = 0.45f)
        }
        Row(
            modifier = Modifier.padding(horizontal = 10.dp).align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconGlyph(icon, tint, Modifier.size(18.dp))
            Label(text = label, style = AppType.label, color = tint)
        }
    }
}

@Composable
fun IconGlyph(icon: ControlIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawIcon(icon, tint) }
}

private fun DrawScope.drawIcon(icon: ControlIcon, tint: Color) {
    val w = size.width
    val h = size.height
    fun triangle(left: Float, right: Float, pointsRight: Boolean) = Path().apply {
        if (pointsRight) {
            moveTo(left, h * 0.15f); lineTo(right, h * 0.5f); lineTo(left, h * 0.85f)
        } else {
            moveTo(right, h * 0.15f); lineTo(left, h * 0.5f); lineTo(right, h * 0.85f)
        }
        close()
    }
    when (icon) {
        ControlIcon.PLAY -> drawPath(triangle(w * 0.22f, w * 0.85f, true), tint)
        ControlIcon.PAUSE -> {
            drawRoundRect(tint, Offset(w * 0.2f, h * 0.15f), Size(w * 0.2f, h * 0.7f), androidx.compose.ui.geometry.CornerRadius(2f))
            drawRoundRect(tint, Offset(w * 0.6f, h * 0.15f), Size(w * 0.2f, h * 0.7f), androidx.compose.ui.geometry.CornerRadius(2f))
        }
        ControlIcon.REWIND -> {
            drawPath(triangle(w * 0.05f, w * 0.5f, false), tint)
            drawPath(triangle(w * 0.5f, w * 0.95f, false), tint)
        }
        ControlIcon.FORWARD -> {
            drawPath(triangle(w * 0.05f, w * 0.5f, true), tint)
            drawPath(triangle(w * 0.5f, w * 0.95f, true), tint)
        }
        ControlIcon.NEXT -> {
            drawPath(triangle(w * 0.12f, w * 0.72f, true), tint)
            drawRect(tint, Offset(w * 0.74f, h * 0.15f), Size(w * 0.14f, h * 0.7f))
        }
        ControlIcon.LIVE -> {
            drawCircle(tint, radius = w * 0.16f, center = Offset(w / 2, h / 2))
            drawCircle(tint, radius = w * 0.36f, center = Offset(w / 2, h / 2), style = Stroke(width = w * 0.09f))
        }
        ControlIcon.SUBTITLES -> {
            drawRoundRect(tint, Offset(w * 0.06f, h * 0.18f), Size(w * 0.88f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(width = w * 0.09f))
            drawLine(tint, Offset(w * 0.22f, h * 0.45f), Offset(w * 0.5f, h * 0.45f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.6f, h * 0.45f), Offset(w * 0.78f, h * 0.45f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.22f, h * 0.62f), Offset(w * 0.36f, h * 0.62f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.46f, h * 0.62f), Offset(w * 0.78f, h * 0.62f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        }
        ControlIcon.AUDIO -> {
            val body = Path().apply {
                moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.3f, h * 0.38f); lineTo(w * 0.55f, h * 0.15f)
                lineTo(w * 0.55f, h * 0.85f); lineTo(w * 0.3f, h * 0.62f); lineTo(w * 0.1f, h * 0.62f); close()
            }
            drawPath(body, tint)
            drawArc(tint, -45f, 90f, false, Offset(w * 0.45f, h * 0.28f), Size(w * 0.36f, h * 0.44f), style = Stroke(width = w * 0.08f, cap = StrokeCap.Round))
        }
        ControlIcon.SLEEP -> {
            // A crescent moon: a circle with a bite taken out of it.
            val moon = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.46f, h * 0.5f), w * 0.38f))
            }
            val bite = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.68f, h * 0.36f), w * 0.3f))
            }
            drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite), tint)
        }
        ControlIcon.CHANNELS -> {
            for (k in 0..2) {
                val y = h * (0.25f + k * 0.25f)
                drawCircle(tint, radius = w * 0.06f, center = Offset(w * 0.14f, y))
                drawLine(tint, Offset(w * 0.3f, y), Offset(w * 0.9f, y), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun TrackPanel(
    heading: String,
    controller: LivePlayerController,
    type: Int,
    focus: FocusRequester,
    extra: Pair<String, () -> Unit>?,
    onDone: () -> Unit,
) {
    val colors = AppTheme.colors
    val options = remember(controller.controlsToken) { controller.trackOptions(type) }
    val isText = type == C.TRACK_TYPE_TEXT
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text = heading, style = AppType.headline, modifier = Modifier.padding(bottom = 8.dp))
        var first = true
        @Composable
        fun row(text: String, selected: Boolean, onClick: () -> Unit) {
            val mod = if (first) Modifier.focusRequester(focus) else Modifier
            first = false
            PanelRow(text = text, selected = selected, modifier = mod, onClick = onClick)
        }
        if (isText) row("Off", options.none { it.selected }) { controller.selectTrack(type, null); onDone() }
        options.forEach { option -> row(option.label, option.selected) { controller.selectTrack(type, option); onDone() } }
        if (!isText && options.isEmpty()) Label(text = "No other audio on this channel.", style = AppType.body, color = colors.textDim)
        if (isText && options.isEmpty()) {
            Label(
                text = if (extra == null) "This channel isn't sending any subtitles." else "This film has no subtitles of its own.",
                style = AppType.body,
                color = colors.textDim,
                maxLines = 2,
            )
        }
        extra?.let { (text, action) -> row(text, false) { action() } }
    }
}

/** Lip sync: move the sound earlier or later against the picture, in 50 ms steps. */
@Composable
private fun AudioSyncRows(controller: LivePlayerController) {
    val colors = AppTheme.colors
    val ms = controller.audioDelayMs
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text = "Sound in sync", style = AppType.title)
        Label(
            text = when {
                ms == 0 -> "In step with the picture"
                ms > 0 -> "Sound $ms ms later"
                else -> "Sound ${-ms} ms earlier"
            },
            style = AppType.label,
            color = if (ms == 0) colors.textDim else colors.accent,
        )
        PanelRow(text = "Sound earlier (− 50 ms)", selected = false) { controller.changeAudioDelay(-50) }
        PanelRow(text = "Sound later (+ 50 ms)", selected = false) { controller.changeAudioDelay(+50) }
        if (ms != 0) PanelRow(text = "Reset", selected = false) { controller.setAudioDelay(0) }
    }
}

private fun sleepLabel(minutes: Int) = when (minutes) {
    0 -> "Sleep"
    60 -> "Sleep 1h"
    90 -> "Sleep 1½h"
    120 -> "Sleep 2h"
    else -> "Sleep ${minutes}m"
}

@Composable
private fun PanelRow(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(6.dp),
        container = if (selected) colors.glassStrong else Color.Transparent,
        outlined = false,
        focusScale = 1.01f,
    ) {
        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (selected) colors.accent else Color.Transparent))
            Label(text = text, style = AppType.title, maxLines = 1)
        }
    }
}

@Composable
private fun FindSubtitles(controller: LivePlayerController, subtitles: SubtitlesRepository, focus: FocusRequester, onDone: () -> Unit) {
    val colors = AppTheme.colors
    val item = controller.vod ?: return
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<SubtitleResult>?>(null) }
    var message by remember { mutableStateOf<String?>("Searching OpenSubtitles…") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(item.url) {
        try {
            val found = subtitles.search(item)
            results = found
            message = if (found.isEmpty()) "OpenSubtitles has nothing for this in your language." else null
        } catch (e: Exception) {
            message = e.message ?: "The search didn't work."
        }
    }
    LaunchedEffect(results) { if (!results.isNullOrEmpty()) runCatching { focus.requestFocus() } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text = "OpenSubtitles", style = AppType.headline, modifier = Modifier.padding(bottom = 4.dp))
        message?.let { Label(text = it, style = AppType.body, color = colors.accent, maxLines = 3) }
        val list = results.orEmpty()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(list) { index, result ->
                FocusSurface(
                    onClick = {
                        if (busy) return@FocusSurface
                        busy = true
                        message = "Downloading…"
                        scope.launch {
                            try {
                                val file = subtitles.download(result)
                                controller.addSubtitles(file, result.language.ifBlank { subtitles.language() })
                                onDone()
                            } catch (e: Exception) {
                                message = e.message ?: "The download didn't work."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = (if (index == 0) Modifier.focusRequester(focus) else Modifier).fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(6.dp),
                    container = Color.Transparent,
                    outlined = false,
                    focusScale = 1.01f,
                ) {
                    Column(modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp)) {
                        Label(text = result.release.ifBlank { "Subtitles" }, style = AppType.title, maxLines = 1)
                        Label(
                            text = listOfNotNull(
                                Locale.forLanguageTag(result.language).getDisplayLanguage(Locale.UK).ifBlank { result.language },
                                "${result.downloads} downloads",
                                if (result.hearingImpaired) "Hearing impaired" else null,
                            ).joinToString(" · "),
                            style = AppType.caption,
                            color = colors.textDim,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun clock(millis: Long): String {
    val total = millis / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private const val STEP_MILLIS = 30_000L
private const val BIG_STEP_MILLIS = 300_000L
private const val SCRUB_SETTLE_MILLIS = 800L
private const val HIDE_MILLIS = 8_000L

/** Live TV details for the controls: logo, number and name, the programme on, its times, what's next. */
@Composable
fun LiveInfo(controller: LivePlayerController) {
    val colors = AppTheme.colors
    val channel = controller.current ?: return
    val now = controller.nowNext.firstOrNull()
    val next = controller.nowNext.getOrNull(1)
    val clockNow = System.currentTimeMillis()
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
        app.tvplayer.ui.components.Logo(url = channel.logo, modifier = Modifier.size(width = 84.dp, height = 60.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Label(text = "${channel.number}  ${channel.name}", style = InfoName, maxLines = 1)
            if (now != null) {
                Label(text = now.title, style = InfoTitle, maxLines = 1)
                val left = ((now.stop - clockNow) / 60_000).coerceAtLeast(0)
                Label(
                    text = listOfNotNull(
                        "${app.tvplayer.ui.components.clockTime(now.start)}–${app.tvplayer.ui.components.clockTime(now.stop)}",
                        if (clockNow in now.start until now.stop) "$left min left" else null,
                        now.category,
                    ).joinToString("  ·  "),
                    style = InfoMeta,
                    color = colors.accent,
                )
                now.description?.let { Label(text = it, style = InfoBody, color = colors.textDim, maxLines = 2) }
            } else {
                Label(text = "No programme information", style = InfoBody, color = colors.textDim)
            }
            if (next != null) {
                Label(
                    text = "Next  ${app.tvplayer.ui.components.clockTime(next.start)}  ${next.title}",
                    style = InfoMeta,
                    color = colors.textDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Film or episode details for the controls: title, year, rating, length and plot. */
@Composable
fun VodInfo(title: String, details: app.tvplayer.data.db.MetadataEntity?) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(text = title, style = InfoName, maxLines = 1)
        val meta = listOfNotNull(
            details?.year?.toString(),
            details?.rating?.let { "★ %.1f".format(it) },
            details?.runtimeMinutes?.let { "${it / 60}h ${it % 60}m" },
            details?.genres,
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) Label(text = meta, style = InfoMeta, color = colors.accent, maxLines = 1)
        details?.overview?.let { Label(text = it, style = InfoBody, color = colors.textDim, maxLines = 3) }
    }
}

private val InfoName = AppType.headline.copy(fontSize = 28.sp, lineHeight = 32.sp)
private val InfoTitle = AppType.title.copy(fontSize = 20.sp, lineHeight = 24.sp)
private val InfoMeta = AppType.label.copy(fontSize = 15.sp, lineHeight = 19.sp)
private val InfoBody = AppType.body.copy(fontSize = 16.sp, lineHeight = 22.sp)
