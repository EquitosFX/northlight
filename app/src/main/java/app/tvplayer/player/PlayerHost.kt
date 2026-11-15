package app.tvplayer.player

import androidx.compose.foundation.border
import androidx.compose.runtime.mutableIntStateOf
import app.tvplayer.core.intro.IntroSegments
import app.tvplayer.core.intro.IntroDb
import app.tvplayer.data.IntroRepository
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.MetadataRepository
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import app.tvplayer.data.SubtitlesRepository
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
    subtitles: SubtitlesRepository,
    metadata: MetadataRepository,
    intros: IntroRepository,
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
                    // Subtitles: white text on a soft dark box, a little larger than standard for a TV.
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.argb(150, 0, 0, 0),
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_NONE,
                                android.graphics.Color.BLACK,
                                null,
                            ),
                        )
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                        setBottomPaddingFraction(0.07f)
                    }
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        if (mode == PlayerMode.FULL && controller.vod != null) {
            VodLayer(controller = controller, subtitles = subtitles, metadata = metadata, intros = intros, onClose = onClose)
        } else if (mode == PlayerMode.FULL) {
            FullScreenLayer(controller = controller, subtitles = subtitles, onMinimise = onMinimise, onClose = onClose)
        } else {
            MiniLayer(onExpand = onExpand)
        }
    }
}

