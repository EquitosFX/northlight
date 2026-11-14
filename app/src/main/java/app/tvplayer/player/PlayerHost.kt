package app.tvplayer.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.tvplayer.core.channels.Quality
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.live.Reconnect
import app.tvplayer.data.db.ProgrammeEntity
import app.tvplayer.ui.components.ProgressLine
import app.tvplayer.ui.components.clockTime
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.delay

enum class PlayerMode { NONE, FULL, MINI }

/**
 * The video, full screen or as the small picture in the bottom-right corner while browsing.
 * The same video surface is kept when switching between the two, so playback carries on.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerHost(
    controller: LivePlayerController,
    mode: PlayerMode,
    onMinimise: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Hold the last picture while the next channel starts, instead of flashing black.
                    setKeepContentOnPlayerReset(true)
                    keepScreenOn = true
                    // Focus stays with the Compose screens, never inside the video view.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    player = controller.player
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (mode == PlayerMode.FULL && controller.vod != null) {
            VodLayer(controller = controller, onClose = onClose)
        } else if (mode == PlayerMode.FULL) {
            FullScreenLayer(controller = controller, onMinimise = onMinimise, onClose = onClose)
        } else {
            MiniLayer(onExpand = onExpand)
        }
    }
}

@Composable
private fun FullScreenLayer(controller: LivePlayerController, onMinimise: () -> Unit, onClose: () -> Unit) {
    var listOpen by remember { mutableStateOf(false) }
    val surfaceFocus = remember { FocusRequester() }

    // Back from full screen shrinks the picture; with nothing playing it just closes.
    BackHandler { if (controller.current == null) onClose() else onMinimise() }

    LaunchedEffect(listOpen) {
        if (!listOpen) runCatching { surfaceFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(surfaceFocus)
            .onKeyEvent { event ->
                // While the channel list is open its own rows handle the keys.
                if (listOpen || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        controller.zap(+1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        controller.zap(-1)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (controller.status == PlaybackStatus.Failed) {
                            controller.retry()
                        } else if (!controller.lineup.isEmpty) {
                            listOpen = true
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight, Key.Info -> {
                        controller.showBanner()
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { canFocus = !listOpen }
            .focusable(),
    ) {
        InfoBanner(controller = controller, modifier = Modifier.align(Alignment.BottomCenter))
        StatusMessage(status = controller.status, modifier = Modifier.align(Alignment.Center))

        if (listOpen) {
            ChannelListOverlay(
                controller = controller,
                onDismiss = { listOpen = false },
                onPick = { channel ->
                    if (channel.key != controller.current?.key) controller.tune(channel)
                    listOpen = false
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/** Controls for a film or episode: OK pauses, Left and Right skip 10 seconds, Back stops and saves the place. */
@Composable
private fun VodLayer(controller: LivePlayerController, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val item = controller.vod ?: return
    val focus = remember { FocusRequester() }
    var visible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    BackHandler { onClose() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(controller.controlsToken) {
        visible = true
        delay(BANNER_MILLIS)
        visible = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = controller.player.currentPosition
            duration = controller.player.duration.coerceAtLeast(0)
            playing = controller.player.isPlaying
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (controller.status == PlaybackStatus.Failed) controller.retry() else controller.togglePause()
                        true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        controller.seekBy(-SKIP_MILLIS)
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        controller.seekBy(SKIP_MILLIS)
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.Info -> {
                        controller.showControls()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        StatusMessage(status = controller.status, modifier = Modifier.align(Alignment.Center))
        AnimatedVisibility(
            visible = visible || !playing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim)))
                    .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label(text = item.title, style = AppType.headline)
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(colors.accent),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Label(
                        text = "${playTime(position)} / ${if (duration > 0) playTime(duration) else "--:--"}",
                        style = AppType.label,
                        color = colors.textDim,
                    )
                    Box(modifier = Modifier.weight(1f))
                    Label(
                        text = if (playing) "OK pause · Left/Right skip 10 s · Back to stop" else "Paused · OK to play",
                        style = AppType.label,
                        color = colors.textDim,
                    )
                }
            }
        }
    }
}

private fun playTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun MiniLayer(onExpand: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onExpand,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(0.dp),
        container = Color.Transparent,
        focusedContainer = Color.White.copy(alpha = 0.06f),
        focusScale = 1f,
    ) { focused ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim)))
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Label(
                text = if (focused) "OK for full screen" else "Hold Back for full screen",
                style = AppType.caption,
                color = colors.textDim,
            )
        }
    }
}

@Composable
private fun InfoBanner(controller: LivePlayerController, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val channel = controller.current ?: return
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(controller.bannerToken) {
        visible = true
        delay(BANNER_MILLIS)
        visible = false
    }

    val row = controller.playingRow
    val quality = row?.let { r -> Quality.entries.firstOrNull { it.rank == r.quality }?.label?.takeIf { it.isNotEmpty() } }
    val onBackup = row != null && row.id != channel.variantIds.firstOrNull()
    val details = listOfNotNull(
        channel.group,
        quality,
        controller.streamInfo,
        if (onBackup) "Backup copy" else null,
    ).joinToString(" · ")

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim)))
                .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 28.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(url = channel.logo, modifier = Modifier.size(56.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Label(text = "${channel.number}  ${channel.name}", style = AppType.headline)
                    NowNextLines(controller.nowNext)
                    if (details.isNotEmpty()) {
                        Label(text = details, style = AppType.caption, color = colors.textDim)
                    }
                }
            }
        }
    }
}

