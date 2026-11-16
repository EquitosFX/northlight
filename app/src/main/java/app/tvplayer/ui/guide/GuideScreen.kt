package app.tvplayer.ui.guide

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay
import app.tvplayer.ui.components.LocalHoldFocus
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.tvplayer.core.catchup.Catchup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import app.tvplayer.ui.components.entersAt
import app.tvplayer.ui.components.EntryFocus
import app.tvplayer.ui.components.EntryPoint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tvplayer.core.live.Lineup
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.db.ProgrammeEntity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.components.clockTime
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val HOUR = 60 * MINUTE

/** How much time the grid shows at once. */
private const val WINDOW = 2 * HOUR

private val ChannelColumn = 214.dp
private val RowHeight = 46.dp
private const val DAY = 24 * HOUR

/** A little larger than the app's standard sizes, so the guide reads easily from the sofa. */
private val GuideChannelText = AppType.title.copy(fontSize = 15.sp)
private val GuideCellText = AppType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium)
private val GuideTitleText = AppType.headline.copy(fontSize = 26.sp, lineHeight = 30.sp)
private val GuideMetaText = AppType.label.copy(fontSize = 14.sp, lineHeight = 18.sp)
private val GuideBodyText = AppType.body.copy(fontSize = 15.sp, lineHeight = 21.sp)
private val SideMargin = 14.dp
private const val FAVOURITES = "\u0000favourites"

/** The programme the viewer is on, for the header. A null programme means the channel has no guide data. */
private data class Focused(val channel: LineupChannel, val programme: ProgrammeEntity?)

/** Up or Down in the grid: the row to move to, and the time whose programme to land on there. */
private data class Jump(val row: Int, val time: Long)

/**
 * The TV guide: an information header for the focused programme, then channels down the side and
 * programmes laid across by time. Moving past either edge slides the grid an hour.
 * Selecting a programme that is on now, or a channel with no guide data, plays that channel.
 */
