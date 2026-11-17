package app.tvplayer.ui.guide

import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.platform.testTag
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.Layout
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
    val now = remember { System.currentTimeMillis() }
    val liveStart = remember { now - now % HALF_HOUR }
    // Programmes already read for this visit, so a row that comes back (or a category changed to)
    // shows them straight away instead of drawing empty and filling in a moment later.
    val programmeCache = remember { ProgrammeCache() }
    val lineup by produceState<Lineup?>(initialValue = null) {
        val loaded = channels.lineup()
        // The first screenful's programmes are read before the guide shows, so it opens complete.
        programmeCache.prefetch(channels, loaded.channels.take(PREFETCH_ROWS), originFor(liveStart))
        value = loaded
    }
    // The guide goes back as far as the longest catch-up any channel has.
    val earliest = remember(lineup) {
        val days = lineup?.channels?.maxOfOrNull { it.catchupDays ?: 0 } ?: 0
        val from = now - days * DAY
        from - from % HALF_HOUR
    }
    var windowStart by windowState
    // Read lazily by the rows, so moving the window doesn't rebuild them.
    val window: () -> Long = remember { { windowState.longValue } }
    // Read only by the header (and when sliding the grid), so moving around the grid doesn't redraw every row.
    val focusedState = remember { mutableStateOf<Focused?>(null) }
    val firstFocus = remember { FocusRequester() }
    val entryFocus = remember { FocusRequester() }
    var placedFocus by remember { mutableStateOf(false) }
    // The category list on the left: Favourites, All channels, then the playlist's groups.
    var group by remember { mutableStateOf<String?>(null) }
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    var openAfterSwitch by remember { mutableIntStateOf(0) }
    // OK on a category the listings haven't changed to yet: change now (no pause) and go in.
    var openRequested by remember { mutableStateOf(false) }
    val favourites by channels.favouriteKeys.collectAsStateWithLifecycle(initialValue = emptySet())
    val windowState = remember { mutableLongStateOf(liveStart) }
    LaunchedEffect(pendingGroup, openRequested) {
        val target = pendingGroup
        if (target != group) {
            val loaded = lineup ?: return@LaunchedEffect
            // The new category's first screenful of programmes is read while the highlight rests,
            // and the listings change in one go once it's ready: the old ones stay until then.
            coroutineScope {
                val ready = async {
                    programmeCache.prefetch(channels, channelsIn(loaded, target, favourites).take(PREFETCH_ROWS), originFor(windowState.longValue))
                }
                if (!openRequested) delay(CATEGORY_SETTLE_MILLIS)
                ready.await()
            }
            group = target
        }
        if (openRequested) {
            openRequested = false
            openAfterSwitch++
        }
    }

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

    val holdFocus = LocalHoldFocus.current

    // Moving along by an hour slides the grid smoothly. The programmes for several hours either side
    // are already laid out, so only the drawing moves: nothing is rebuilt or loaded.
    val slideMillis = remember { floatArrayOf(0f) }
    val slideTick = remember { mutableFloatStateOf(0f) }
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
            slideJob = slideScope.launch {
                androidx.compose.animation.core.animate(from, 0f, animationSpec = tween<Float>(SLIDE_MILLIS, easing = FastOutSlowInEasing)) { value, _ ->
                    slideMillis[0] = value
                    slideTick.floatValue = value
                }
            }
        } else {
            slideMillis[0] = 0f
            slideTick.floatValue = 0f
        }
        windowStart = target
    }
    // Read only while drawing, so the slide doesn't rebuild anything.
    val slide: () -> Float = remember { { slideTick.floatValue } }
    // The hours the rows have laid out: moves on only every few hours of moving along.
    val origin = originFor(windowStart)

    // The highlighted programme stays where it is (it's always still in view after an hour's move).
    fun shift(by: Long) {
        val target = (windowStart + by).coerceIn(earliest, liveStart + 7 * 24 * HOUR - WINDOW)
        if (target == windowStart) return
        moveWindow(target)
    }
    val latestShift by androidx.compose.runtime.rememberUpdatedState<(Long) -> Unit>(::shift)
    val onShift: (Long) -> Unit = remember { { by -> latestShift(by) } }

    // Once a category is chosen the categories step aside and the listings fill the screen.
    // Back brings them back with the chosen one highlighted; Back again goes to the side menu.
    var listings by remember { mutableStateOf(false) }
    // OK on a category the channels haven't caught up with yet: into its listings once they're there.
    LaunchedEffect(openAfterSwitch) {
        if (openAfterSwitch == 0) return@LaunchedEffect
        // Up to a second for the first channel's programmes to load.
        repeat(60) {
            withFrameNanos { }
            if (runCatching { entryFocus.requestFocus() }.isSuccess) {
                listings = true
                return@LaunchedEffect
            }
        }
    }
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

    val rows = remember(current, group, favourites) { channelsIn(current, group, favourites) }
    // Holding Up or Down (or pressing very fast) moves at a pace the TV box can keep up with,
    // instead of piling up moves until it freezes.
    val lastMove = remember { longArrayOf(0L) }
    // Up and Down keep to one moment in time: what's on now (or, after moving along a row, the start
    // of the programme moved to), so a short programme never pulls the highlight later and later.
    val anchor = remember { longArrayOf(0L) }
    val focusedRow = remember { intArrayOf(-1) }
    val movingVertically = remember { booleanArrayOf(false) }
    val jumpState = remember { mutableStateOf<Jump?>(null) }
    // Focus parked while Back takes the grid back to now; the listings stay open meanwhile.
    var holdingForJump by remember { mutableStateOf(false) }
    LaunchedEffect(jumpState.value) {
        val pending = jumpState.value
        if (pending == null) {
            holdingForJump = false
            return@LaunchedEffect
        }
        // A move that can't land (that row has nothing to land on) is let go of, and focus never
        // stays parked: it goes to the first channel.
        delay(JUMP_GIVE_UP_MILLIS)
        if (jumpState.value === pending) {
            jumpState.value = null
            if (holdingForJump) runCatching { entryFocus.requestFocus() }
        }
    }
    val rowsList = remember(group) { LazyListState() }
    val gridScope = rememberCoroutineScope()

    // Back, after moving to another time or day: back to now, on the same channel's programme on now.
    BackHandler(enabled = listings && windowStart != liveStart && focusedRow[0] >= 0) {
        val row = focusedRow[0]
        val nowAgain = System.currentTimeMillis()
        holdingForJump = true
        holdFocus()
        anchor[0] = nowAgain
        movingVertically[0] = true
        jumpState.value = Jump(row, nowAgain)
        moveWindow(liveStart)
    }

    // The listings are always laid out at their full width; with the categories showing they're just
    // drawn further right. Showing or hiding the categories slides the two along together, so
    // nothing is measured or laid out again (that was the jolt going in and out of the listings).
    val categoriesShown = remember { Animatable(if (listings) 0f else 1f) }
    LaunchedEffect(listings) {
        categoriesShown.animateTo(if (listings) 0f else 1f, tween(PANEL_MILLIS, easing = FastOutSlowInEasing))
    }
    val keepCategories by remember { derivedStateOf { !listings || categoriesShown.value > 0f } }
    val categoryWidth = 150.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 34.dp)
            // Once anything in the guide has focus, it never jumps to the first programme by itself.
            // Leaving the guide altogether (to the side menu) brings the categories back for next time.
            .onFocusChanged {
                if (it.hasFocus) placedFocus = true else if (!holdingForJump) listings = false
            }
            .focusGroup(),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = categoriesShown.value * categoryWidth.toPx() }
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
        TimeRuler(windowStart = windowStart, origin = origin, now = now, window = window, slide = slide)
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
                    window = window,
                    slide = slide,
                    origin = origin,
                    now = now,
                    earliest = earliest,
                    entry = if (index == 0) entryFocus else null,
                    onFocus = { programme ->
                        focusedState.value = Focused(channel, programme)
                        focusedRow[0] = index
                        // Moving along a row (not up or down) sets the moment Up and Down keep to.
                        if (!movingVertically[0]) {
                            val now2 = System.currentTimeMillis()
                            val windowStart = window()
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
                    onShift = onShift,
                    onPlay = { onPlay(channel) },
                    onCatchup = { programme -> onCatchup(channel, programme) },
                    reminders = reminders,
                    onRemind = { programme -> onRemind(channel, programme) },
                    notWorking = channel.key in notWorking,
                    cache = programmeCache,
                )
            }
        }
    }
    // Drawn over the listings' left edge, sliding off to the left as they slide in. While sliding
    // away they can't be highlighted, so Left from the listings still goes to the side menu.
    if (keepCategories) {
        Categories(
            groups = current.groups,
            selected = group,
            // Moving quickly through the categories doesn't build every one's listings on the way:
            // the channels change once the highlight has paused on a category.
            onSelect = { pendingGroup = it },
            // OK on a category moves into its listings (focus first, so it never falls out of the guide).
            onOpen = {
                if (pendingGroup != group) {
                    openRequested = true
                } else if (runCatching { entryFocus.requestFocus() }.isSuccess) {
                    listings = true
                }
            },
            currentFocus = categoryFocus,
            listState = categoryList,
            interactive = !listings,
            modifier = Modifier
                .width(categoryWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    val shown = categoriesShown.value
                    translationX = -(1f - shown) * categoryWidth.toPx()
                    alpha = shown
                },
        )
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
    interactive: Boolean = true,
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
                        .focusProperties { canFocus = interactive }
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
private fun TimeRuler(windowStart: Long, origin: Long, now: Long, window: () -> Long, slide: () -> Float) {
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
        // The times for the hours either side too, laid out once and slid along with the grid.
        val times = remember(origin) { (0 until (SPAN / HALF_HOUR).toInt()).map { origin + it * HALF_HOUR } }
        val spans = remember(origin, now) {
            times.map { it to it + HALF_HOUR } + listOfNotNull((now to now + MINUTE).takeIf { now in origin until origin + SPAN })
        }
        Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            ProgrammeStrip(spans = spans, origin = origin, window = window, slide = slide) {
                for (at in times) {
                    Box(Modifier.fillMaxSize()) {
                        Label(
                            text = clockTime(at),
                            style = AppType.label,
                            color = colors.textDim,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
                if (now in origin until origin + SPAN) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: LineupChannel,
    channels: ChannelsRepository,
    /** Where the grid is (read only when needed, so moving it doesn't rebuild the row). */
    window: () -> Long,
    /** The slide in progress, in milliseconds still to go. */
    slide: () -> Float,
    /** The time the row's programmes are laid out from; changes only every few hours of moving. */
    origin: Long,
    now: Long,
    earliest: Long,
    entry: FocusRequester?,
    onFocus: (ProgrammeEntity?) -> Unit,
    onShift: (Long) -> Unit,
    onPlay: () -> Unit,
    onCatchup: (ProgrammeEntity) -> Unit,
    reminders: Set<String>,
    onRemind: (ProgrammeEntity) -> Unit,
    notWorking: Boolean = false,
    jumpState: androidx.compose.runtime.MutableState<Jump?>? = null,
    index: Int = -1,
    cache: ProgrammeCache? = null,
) {
    val colors = AppTheme.colors
    // Only the row being moved to notices the move.
    val jumpTime by remember(jumpState, index) { derivedStateOf { jumpState?.value?.takeIf { it.row == index }?.time } }
    val onJumped: () -> Unit = { if (jumpState?.value?.row == index) jumpState.value = null }
    // Several hours either side of what's in view, loaded once and laid out once: moving along the
    // grid just slides them, with nothing rebuilt or loaded until a few hours further on.
    // Already read (the category's first screenful is read before it's shown): there from the first frame.
    var programmes by remember { mutableStateOf(cache?.get(channel, origin)) }
    var loadedOrigin by remember { mutableLongStateOf(if (programmes != null) origin else Long.MIN_VALUE) }
    LaunchedEffect(channel.key, origin) {
        programmes = cache?.load(channels, channel, origin) ?: channels.programmes(channel, origin, origin + SPAN)
        loadedOrigin = origin
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            // Lets the walkthrough check no row is ever shown waiting for its programmes after a change.
            .then(if (programmes == null) Modifier.testTag("guide-row-waiting") else Modifier),
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

        // Edge keys slide the grid instead of leaving it.
        fun edgeKeys(start: Long, stop: Long) = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val w = window()
            when {
                event.key == Key.DirectionRight && stop >= w + WINDOW -> {
                    onShift(HOUR)
                    true
                }
                event.key == Key.DirectionLeft && start <= w && w > earliest -> {
                    onShift(-HOUR)
                    true
                }
                else -> false
            }
        }

        val list = programmes.orEmpty().filter { it.stop > origin && it.start < origin + SPAN }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            if (programmes != null && list.isEmpty()) {
                GuideCell(
                    title = "No programme information",
                    past = false,
                    live = false,
                    catchup = false,
                    onFocus = { onFocus(null) },
                    onClick = onPlay,
                    refocus = jumpTime != null,
                    onRefocused = onJumped,
                    modifier = edgeKeys(Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)
                        .fillMaxSize()
                        .then(if (entry != null) Modifier.focusRequester(entry) else Modifier),
                )
            } else if (list.isNotEmpty()) {
                val focusTarget = list.firstOrNull { now in it.start until it.stop } ?: list.first()
                // Up or Down landing here: the programme on at the moment being kept to, or the nearest after it.
                val jumpId = jumpTime?.let { time ->
                    val w = window()
                    val inView = list.filter { it.stop > w && it.start < w + WINDOW }
                    (inView.firstOrNull { time >= it.start && time < it.stop }
                        ?: inView.firstOrNull { it.stop > time }
                        ?: inView.lastOrNull() ?: list.last()).id
                }
                ProgrammeStrip(spans = list.map { maxOf(it.start, origin) to minOf(it.stop, origin + SPAN) }, origin = origin, window = window, slide = slide) {
                    for (programme in list) {
                        val from = maxOf(programme.start, origin)
                        val to = minOf(programme.stop, origin + SPAN)
                        val live = now in programme.start until programme.stop
                        val catchup = !live && Catchup.available(channel.catchupDays, programme.start, programme.stop, now)
                        key(programme.id) {
                            GuideCell(
                                title = programme.title,
                                past = programme.stop <= now,
                                live = live,
                                catchup = catchup,
                                reminder = "${channel.key}|${programme.start}" in reminders,
                                onFocus = { onFocus(programme) },
                                refocus = jumpId != null && programme.id == jumpId && loadedOrigin == origin,
                                onRefocused = onJumped,
                                // The name stays in view at the left edge for a programme that started earlier.
                                titleShift = { pxPerMilli ->
                                    val visibleFrom = window() + slide().toLong()
                                    val hidden = (visibleFrom - from).coerceIn(0L, (to - from - 20 * MINUTE).coerceAtLeast(0L))
                                    (hidden * pxPerMilli).toInt()
                                },
                                onClick = {
                                    when {
                                        live -> onPlay()
                                        catchup -> onCatchup(programme)
                                        programme.start > now -> onRemind(programme)
                                    }
                                },
                                modifier = edgeKeys(programme.start, programme.stop)
                                    // Only programmes in view can be highlighted.
                                    .focusProperties {
                                        val w = window()
                                        canFocus = programme.stop > w && programme.start < w + WINDOW
                                    }
                                    // The first channel's current programme is where Right from the menu lands.
                                    .then(if (entry != null && programme.id == focusTarget.id) Modifier.focusRequester(entry) else Modifier),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How wide a millisecond is, shared with the cells so their names can stay in view. */
private val LocalPxPerMilli = androidx.compose.runtime.compositionLocalOf { floatArrayOf(0f) }

/**
 * Lays programmes out by time from [origin] (positions depend only on the time and the width, so
 * they're worked out once), then draws the strip slid along to the current window: moving the guide
 * only moves the drawing.
 */
@Composable
private fun ProgrammeStrip(
    spans: List<Pair<Long, Long>>,
    origin: Long,
    window: () -> Long,
    slide: () -> Float,
    content: @Composable () -> Unit,
) {
    val pxPerMilli = remember { floatArrayOf(0f) }
    androidx.compose.runtime.CompositionLocalProvider(LocalPxPerMilli provides pxPerMilli) {
        Layout(
            content = content,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -((window() - origin) + slide()) * pxPerMilli[0] },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val perMilli = width.toFloat() / WINDOW
            pxPerMilli[0] = perMilli
            val gap = 3.dp.roundToPx()
            val placeables = measurables.mapIndexed { i, measurable ->
                val (from, to) = spans.getOrElse(i) { origin to origin }
                val cellWidth = ((to - from) * perMilli).toInt() - gap
                measurable.measure(androidx.compose.ui.unit.Constraints.fixed(cellWidth.coerceAtLeast(4), height))
            }
            layout(width, height) {
                placeables.forEachIndexed { i, placeable ->
                    val (from, _) = spans.getOrElse(i) { origin to origin }
                    placeable.place(((from - origin) * perMilli).toInt(), 0)
                }
            }
        }
    }
}

@Composable
private fun GuideCell(
    title: String,
    past: Boolean,
    live: Boolean,
    catchup: Boolean,
    reminder: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    refocus: Boolean = false,
    onRefocused: () -> Unit = {},
    /** How far to move the name along (in pixels, given pixels per millisecond) to keep it in view. */
    titleShift: ((Float) -> Int)? = null,
) {
    val colors = AppTheme.colors
    val requester = remember { FocusRequester() }
    val pxPerMilli = LocalPxPerMilli.current
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
                .then(if (titleShift != null) Modifier.offset { IntOffset(titleShift(pxPerMilli[0]), 0) } else Modifier)
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

/** The hours a row lays out at once: from two hours before a four-hour block to four after it. */
private const val CHUNK = 4 * 3_600_000L
private const val SPAN = 10 * 3_600_000L

private fun originFor(windowStart: Long): Long = windowStart - Math.floorMod(windowStart, CHUNK) - 2 * 3_600_000L

/** How long the highlight rests on a category before its channels are shown. */
private const val CATEGORY_SETTLE_MILLIS = 160L

/** How many rows' programmes are read before a category is shown: a screenful and a little more. */
private const val PREFETCH_ROWS = 12

/** How long the categories take to slide away or back. */
private const val PANEL_MILLIS = 220

private fun channelsIn(lineup: Lineup, group: String?, favourites: Set<String>): List<LineupChannel> = when (group) {
    null -> lineup.channels
    FAVOURITES -> lineup.channels.filter { it.key in favourites }
    else -> lineup.inGroup(group)
}

/** Programmes read during one visit to the guide, by channel guide and laid-out hours (the most recent few hundred). */
private class ProgrammeCache {
    private val map = object : LinkedHashMap<String, List<ProgrammeEntity>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ProgrammeEntity>>?) = size > 600
    }

    private fun key(channel: LineupChannel, origin: Long) = "${channel.playlistId}|${channel.guideId}|$origin"

    fun get(channel: LineupChannel, origin: Long): List<ProgrammeEntity>? =
        if (channel.guideId == null) emptyList() else synchronized(map) { map[key(channel, origin)] }

    suspend fun load(channels: ChannelsRepository, channel: LineupChannel, origin: Long): List<ProgrammeEntity> =
        get(channel, origin) ?: channels.programmes(channel, origin, origin + SPAN).also { found ->
            synchronized(map) { map[key(channel, origin)] = found }
        }

    /** Reads the programmes for [rows] together; already-read ones cost nothing. */
    suspend fun prefetch(channels: ChannelsRepository, rows: List<LineupChannel>, origin: Long) {
        runCatching {
            coroutineScope { rows.filter { get(it, origin) == null }.map { async { load(channels, it, origin) } }.awaitAll() }
        }
    }
}

/** How long the grid takes to slide along an hour. */
private const val SLIDE_MILLIS = 320

/** The quickest the guide moves between channels with the button held down. */
private const val HELD_MOVE_MILLIS = 120L

/** How long a move up or down (or Back to now) is given to land before it's abandoned. */
private const val JUMP_GIVE_UP_MILLIS = 1_500L

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