@Composable
private fun FullScreenLayer(
    controller: LivePlayerController,
    subtitles: SubtitlesRepository,
    onMinimise: () -> Unit,
    onClose: () -> Unit,
) {
    var listOpen by remember { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(false) }
    val surfaceFocus = remember { FocusRequester() }

    // Back from full screen shrinks the picture; with nothing playing it just closes.
    BackHandler { if (controller.current == null) onClose() else onMinimise() }

    LaunchedEffect(listOpen, controlsOpen) {
        if (!listOpen && !controlsOpen) runCatching { surfaceFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(surfaceFocus)
            .onKeyEvent { event ->
                // While the channel list or the controls are open, they handle the keys.
                if (listOpen || controlsOpen || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> {
                        controller.zap(+1)
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown -> {
                        controller.zap(-1)
                        true
                    }
                    // OK opens the player controls: pause, rewind, live, subtitles, audio, channels.
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (controller.status == PlaybackStatus.Failed) controller.retry() else controlsOpen = true
                        true
                    }
                    // Left opens the channel list.
                    Key.DirectionLeft -> {
                        if (!controller.lineup.isEmpty) listOpen = true
                        true
                    }
                    Key.DirectionRight, Key.Info -> {
                        controller.showBanner()
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        controller.togglePause()
                        true
                    }
                    Key.MediaRewind -> {
                        if (controller.liveSeekable) controller.seekBy(-30_000)
                        true
                    }
                    Key.MediaFastForward -> {
                        if (controller.liveSeekable) controller.seekBy(30_000)
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { canFocus = !listOpen && !controlsOpen }
            .focusable(),
    ) {
        if (!controlsOpen) InfoBanner(controller = controller, modifier = Modifier.align(Alignment.BottomCenter))
        StatusMessage(status = controller.status, modifier = Modifier.align(Alignment.Center))

        val channel = controller.current
        if (controlsOpen && channel != null) {
            PlayerControls(
                controller = controller,
                subtitles = subtitles,
                info = { LiveInfo(controller) },
                onChannels = if (controller.lineup.isEmpty) null else ({ controlsOpen = false; listOpen = true }),
                onHide = { controlsOpen = false },
            )
        }

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
private fun VodLayer(
    controller: LivePlayerController,
    subtitles: SubtitlesRepository,
    metadata: MetadataRepository,
    intros: IntroRepository,
    onClose: () -> Unit,
) {
    val colors = AppTheme.colors
    val item = controller.vod ?: return
    val focus = remember { FocusRequester() }
    var visible by remember { mutableStateOf(true) }
    var controlsOpen by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }

    BackHandler { onClose() }
    LaunchedEffect(controlsOpen) { if (!controlsOpen) runCatching { focus.requestFocus() } }

    // Skip intro / recap and an early Up next, from TheIntroDB's timings.
    val segments by produceState(IntroSegments(), item.url) { value = intros.segments(item) }
    val skip = if (controlsOpen) null else IntroDb.offer(segments, position, duration)
    val nextTitle = controller.nextEpisodeTitle
    var upNextDismissed by remember(item.url) { mutableStateOf(false) }
    val creditsAt = IntroDb.creditsStart(segments, duration)
    val upNext = !controlsOpen && !upNextDismissed && nextTitle != null && creditsAt != null &&
        position >= creditsAt && controller.status == PlaybackStatus.Playing
    var upNextSeconds by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    LaunchedEffect(upNext) {
        if (!upNext) return@LaunchedEffect
        for (seconds in UP_NEXT_SECONDS downTo 1) {
            upNextSeconds = seconds
            delay(1_000)
        }
        controller.skipEpisode(+1)
    }
    // Back while Up next shows keeps watching the credits.
    BackHandler(enabled = upNext) { upNextDismissed = true }
    LaunchedEffect(controller.controlsToken) {
        visible = true
        delay(BANNER_MILLIS)
        visible = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = controller.mediaPosition()
            duration = controller.mediaDuration()
            playing = controller.player.isPlaying
            delay(500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onKeyEvent { event ->
                if (controlsOpen || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    // OK opens the controls (pause, skip, subtitles, audio, next episode).
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            controller.status == PlaybackStatus.Failed -> controller.retry()
                            controller.status is PlaybackStatus.UpNext || upNext -> controller.skipEpisode(+1)
                            skip != null -> controller.seekTo(skip.toMs)
                            else -> controlsOpen = true
                        }
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        controller.togglePause()
                        true
                    }
                    Key.MediaNext -> {
                        controller.skipEpisode(+1)
                        true
                    }
                    Key.MediaPrevious -> {
                        controller.skipEpisode(-1)
                        true
                    }
                    // Catch-up can only be asked for from a whole minute, so its quick skips are a minute.
                    Key.DirectionLeft, Key.MediaRewind -> {
                        controller.seekBy(if (item.catchup) -60_000L else -SKIP_MILLIS)
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        controller.seekBy(if (item.catchup) 60_000L else SKIP_MILLIS)
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.Info -> {
                        controlsOpen = true
                        true
                    }
                    else -> false
                }
            }
            .focusProperties { canFocus = !controlsOpen }
            .focusable(),
    ) {
        StatusMessage(status = controller.status, modifier = Modifier.align(Alignment.Center))
        if (skip != null && !upNext) {
            SkipPill(
                label = skip.label,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 110.dp),
            )
        }
        if (upNext && nextTitle != null) {
            UpNextCard(
                title = nextTitle,
                seconds = upNextSeconds,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 110.dp),
            )
        }
        if (controlsOpen) {
            val details by produceState<MetadataEntity?>(null, item.url) {
                if (item.catchup) return@produceState
                val episode = app.tvplayer.core.subtitles.OpenSubtitles.seasonEpisode(item.title)
                value = if (episode != null) {
                    metadata.lookup(item.title.substringBefore(" · "), ChannelEntity.KIND_SERIES)
                } else {
                    metadata.lookup(item.title, ChannelEntity.KIND_MOVIE)
                }
            }
            PlayerControls(
                controller = controller,
                subtitles = subtitles,
                info = { VodInfo(item.title, details) },
                onChannels = null,
                onHide = { controlsOpen = false },
            )
        }
        AnimatedVisibility(
            visible = !controlsOpen && (visible || !playing),
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
                        text = if (playing) "OK for controls · Left/Right skip ${if (item.catchup) "1 min" else "10 s"} · Back to stop" else "Paused · OK for controls",
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
        is PlaybackStatus.UpNext -> "Next: ${status.title} in ${status.seconds} s\nOK to play now · Back to stop"
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
                    // An empty group (no favourites yet) keeps the group list open so focus has somewhere to go.
                    if (controller.visibleChannels.isNotEmpty()) showGroups = false
                },
            )
        }

        Column(
            modifier = Modifier
                .width(330.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Label(text = groupLabel(controller.group), style = AppType.headline)
            Label(
                text = "${channels.size} channels · Left for groups · Right to add or remove a favourite",
                style = AppType.caption,
                color = colors.textDim,
            )
            if (channels.isEmpty() && controller.group == FAVOURITES_GROUP) {
                Label(
                    text = "No favourites yet. Press Right on a channel to add it.",
                    style = AppType.body,
                    color = colors.textDim,
                    maxLines = 2,
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        val opensGroups = !showGroups &&
                            event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionLeft
                        if (opensGroups) showGroups = true
                        opensGroups
                    },
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.key }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        controller = controller,
                        playing = channel.key == currentKey,
                        favourite = channel.key in controller.favourites,
                        onClick = { onPick(channel) },
                        modifier = Modifier
                            .onPreviewKeyEvent { event ->
                                val toggles = event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight
                                if (toggles) controller.toggleFavourite(channel)
                                toggles
                            }
                            .then(if (index == startIndex) Modifier.focusRequester(itemFocus) else Modifier),
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
    favourite: Boolean,
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
                Label(text = if (favourite) "★ ${channel.name}" else channel.name, style = AppType.title)
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
    val entries = remember(groups) { listOf<String?>(null, FAVOURITES_GROUP) + groups }
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
        Label(text = "${groups.size + 1} groups", style = AppType.caption, color = colors.textDim)
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
                        text = groupLabel(name),
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

private fun groupLabel(name: String?): String = when (name) {
    null -> "All channels"
    FAVOURITES_GROUP -> "Favourites"
    else -> name
}

private const val BANNER_MILLIS = 5_000L
private const val LOADING_DELAY_MILLIS = 600L
private const val SKIP_MILLIS = 10_000L

private const val UP_NEXT_SECONDS = 10

/** "Skip intro · OK": shown while an intro or recap plays; OK jumps past it. */
@Composable
private fun SkipPill(label: String, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.scrim)
            .border(2.dp, colors.accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(text = label, style = AppType.title)
        Label(text = "OK", style = AppType.label, color = colors.accent)
    }
}

/** The next episode, counting down over the credits. OK plays it now; Back keeps watching. */
@Composable
private fun UpNextCard(title: String, seconds: Int, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(
        modifier = modifier
            .width(380.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.scrim)
            .border(2.dp, colors.accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label(text = "UP NEXT", style = AppType.caption, color = colors.accent)
        Label(text = title, style = AppType.title, maxLines = 2)
        Label(text = "Plays in $seconds s  ·  OK to play now  ·  Back to keep watching", style = AppType.label, color = colors.textDim)
    }
}