@Composable
fun GuideScreen(
    channels: ChannelsRepository,
    onPlay: (LineupChannel) -> Unit,
    /** Plays a past programme again, on a channel whose provider keeps catch-up. */
    onCatchup: (LineupChannel, ProgrammeEntity) -> Unit,
    /** Ids ("channelKey|start") of programmes with a reminder set. */
    reminders: Set<String> = emptySet(),
    /** Sets or cancels a reminder for a programme still to come. */
    onRemind: (LineupChannel, ProgrammeEntity) -> Unit = { _, _ -> },
    /** Channels that failed the last channel check (Settings › Refresh and check). */
    notWorking: Set<String> = emptySet(),
) {
    val colors = AppTheme.colors
    val lineup by produceState<Lineup?>(initialValue = null) { value = channels.lineup() }
    val now = remember { System.currentTimeMillis() }
    val liveStart = remember { now - now % HALF_HOUR }
    // The guide goes back as far as the longest catch-up any channel has.
    val earliest = remember(lineup) {
        val days = lineup?.channels?.maxOfOrNull { it.catchupDays ?: 0 } ?: 0
        val from = now - days * DAY
        from - from % HALF_HOUR
    }
    var windowStart by remember { mutableLongStateOf(liveStart) }
    // Read only by the header (and when sliding the grid), so moving around the grid doesn't redraw every row.
    val focusedState = remember { mutableStateOf<Focused?>(null) }
    val firstFocus = remember { FocusRequester() }
    val entryFocus = remember { FocusRequester() }
    var placedFocus by remember { mutableStateOf(false) }
    // The category list on the left: Favourites, All channels, then the playlist's groups.
    var group by remember { mutableStateOf<String?>(null) }
    val favourites by channels.favouriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())

    val current = lineup
    if (current == null || current.isEmpty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label(
                text = if (current == null) "Loading the guide…" else "No live channels yet. Add a playlist in Settings › Playlists.",
                style = AppType.headline,
                color = colors.textDim,
            )
        }
        return
    }

    // Sliding the grid rebuilds its cells, so focus is parked while it happens and put back on the
    // programme that had it once the new hours have loaded.
    val holdFocus = LocalHoldFocus.current
    var refocus by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(refocus) {
        // If the programme can't be found again, focus goes to the first channel rather than staying parked.
        if (refocus == null) return@LaunchedEffect
        delay(2_000)
        refocus = null
        runCatching { entryFocus.requestFocus() }
    }

    // Moving along by an hour slides the grid smoothly rather than jumping. The grid is laid out for
    // where it's going, then drawn shifted back to where it was and slid into place: only the drawing
    // moves, so it stays smooth on slower boxes.
    val slideMillis = remember { floatArrayOf(0f) }
    val slideTick = remember { mutableFloatStateOf(0f) }
    var sliding by remember { mutableStateOf(false) }
    val slideScope = rememberCoroutineScope()
    var slideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun moveWindow(target: Long) {
        val delta = windowStart - target
        if (delta == 0L) return
        slideJob?.cancel()
        if (kotlin.math.abs(delta) <= WINDOW) {
            val from = slideMillis[0] + delta
            slideMillis[0] = from
            slideTick.floatValue = from
            sliding = true
            slideJob = slideScope.launch {
                androidx.compose.animation.core.animate(from, 0f, animationSpec = tween<Float>(SLIDE_MILLIS, easing = FastOutSlowInEasing)) { value, _ ->
                    slideMillis[0] = value
                    slideTick.floatValue = value
                }
                sliding = false
            }
        } else {
            slideMillis[0] = 0f
            slideTick.floatValue = 0f
            sliding = false
        }
        windowStart = target
    }
    // Read only while drawing, so the slide doesn't rebuild anything.
    val slide: () -> Float = { slideTick.floatValue }

    fun shift(by: Long) {
        val target = (windowStart + by).coerceIn(earliest, liveStart + 7 * 24 * HOUR - WINDOW)
        if (target == windowStart) return
        // Focus waits to one side during the slide and goes back to the same programme once it's settled.
        refocus = focusedState.value?.programme?.id
        if (refocus != null) holdFocus()
        moveWindow(target)
    }

    // Once a category is chosen the categories step aside and the listings fill the screen.
    // Back brings them back with the chosen one highlighted; Back again goes to the side menu.
    var listings by remember { mutableStateOf(false) }
    var backToCategories by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
    // Kept here, so the category list is still scrolled to the chosen category when it comes back.
    val categoryList = rememberLazyListState()
    BackHandler(enabled = listings) {
        listings = false
        backToCategories = true
    }
    LaunchedEffect(backToCategories) {
        if (!backToCategories) return@LaunchedEffect
        withFrameNanos { }
        runCatching { categoryFocus.requestFocus() }
        backToCategories = false
    }

    val rows = remember(current, group, favourites) {
        when (group) {
            null -> current.channels
            FAVOURITES -> current.channels.filter { it.key in favourites }
            else -> current.inGroup(group)
        }
    }
    // Holding Up or Down (or pressing very fast) moves at a pace the TV box can keep up with,
    // instead of piling up moves until it freezes.
    val lastMove = remember { longArrayOf(0L) }
    // Up and Down keep to one moment in time: what's on now (or, after moving along a row, the start
    // of the programme moved to), so a short programme never pulls the highlight later and later.
    val anchor = remember { longArrayOf(0L) }
    val focusedRow = remember { intArrayOf(-1) }
    val movingVertically = remember { booleanArrayOf(false) }
    val jumpState = remember { mutableStateOf<Jump?>(null) }
    val rowsList = remember(group) { LazyListState() }
    val gridScope = rememberCoroutineScope()

    // Back, after moving to another time or day: back to now, on the same channel's programme on now.
    BackHandler(enabled = listings && windowStart != liveStart && focusedRow[0] >= 0) {
        val row = focusedRow[0]
        val nowAgain = System.currentTimeMillis()
        refocus = null
        holdFocus()
        anchor[0] = nowAgain
        movingVertically[0] = true
        jumpState.value = Jump(row, nowAgain)
        moveWindow(liveStart)
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 34.dp)
            // Once anything in the guide has focus, it never jumps to the first programme by itself.
            // Leaving the guide altogether (to the side menu) brings the categories back for next time.
            .onFocusChanged {
                if (it.hasFocus) placedFocus = true else if (refocus == null) listings = false
            }
            .focusGroup(),
    ) {
    if (!listings) {
        Categories(
            groups = current.groups,
            selected = group,
            onSelect = { group = it },
            // OK on a category moves into its listings (focus first, so it never falls out of the guide).
            onOpen = { if (runCatching { entryFocus.requestFocus() }.isSuccess) listings = true },
            currentFocus = categoryFocus,
            listState = categoryList,
            modifier = Modifier.width(150.dp).fillMaxHeight(),
        )
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .onFocusChanged { if (it.hasFocus) listings = true },
    ) {
        Header(focusedState = focusedState, reminders = reminders, modifier = Modifier.padding(horizontal = SideMargin))
        DayBar(
            earliest = earliest,
            now = now,
            windowStart = windowStart,
            onDay = { dayStart ->
                // Same time of day on the chosen day (now, for today), within what the guide holds.
                val zone = java.time.ZoneId.systemDefault()
                val timeOfDay = java.time.Instant.ofEpochMilli(liveStart).atZone(zone).toLocalTime()
                val target = if (sameDay(dayStart, now)) {
                    liveStart
                } else {
                    java.time.Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate().atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                }
                moveWindow(target.coerceIn(earliest, liveStart + 7 * 24 * HOUR - WINDOW))
            },
        )
        TimeRuler(windowStart = windowStart, now = now, slide = slide, sliding = sliding)
        if (rows.isEmpty()) {
            Label(
                text = if (group == FAVOURITES) "No favourites yet. In live TV, press OK for the channel list, then Right on a channel." else "No channels in this group.",
                style = AppType.body,
                color = colors.textDim,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = SideMargin, vertical = 12.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = SideMargin, end = 40.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                    val time = android.os.SystemClock.uptimeMillis()
                    // Held down: a steady pace. Pressed quickly: one move at a time, each landing before the next.
                    if (event.nativeKeyEvent.repeatCount > 0 && time - lastMove[0] < HELD_MOVE_MILLIS) return@onPreviewKeyEvent true
                    if (jumpState.value != null && time - lastMove[0] < JUMP_TIMEOUT_MILLIS) return@onPreviewKeyEvent true
                    lastMove[0] = time
                    val from = focusedRow[0]
                    val to = from + if (event.key == Key.DirectionDown) 1 else -1
                    // Off the top: the day bar above, as usual.
                    if (from < 0 || to !in rows.indices) return@onPreviewKeyEvent false
                    movingVertically[0] = true
                    jumpState.value = Jump(to, anchor[0])
                    // A row just off screen is scrolled in first; on screen, landing on it brings it fully into view.
                    if (rowsList.layoutInfo.visibleItemsInfo.none { it.index == to }) {
                        gridScope.launch {
                            if (to > from) rowsList.scrollToItem(rowsList.firstVisibleItemIndex + 1) else rowsList.scrollToItem(to)
                        }
                    }
                    true
                },
            state = rowsList,
        ) {
            itemsIndexed(rows, key = { _, channel -> channel.key }) { index, channel ->
                GuideRow(
                    channel = channel,
                    channels = channels,
                    windowStart = windowStart,
                    now = now,
                    canGoBack = windowStart > earliest,
                    // The guide opens on the categories; the listings never take focus by themselves.
                    focusRequester = null,
                    entry = if (index == 0) entryFocus else null,
                    onFocusPlaced = { placedFocus = true },
                    onFocus = { programme ->
                        focusedState.value = Focused(channel, programme)
                        focusedRow[0] = index
                        // Moving along a row (not up or down) sets the moment Up and Down keep to.
                        if (!movingVertically[0]) {
                            val now2 = System.currentTimeMillis()
                            anchor[0] = when {
                                programme == null -> maxOf(windowStart, minOf(now2, windowStart + WINDOW - MINUTE))
                                now2 in programme.start until programme.stop -> now2
                                else -> maxOf(programme.start, windowStart)
                            }
                        }
                        movingVertically[0] = false
                    },
                    jumpState = jumpState,
                    index = index,
                    slide = slide,
                    sliding = sliding,
                    onShift = ::shift,
                    refocus = refocus,
                    onRefocused = { refocus = null },
                    onPlay = { onPlay(channel) },
                    onCatchup = { programme -> onCatchup(channel, programme) },
                    reminders = reminders,
                    onRemind = { programme -> onRemind(channel, programme) },
                    notWorking = channel.key in notWorking,
                )
            }
        }
    }
    }
}

