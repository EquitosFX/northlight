package app.tvplayer.ui.guide

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

private val ChannelColumn = 196.dp
private val RowHeight = 40.dp
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
fun GuideScreen(channels: ChannelsRepository, onPlay: (LineupChannel) -> Unit) {
    val colors = AppTheme.colors
    val lineup by produceState<Lineup?>(initialValue = null) { value = channels.lineup() }
    val now = remember { System.currentTimeMillis() }
    val earliest = remember { now - now % HALF_HOUR }
    var windowStart by remember { mutableLongStateOf(earliest) }
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

    fun shift(by: Long) {
        windowStart = (windowStart + by).coerceIn(earliest, earliest + 7 * 24 * HOUR - WINDOW)
    }

    val rows = when (group) {
        null -> current.channels
        FAVOURITES -> current.channels.filter { it.key in favourites }
        else -> current.inGroup(group)
    }

    Row(modifier = Modifier.fillMaxSize().padding(start = 34.dp)) {
    Categories(
        groups = current.groups,
        selected = group,
        onSelect = { group = it },
        modifier = Modifier.width(150.dp).fillMaxHeight(),
    )
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                    focusRequester = if (index == 0 && !placedFocus) firstFocus else null,
                    entry = if (index == 0) entryFocus else null,
                    onFocusPlaced = { placedFocus = true },
                    onFocus = { programme -> focused = Focused(channel, programme) },
                    onShift = ::shift,
                    onPlay = { onPlay(channel) },
                )
            }
        }
    }
    }
}

/** Favourites, All channels and the playlist's groups. Moving through them changes the channels shown. */
@Composable
private fun Categories(groups: List<String>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val entries = remember(groups) { listOf<String?>(FAVOURITES, null) + groups }
    // Left from the listings returns to the chosen category.
    val currentFocus = remember { FocusRequester() }
    val entry = remember { EntryFocus() }
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
                    onClick = { onSelect(name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
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
                        style = if (focused || current) AppType.title else AppType.body,
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
            .height(112.dp)
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (focused == null) return@Column
        val channel = focused.channel
        val programme = focused.programme
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(url = channel.logo, modifier = Modifier.size(width = 34.dp, height = 22.dp))
            Label(text = "${channel.number} · ${channel.name}", style = AppType.label, color = colors.textDim)
        }
        if (programme == null) {
            Label(text = "No programme information", style = AppType.headline)
            Label(text = "Press OK to watch this channel.", style = AppType.body, color = colors.textDim)
            return@Column
        }
        Label(text = programme.title, style = AppType.headline)
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
        Label(text = meta, style = AppType.label, color = colors.accent)
        val description = programme.description
        if (description != null) {
            Label(text = description, style = AppType.body, color = colors.textDim, maxLines = 2)
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
                text = if (windowStart - now > 18 * HOUR) dayLabel(windowStart) else "Today",
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
    onPlay: () -> Unit,
) {
    val colors = AppTheme.colors
    val windowEnd = windowStart + WINDOW
    // Keeps showing the previous programmes while new ones load, so the grid doesn't flash.
    val programmes by produceState<List<ProgrammeEntity>?>(initialValue = null, channel.key, windowStart) {
        value = channels.programmes(channel, windowStart, windowEnd)
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
                style = AppType.caption,
                color = colors.textDim,
                modifier = Modifier.width(30.dp),
            )
            Logo(url = channel.logo, modifier = Modifier.size(width = 36.dp, height = 26.dp))
            Label(text = channel.name, style = AppType.label)
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
                    onFocus = { onFocus(null) },
                    onClick = onPlay,
                    modifier = edgeKeys(windowStart, windowEnd)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (entry != null) Modifier.focusRequester(entry) else Modifier),
                )
                if (entry != null) EntryPoint(entry)
                if (focusRequester != null) FocusOnce(focusRequester, onFocusPlaced)
                return@BoxWithConstraints
            }

            val focusTarget = list.firstOrNull { now in it.start until it.stop } ?: list.first()
            for (programme in list) {
                val from = maxOf(programme.start, windowStart)
                val to = minOf(programme.stop, windowEnd)
                if (to - from < MINUTE) continue
                val live = now in programme.start until programme.stop
                key(programme.id) {
                    GuideCell(
                        title = programme.title,
                        x = position(from - windowStart, width),
                        width = position(to - from, width) - 3.dp,
                        past = programme.stop <= now,
                        live = live,
                        onFocus = { onFocus(programme) },
                        onClick = { if (live) onPlay() },
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
                    if (entry != null && programme.id == focusTarget.id) EntryPoint(entry)
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
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier
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
        Label(
            text = title,
            style = AppType.label,
            color = when {
                focused -> colors.onAccent
                past -> colors.textFaint
                else -> colors.text
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = 8.dp),
        )
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

private fun dayLabel(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