@Composable
private fun NowNextLines(programmes: List<ProgrammeEntity>) {
    val colors = AppTheme.colors
    val now = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.start <= now }
    val next = programmes.firstOrNull { it.start > now }
    if (current != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(
                text = "${clockTime(current.start)}–${clockTime(current.stop)}",
                style = AppType.label,
                color = colors.accent,
            )
            Label(text = current.title, style = AppType.title, modifier = Modifier.width(420.dp))
        }
        ProgressLine(start = current.start, stop = current.stop, modifier = Modifier.width(300.dp))
    }
    if (next != null) {
        Label(text = "Next  ${clockTime(next.start)}  ${next.title}", style = AppType.body, color = colors.textDim)
    }
}

@Composable
private fun StatusMessage(status: PlaybackStatus, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    // "Tuning" only appears if a channel is slow to start, so quick channel changes don't flicker.
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        showLoading = false
        if (status == PlaybackStatus.Loading) {
            delay(LOADING_DELAY_MILLIS)
            showLoading = true
        }
    }

    val text = when (status) {
        PlaybackStatus.Loading -> if (showLoading) "Tuning…" else null
        is PlaybackStatus.Reconnecting -> "Reconnecting… (${status.attempt} of ${Reconnect.MAX_ATTEMPTS})"
        PlaybackStatus.TryingBackup -> "Trying another copy of this channel…"
        PlaybackStatus.Failed -> "This channel isn't playing right now.\nOK to try again · Up or Down for another channel"
        PlaybackStatus.NoChannels -> "No live channels yet.\nAdd a playlist in Settings › Playlists."
        PlaybackStatus.Finished -> "Finished · Back to return"
        PlaybackStatus.Idle, PlaybackStatus.Playing -> null
    } ?: return

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.scrim)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Label(text = text, style = AppType.body, maxLines = 3)
    }
}

@Composable
private fun ChannelListOverlay(
    controller: LivePlayerController,
    onDismiss: () -> Unit,
    onPick: (LineupChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    var showGroups by remember { mutableStateOf(false) }
    val channels = controller.visibleChannels
    val currentKey = controller.current?.key
    val startIndex = remember(channels) { channels.indexOfFirst { it.key == currentKey }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val itemFocus = remember { FocusRequester() }

    BackHandler { if (showGroups) showGroups = false else onDismiss() }

    // Put focus on the channel that is playing (or the first in a newly chosen group).
    LaunchedEffect(channels, showGroups) {
        if (!showGroups && channels.isNotEmpty()) {
            listState.scrollToItem((startIndex - 3).coerceAtLeast(0))
            withFrameNanos { }
            runCatching { itemFocus.requestFocus() }
        }
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color.Transparent, colors.scrim, colors.scrim)))
            .padding(start = 60.dp, end = 32.dp, top = 24.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showGroups) {
            GroupList(
                groups = controller.lineup.groups,
                selected = controller.group,
                onPick = { name ->
                    controller.chooseGroup(name)
                    showGroups = false
                },
            )
        }

        Column(
            modifier = Modifier
                .width(330.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Label(text = controller.group ?: "All channels", style = AppType.headline)
            val hint = if (controller.lineup.groups.isEmpty()) "" else " · Left for groups"
            Label(text = "${channels.size} channels$hint", style = AppType.caption, color = colors.textDim)

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        val opensGroups = !showGroups &&
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionLeft &&
                            controller.lineup.groups.isNotEmpty()
                        if (opensGroups) showGroups = true
                        opensGroups
                    },
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.key }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        controller = controller,
                        playing = channel.key == currentKey,
                        onClick = { onPick(channel) },
                        modifier = if (index == startIndex) Modifier.focusRequester(itemFocus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LineupChannel,
    controller: LivePlayerController,
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val onNow by produceState<ProgrammeEntity?>(initialValue = null, channel.key) {
        value = controller.nowOn(channel)
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        container = if (playing) colors.glassStrong else Color.Transparent,
        outlined = false,
        focusScale = 1.02f,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                text = channel.number.toString(),
                style = AppType.label,
                color = colors.textDim,
                modifier = Modifier.width(38.dp),
            )
            Logo(url = channel.logo, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.weight(1f)) {
                Label(text = channel.name, style = AppType.title)
                val group = channel.group
                val programme = onNow
                when {
                    programme != null -> Label(
                        text = programme.title,
                        style = AppType.caption,
                        color = if (playing) colors.accent else colors.textDim,
                    )
                    playing -> Label(text = "Playing now", style = AppType.caption, color = colors.accent)
                    group != null -> Label(text = group, style = AppType.caption, color = colors.textDim)
                }
            }
        }
    }
}

@Composable
private fun GroupList(groups: List<String>, selected: String?, onPick: (String?) -> Unit) {
    val colors = AppTheme.colors
    val entries = remember(groups) { listOf<String?>(null) + groups }
    val startIndex = remember(entries, selected) { entries.indexOf(selected).coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val itemFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { itemFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .width(230.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label(text = "Groups", style = AppType.headline)
        Label(text = "${groups.size} groups", style = AppType.caption, color = colors.textDim)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(entries) { index, name ->
                val isSelected = name == selected
                FocusSurface(
                    onClick = { onPick(name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .then(if (index == startIndex) Modifier.focusRequester(itemFocus) else Modifier),
                    container = if (isSelected) colors.glassStrong else Color.Transparent,
                    outlined = false,
                    focusScale = 1.02f,
                ) {
                    Label(
                        text = name ?: "All channels",
                        style = AppType.title,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }
}

private const val BANNER_MILLIS = 5_000L
private const val LOADING_DELAY_MILLIS = 600L
private const val SKIP_MILLIS = 10_000L