/** Favourites, All channels and the playlist's groups. Moving through them changes the channels shown. */
@Composable
private fun Categories(
    groups: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOpen: () -> Unit,
    currentFocus: FocusRequester,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val entries = remember(groups) { listOf<String?>(FAVOURITES, null) + groups }
    // Coming back to the categories lands on the chosen one.
    val entry = remember { EntryFocus() }
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) {
        // Make sure the chosen category is on screen, so there's something to land on.
        val index = entries.indexOf(selected).coerceAtLeast(0)
        withFrameNanos { }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.scrollToItem((index - 3).coerceAtLeast(0))
            withFrameNanos { }
        }
        if (autoFocus()) runCatching { currentFocus.requestFocus() }
    }
    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text = "TV guide", style = AppType.headline, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f).entersAt(entry),
        ) {
            items(entries.size) { index ->
                val name = entries[index]
                val current = name == selected
                if (current) {
                    EntryPoint(currentFocus, entry)
                    // The guide opens here: moving in from the menu, or arriving from a button, lands on the chosen category.
                    EntryPoint(currentFocus)
                }
                FocusSurface(
                    onClick = {
                        onSelect(name)
                        onOpen()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .then(if (current) Modifier.focusRequester(currentFocus) else Modifier),
                    shape = RoundedCornerShape(3.dp),
                    container = if (current) colors.glass else Color.Transparent,
                    focusedContainer = colors.glassStrong,
                    outlined = false,
                    focusScale = 1f,
                ) { focused ->
                    LaunchedEffect(focused) { if (focused) onSelect(name) }
                    Label(
                        text = when (name) {
                            null -> "All channels"
                            FAVOURITES -> "Favourites"
                            else -> name
                        },
                        style = if (focused || current) GuideChannelText.copy(fontSize = 16.sp) else AppType.body.copy(fontSize = 16.sp),
                        color = if (focused || current) colors.text else colors.textDim,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(focusedState: androidx.compose.runtime.State<Focused?>, reminders: Set<String>, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val focused = focusedState.value
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(124.dp)
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (focused == null) return@Column
        val channel = focused.channel
        val programme = focused.programme
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(url = channel.logo, modifier = Modifier.size(width = 38.dp, height = 26.dp))
            Label(text = "${channel.number} · ${channel.name}", style = AppType.title, color = colors.textDim)
        }
        if (programme == null) {
            Label(text = "No programme information", style = GuideTitleText)
            Label(text = "Press OK to watch this channel.", style = GuideBodyText, color = colors.textDim)
            return@Column
        }
        Label(text = programme.title, style = GuideTitleText, maxLines = 1)
        val episode = if (programme.season != null && programme.episode != null) {
            "S${programme.season} E${programme.episode}"
        } else {
            null
        }
        val meta = listOfNotNull(
            "${clockTime(programme.start)}–${clockTime(programme.stop)}",
            "${(programme.stop - programme.start) / MINUTE} min",
            episode,
            programme.subTitle,
            programme.category,
        ).joinToString(" · ")
        val now = System.currentTimeMillis()
        val hint = when {
            now in programme.start until programme.stop -> "On now · OK to watch"
            programme.stop <= now && Catchup.available(channel.catchupDays, programme.start, programme.stop, now) ->
                "Catch-up · OK to watch from the start"
            programme.stop <= now -> "Catch-up isn't available on this channel"
            "${channel.key}|${programme.start}" in reminders -> "Reminder set · OK to cancel"
            else -> "OK to set a reminder"
        }
        Label(text = listOfNotNull(meta, hint).joinToString("  ·  "), style = GuideMetaText, color = colors.accent, maxLines = 1)
        val description = programme.description
        if (description != null) {
            Label(text = description, style = GuideBodyText, color = colors.textDim, maxLines = 2)
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, now: Long, slide: () -> Float = { 0f }, sliding: Boolean = false) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .padding(horizontal = SideMargin),
    ) {
        Box(modifier = Modifier.width(ChannelColumn + 4.dp)) {
            Label(
                text = if (sameDay(windowStart, now)) "Today" else dayLabel(windowStart),
                style = AppType.label,
                color = colors.textDim,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val width = maxWidth
            val widthPx = constraints.maxWidth.toFloat()
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = -(slide() / WINDOW) * widthPx }) {
            // While sliding, the times either side are drawn too, coming into view.
            val steps = if (sliding) (-(WINDOW / HALF_HOUR).toInt() until (2 * WINDOW / HALF_HOUR).toInt()) else (0 until (WINDOW / HALF_HOUR).toInt())
            for (step in steps) {
                val at = windowStart + step * HALF_HOUR
                Label(
                    text = clockTime(at),
                    style = AppType.label,
                    color = colors.textDim,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = position(at - windowStart, width)),
                )
            }
            if (now in windowStart - (if (sliding) WINDOW else 0L) until windowStart + (if (sliding) 2 * WINDOW else WINDOW)) {
                Box(
                    modifier = Modifier
                        .offset(x = position(now - windowStart, width))
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(colors.accent),
                )
            }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LineupChannel,
    channels: ChannelsRepository,
    windowStart: Long,
    now: Long,
    canGoBack: Boolean,
    focusRequester: FocusRequester?,
    entry: FocusRequester?,
    onFocusPlaced: () -> Unit,
    onFocus: (ProgrammeEntity?) -> Unit,
    onShift: (Long) -> Unit,
    refocus: Long?,
    onRefocused: () -> Unit,
    onPlay: () -> Unit,
    onCatchup: (ProgrammeEntity) -> Unit,
    reminders: Set<String>,
    onRemind: (ProgrammeEntity) -> Unit,
    notWorking: Boolean = false,
    jumpState: androidx.compose.runtime.MutableState<Jump?>? = null,
    index: Int = -1,
    slide: () -> Float = { 0f },
    sliding: Boolean = false,
) {
    val colors = AppTheme.colors
    val windowEnd = windowStart + WINDOW
    // Only the row being moved to notices the move.
    val jumpTime by remember(jumpState, index) { derivedStateOf { jumpState?.value?.takeIf { it.row == index }?.time } }
    val onJumped: () -> Unit = { if (jumpState?.value?.row == index) jumpState.value = null }
    // Keeps showing the previous programmes while new ones load, so the grid doesn't flash.
    var programmes by remember { mutableStateOf<List<ProgrammeEntity>?>(null) }
    var loadedFor by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(channel.key, windowStart) {
        // An extra window either side, ready to slide into view.
        programmes = channels.programmes(channel, windowStart - WINDOW, windowEnd + WINDOW)
        loadedFor = windowStart
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .width(ChannelColumn)
                .fillMaxHeight()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.glass)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(
                text = channel.number.toString(),
                style = AppType.label,
                color = colors.textDim,
                modifier = Modifier.width(32.dp),
            )
            Logo(url = channel.logo, modifier = Modifier.size(width = 38.dp, height = 28.dp))
            Label(text = channel.name, style = GuideChannelText)
            // Didn't respond in the last channel check.
            if (notWorking) Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFFFF5A5F)))
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            val width = maxWidth
            val widthPx = constraints.maxWidth.toFloat()
            // Slides with the rest of the grid when moving along by an hour.
            SlideLayer(slide, widthPx) {
            // Settled: the programmes in view, the first and last trimmed to the edges so their names show.
            // Sliding: those either side too, at their full lengths, coming into or going out of view.
            val shownFrom = if (sliding) windowStart - WINDOW else windowStart
            val shownTo = if (sliding) windowEnd + WINDOW else windowEnd
            val list = programmes?.filter { it.stop > shownFrom && it.start < shownTo } ?: return@SlideLayer
            val inViewList = list.filter { it.stop > windowStart && it.start < windowEnd }

            // Edge keys slide the grid instead of leaving it.
            fun edgeKeys(start: Long, stop: Long) = Modifier.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionRight && stop >= windowEnd -> {
                        onShift(HOUR)
                        true
                    }
                    event.key == Key.DirectionLeft && start <= windowStart && canGoBack -> {
                        onShift(-HOUR)
                        true
                    }
                    else -> false
                }
            }

            if (inViewList.isEmpty()) {
                GuideCell(
                    title = "No programme information",
                    x = 0.dp,
                    width = width,
                    past = false,
                    live = false,
                    catchup = false,
                    onFocus = { onFocus(null) },
                    onClick = onPlay,
                    refocus = jumpTime != null && !sliding,
                    onRefocused = onJumped,
                    modifier = edgeKeys(windowStart, windowEnd)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (entry != null) Modifier.focusRequester(entry) else Modifier),
                )
                if (focusRequester != null) FocusOnce(focusRequester, onFocusPlaced)
                return@SlideLayer
            }

            val focusTarget = inViewList.firstOrNull { now in it.start until it.stop } ?: inViewList.firstOrNull() ?: list.first()
            // Up or Down landing here: the programme on at the moment being kept to, or the nearest after it.
            val jumpId = jumpTime?.let { time ->
                (inViewList.firstOrNull { time >= it.start && time < it.stop }
                    ?: inViewList.firstOrNull { it.stop > time }
                    ?: inViewList.lastOrNull() ?: list.last()).id
            }
            for (programme in list) {
                val from = maxOf(programme.start, shownFrom)
                val to = minOf(programme.stop, shownTo)
                if (to - from < MINUTE) continue
                // Only what's in view once the slide ends can be highlighted.
                val inView = programme.stop > windowStart && programme.start < windowEnd
                val live = now in programme.start until programme.stop
                val catchup = !live && Catchup.available(channel.catchupDays, programme.start, programme.stop, now)
                key(programme.id) {
                    GuideCell(
                        title = programme.title,
                        x = position(from - windowStart, width),
                        width = position(to - from, width) - 3.dp,
                        past = programme.stop <= now,
                        live = live,
                        catchup = catchup,
                        reminder = "${channel.key}|${programme.start}" in reminders,
                        onFocus = { onFocus(programme) },
                        refocus = !sliding && ((programme.id == refocus && loadedFor == windowStart) || (jumpId != null && programme.id == jumpId && loadedFor == windowStart)),
                        onRefocused = if (jumpId != null && programme.id == jumpId) onJumped else onRefocused,
                        onClick = {
                            when {
                                live -> onPlay()
                                catchup -> onCatchup(programme)
                                programme.start > now -> onRemind(programme)
                            }
                        },
                        modifier = edgeKeys(programme.start, programme.stop).focusProperties { canFocus = inView }.then(
                            if (focusRequester != null && programme.id == focusTarget.id) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        ).then(
                            // The first channel's current programme is where Right from the menu lands.
                            if (entry != null && programme.id == focusTarget.id) Modifier.focusRequester(entry) else Modifier,
                        ),
                    )
                }
            }
            if (focusRequester != null) FocusOnce(focusRequester, onFocusPlaced)
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    x: Dp,
    width: Dp,
    past: Boolean,
    live: Boolean,
    catchup: Boolean,
    reminder: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    refocus: Boolean = false,
    onRefocused: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val requester = remember { FocusRequester() }
    if (refocus) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            onRefocused()
        }
    }
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .focusRequester(requester)
            .offset(x = x)
            .width(width.coerceAtLeast(4.dp))
            .fillMaxHeight(),
        shape = RoundedCornerShape(6.dp),
        container = if (live) colors.glassStrong else colors.glass,
        focusedContainer = colors.accent,
        outlined = false,
        focusScale = 1f,
    ) { focused ->
        LaunchedEffect(focused) { if (focused) onFocus() }
        val tint = when {
            focused -> colors.onAccent
            past && !catchup -> colors.textFaint
            else -> colors.text
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Past programmes that can be watched again carry a replay mark.
            if (catchup) ReplayMark(tint = if (focused) colors.onAccent else colors.accent, modifier = Modifier.size(13.dp))
            if (reminder) BellMark(tint = if (focused) colors.onAccent else colors.accent, modifier = Modifier.size(13.dp))
            Label(text = title, style = GuideCellText, color = tint)
        }
    }
}

