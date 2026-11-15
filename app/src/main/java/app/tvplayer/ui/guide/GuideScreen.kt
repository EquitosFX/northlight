package app.tvplayer.ui.guide

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
    var focused by remember { mutableStateOf<Focused?>(null) }
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

    fun shift(by: Long) {
        val target = (windowStart + by).coerceIn(earliest, liveStart + 7 * 24 * HOUR - WINDOW)
        if (target == windowStart) return
        refocus = focused?.programme?.id
        if (refocus != null) holdFocus()
        windowStart = target
    }

    // Once a category is chosen the categories step aside and the listings fill the screen.
    // Back brings them back with the chosen one highlighted; Back again goes to the side menu.
    var listings by remember { mutableStateOf(false) }
    var backToCategories by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
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

    val rows = when (group) {
        null -> current.channels
        FAVOURITES -> current.channels.filter { it.key in favourites }
        else -> current.inGroup(group)
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
            modifier = Modifier.width(150.dp).fillMaxHeight(),
        )
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .onFocusChanged { if (it.hasFocus) listings = true },
    ) {
        Header(focused = focused, modifier = Modifier.padding(horizontal = SideMargin))
        TimeRuler(windowStart = windowStart, now = now)
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
            modifier = Modifier.weight(1f),
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
                    onFocus = { programme -> focused = Focused(channel, programme) },
                    onShift = ::shift,
                    refocus = refocus,
                    onRefocused = { refocus = null },
                    onPlay = { onPlay(channel) },
                    onCatchup = { programme -> onCatchup(channel, programme) },
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
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val entries = remember(groups) { listOf<String?>(FAVOURITES, null) + groups }
    // Coming back to the categories lands on the chosen one.
    val entry = remember { EntryFocus() }
    // The guide opens here: moving in from the menu, or arriving from a button, lands on the chosen category.
    EntryPoint(currentFocus)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (autoFocus()) runCatching { currentFocus.requestFocus() }
    }
    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text = "TV guide", style = AppType.headline, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f).entersAt(entry),
        ) {
            items(entries.size) { index ->
                val name = entries[index]
                val current = name == selected
                if (current) EntryPoint(currentFocus, entry)
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
private fun Header(focused: Focused?, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
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
            else -> null
        }
        Label(text = listOfNotNull(meta, hint).joinToString("  ·  "), style = GuideMetaText, color = colors.accent, maxLines = 1)
        val description = programme.description
        if (description != null) {
            Label(text = description, style = GuideBodyText, color = colors.textDim, maxLines = 2)
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, now: Long) {
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
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val width = maxWidth
            for (step in 0 until (WINDOW / HALF_HOUR).toInt()) {
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
            if (now in windowStart until windowStart + WINDOW) {
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
) {
    val colors = AppTheme.colors
    val windowEnd = windowStart + WINDOW
    // Keeps showing the previous programmes while new ones load, so the grid doesn't flash.
    var programmes by remember { mutableStateOf<List<ProgrammeEntity>?>(null) }
    var loadedFor by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(channel.key, windowStart) {
        programmes = channels.programmes(channel, windowStart, windowEnd)
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
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            val width = maxWidth
            val list = programmes ?: return@BoxWithConstraints

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

            if (list.isEmpty()) {
                GuideCell(
                    title = "No programme information",
                    x = 0.dp,
                    width = width,
                    past = false,
                    live = false,
                    catchup = false,
                    onFocus = { onFocus(null) },
                    onClick = onPlay,
                    modifier = edgeKeys(windowStart, windowEnd)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (entry != null) Modifier.focusRequester(entry) else Modifier),
                )
                if (focusRequester != null) FocusOnce(focusRequester, onFocusPlaced)
                return@BoxWithConstraints
            }

            val focusTarget = list.firstOrNull { now in it.start until it.stop } ?: list.first()
            for (programme in list) {
                val from = maxOf(programme.start, windowStart)
                val to = minOf(programme.stop, windowEnd)
                if (to - from < MINUTE) continue
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
                        onFocus = { onFocus(programme) },
                        refocus = programme.id == refocus && loadedFor == windowStart,
                        onRefocused = onRefocused,
                        onClick = {
                            when {
                                live -> onPlay()
                                catchup -> onCatchup(programme)
                            }
                        },
                        modifier = edgeKeys(programme.start, programme.stop).then(
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

@Composable
private fun GuideCell(
    title: String,
    x: Dp,
    width: Dp,
    past: Boolean,
    live: Boolean,
    catchup: Boolean,
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