/** Puts focus on the first row's current programme once, when the guide opens. */
@Composable
private fun FocusOnce(requester: FocusRequester, onPlaced: () -> Unit) {
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(requester) {
        withFrameNanos { }
        if (autoFocus() && runCatching { requester.requestFocus() }.isSuccess) onPlaced()
    }
}

private fun position(offsetMillis: Long, width: Dp): Dp = width * (offsetMillis.toFloat() / WINDOW)

private fun sameDay(a: Long, b: Long): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}

/** A small circular arrow: "watch again". */
@Composable
private fun ReplayMark(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.width * 0.16f
        drawArc(
            color = tint,
            startAngle = -60f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * stroke, size.height - 2 * stroke),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        val head = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.62f, 0f)
            lineTo(size.width * 0.98f, size.height * 0.12f)
            lineTo(size.width * 0.72f, size.height * 0.4f)
            close()
        }
        drawPath(head, tint)
    }
}

private fun dayLabel(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))

/** A small bell: a reminder is set. */
@Composable
private fun BellMark(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        val bell = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.08f)
            cubicTo(w * 0.82f, h * 0.08f, w * 0.82f, h * 0.45f, w * 0.84f, h * 0.62f)
            lineTo(w * 0.96f, h * 0.78f)
            lineTo(w * 0.04f, h * 0.78f)
            lineTo(w * 0.16f, h * 0.62f)
            cubicTo(w * 0.18f, h * 0.45f, w * 0.18f, h * 0.08f, w * 0.5f, h * 0.08f)
            close()
        }
        drawPath(bell, tint)
        drawCircle(tint, radius = w * 0.11f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.88f))
    }
}

/**
 * The days the guide covers, from the earliest catch-up day to a week ahead: OK on one jumps there.
 * Up from the top channel reaches it.
 */
@Composable
private fun DayBar(earliest: Long, now: Long, windowStart: Long, onDay: (Long) -> Unit) {
    val colors = AppTheme.colors
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val first = java.time.Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today.plusDays(6)) }.toList()
    val shown = java.time.Instant.ofEpochMilli(windowStart).atZone(zone).toLocalDate()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(days.size) {
        val index = days.indexOf(today)
        if (index > 2) listState.scrollToItem(index - 2)
    }
    // Coming up from the listings lands on the day being shown, not the nearest chip.
    val entry = remember { EntryFocus() }
    val shownFocus = remember { FocusRequester() }
    androidx.compose.foundation.lazy.LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = SideMargin),
        modifier = Modifier
            .entersAt(entry)
            .fillMaxWidth()
            .height(34.dp)
            .padding(vertical = 3.dp),
    ) {
        items(days.size) { i ->
            val day = days[i]
            val label = when (day) {
                today -> "Today"
                today.minusDays(1) -> "Yesterday"
                today.plusDays(1) -> "Tomorrow"
                else -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d", java.util.Locale.UK))
            }
            val selected = day == shown
            if (selected) EntryPoint(shownFocus, entry)
            FocusSurface(
                onClick = { onDay(day.atStartOfDay(zone).toInstant().toEpochMilli()) },
                modifier = Modifier.fillMaxHeight().then(if (selected) Modifier.focusRequester(shownFocus) else Modifier),
                shape = RoundedCornerShape(14.dp),
                container = if (selected) colors.glassStrong else Color.Transparent,
                focusedContainer = colors.accent,
                outlined = false,
            ) { focused ->
                Label(
                    text = label,
                    style = AppType.label.copy(fontSize = 13.sp),
                    color = when {
                        focused -> colors.onAccent
                        selected -> colors.text
                        else -> colors.textDim
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 12.dp),
                )
            }
        }
    }
}

/** How long the grid takes to slide along an hour. */
private const val SLIDE_MILLIS = 320

/** The quickest the guide moves between channels with the button held down. */
private const val HELD_MOVE_MILLIS = 120L

/** How long a move up or down may take to land before another press is taken anyway. */
private const val JUMP_TIMEOUT_MILLIS = 600L

/**
 * Draws its content shifted along by the slide in progress. (A function of its own, not an inline
 * Box, so the early returns inside the row don't upset Compose's bookkeeping and rebuild every cell.)
 */
@Composable
private fun SlideLayer(slide: () -> Float, widthPx: Float, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = -(slide() / WINDOW) * widthPx }) { content() }
}
