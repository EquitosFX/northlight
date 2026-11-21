package app.tvplayer.ui.vod

import app.tvplayer.ui.components.fadeBottomEdge
import app.tvplayer.ui.components.MeasureOpen
import app.tvplayer.ui.components.LocalPosterLookup
import app.tvplayer.ui.components.NewEpisodesBadge
import app.tvplayer.ui.components.WatchedTick
import app.tvplayer.ui.components.LocalWatched
import app.tvplayer.data.MyListRepository
import app.tvplayer.data.WatchedTitles
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.entersAt
import app.tvplayer.ui.components.EntryFocus
import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.LocalHoldFocus
import app.tvplayer.ui.components.fadeIntoBackground
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.peopleList
import app.tvplayer.ui.person.PeopleRow
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.data.SeriesDetails
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.GroupCount
import app.tvplayer.data.db.VodRow
import app.tvplayer.player.VodItem
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import coil.compose.AsyncImage

/** Where the viewer was in Films or Series, kept while they watch something so they come back to the same place. */
class VodBrowseState {
    var group by mutableStateOf<String?>(null)
    var groupChosen by mutableStateOf(false)
    var series by mutableStateOf<VodRow?>(null)
    var film by mutableStateOf<VodRow?>(null)
    var lastIndex by mutableIntStateOf(0)
    var returnToGrid by mutableStateOf(false)
    /** Set when a title was opened from another section (Movie night): Back goes back there. */
    var returnTo by mutableStateOf<Dest?>(null)
}

private val SideMargin = 48.dp

/**
 * Films or Series: groups down the left, posters on the right. Moving through the groups changes
 * the posters. A film plays straight away; a series opens its episodes.
 */
@Composable
fun VodScreen(
    kind: String,
    state: VodBrowseState,
    channels: ChannelsRepository,
    playlists: PlaylistRepository,
    metadata: MetadataRepository,
    onPlay: (VodItem, List<VodItem>) -> Unit,
    onPerson: (TmdbPerson) -> Unit,
    onReturn: (Dest) -> Unit = {},
    watched: WatchedTitles? = null,
    myList: MyListRepository? = null,
) {
    val colors = AppTheme.colors
    val holdFocus = LocalHoldFocus.current
    fun leave() {
        val back = state.returnTo ?: return
        state.returnTo = null
        onReturn(back)
    }
    val series = state.series
    if (series != null) {
        SeriesPane(
            series = series,
            channels = channels,
            playlists = playlists,
            metadata = metadata,
            watched = watched,
            myList = myList,
            onPlay = onPlay,
            onPerson = onPerson,
            onBack = {
                holdFocus()
                state.series = null
                leave()
            },
        )
        return
    }
    val film = state.film
    if (film != null) {
        FilmPane(
            film = film,
            channels = channels,
            metadata = metadata,
            onPlay = onPlay,
            onPerson = onPerson,
            onBack = {
                holdFocus()
                state.film = null
                leave()
            },
            myList = myList,
        )
        return
    }

    val groups by produceState(initialValue = channels.cachedGroups(kind), kind) { value = channels.groups(kind) }
    MeasureOpen(name = if (kind == ChannelEntity.KIND_SERIES) "Series" else "Films", ready = groups != null)
    val list = groups
    val noun = if (kind == ChannelEntity.KIND_MOVIE) "films" else "series"
    if (list == null || list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label(
                text = if (list == null) "Loading…" else "No $noun yet. Add a playlist in Settings › Playlists.",
                style = AppType.headline,
                color = colors.textDim,
            )
        }
        return
    }
    if (!state.groupChosen) {
        state.group = list.first().groupName
        state.groupChosen = true
    }

    val items by produceState(initialValue = channels.cachedInGroup(kind, state.group), kind, state.group) {
        // Straight from memory when it's been opened before, so nothing flashes.
        channels.cachedInGroup(kind, state.group)?.let { value = it }
        value = channels.inGroup(kind, state.group)
    }
    val groupFocus = remember { FocusRequester() }
    val groupEntry = remember { EntryFocus() }
    val gridFocus = remember { FocusRequester() }
    val autoFocus = LocalAutoFocus.current
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.lastIndex)
    // The header shows the highlighted poster's details, looked up once the highlight settles.
    // Until a poster is highlighted, the header shows the group's first title.
    var highlighted by remember(kind, state.group) { mutableStateOf<VodRow?>(null) }
    val shown = highlighted ?: items?.firstOrNull()
    val highlightInfo by produceState(initialValue = shown?.let { metadata.peek(it.name, it.kind) }, shown?.id) {
        val row = shown ?: return@produceState
        if (highlighted != null) delay(350)
        value = metadata.lookup(row.name, row.kind)
    }

    // On arrival focus the chosen group; coming back from watching, focus the poster that was picked.
    LaunchedEffect(items != null) {
        if (items == null) return@LaunchedEffect
        withFrameNanos { }
        val target = if (state.returnToGrid) gridFocus else groupFocus
        if (autoFocus()) runCatching { target.requestFocus() }
        state.returnToGrid = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    val backdrop = highlightInfo?.backdrop?.takeIf { shown != null }
    if (backdrop != null) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.72f)
                .height(250.dp)
                .fadeIntoBackground(solidFrom = 0.45f, solidUntil = 0.4f),
        ) {
            AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = SideMargin, end = SideMargin, top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(180.dp)
                .fillMaxHeight()
                .entersAt(groupEntry),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(list) { group ->
                val selected = group.groupName == state.group
                if (selected) {
                    EntryPoint(groupFocus)
                    // Left from the posters returns to the chosen group.
                    EntryPoint(groupFocus, groupEntry)
                }
                FocusSurface(
                    onClick = { state.group = group.groupName },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .then(if (selected) Modifier.focusRequester(groupFocus) else Modifier),
                    container = if (selected) colors.glassStrong else Color.Transparent,
                    outlined = false,
                    focusScale = 1.02f,
                ) { focused ->
                    // Moving through the groups shows each one's posters straight away.
                    LaunchedEffect(focused) {
                        if (focused && state.group != group.groupName) {
                            state.group = group.groupName
                            state.lastIndex = 0
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = when (group.groupName) {
                            ChannelsRepository.RECENTLY_ADDED -> "Recently added"
                            null -> "Other"
                            else -> group.groupName
                        }
                        Label(text = label, style = AppType.label, modifier = Modifier.weight(1f))
                        Label(text = group.count.toString(), style = AppType.caption, color = colors.textDim)
                    }
                }
            }
        }

        val rows = items.orEmpty()
        Column(modifier = Modifier.weight(1f)) {
        // In "Recently added", when each title was added.
        val addedOn = if (state.group == ChannelsRepository.RECENTLY_ADDED) shown?.let { channels.addedDate(it) } else null
        HighlightHeader(row = shown, info = highlightInfo, addedOn = addedOn)
        LazyVerticalGrid(
            // Posters stay a readable size: about three across with the side panel open, five with it tucked away.
            columns = GridCells.Adaptive(minSize = 118.dp),
            state = gridState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The row cut off at the bottom fades away, so it's clear there's more below.
                .fadeBottomEdge { gridState.canScrollForward },
        ) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                PosterCard(
                    row = row,
                    modifier = if (index == state.lastIndex) Modifier.focusRequester(gridFocus) else Modifier,
                    onFocus = { highlighted = row },
                    onClick = {
                        holdFocus()
                        state.lastIndex = index
                        state.returnToGrid = true
                        // A series opens its episode list (M3U episodes are gathered under their show).
                        if (row.kind == ChannelEntity.KIND_SERIES) {
                            state.series = row
                        } else {
                            state.film = row
                        }
                    },
                )
            }
        }
        }
    }
    }
}

/** "Added today", "Added yesterday", "Added 3 days ago", then "Added 12 Nov". */
private fun addedText(seconds: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    return when {
        days <= 0L -> "Added today"
        days == 1L -> "Added yesterday"
        days < 7L -> "Added $days days ago"
        day.year == today.year -> "Added " + day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.UK))
        else -> "Added " + day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.UK))
    }
}

/** The highlighted title's details, above the posters. */
@Composable
private fun HighlightHeader(row: VodRow?, info: MetadataEntity?, addedOn: Long? = null) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .height(132.dp)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (row == null) return@Column
        Label(text = info?.title ?: row.name, style = AppType.headline, maxLines = 1)
        val meta = listOfNotNull(
            addedOn?.let(::addedText),
            info?.year?.toString(),
            info?.runtimeMinutes?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
            info?.rating?.let { "★ %.1f".format(it) },
            info?.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
            info?.genres?.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Label(text = meta, style = AppType.label, color = colors.accent)
        info?.overview?.let { Label(text = it, style = AppType.body, color = colors.textDim, maxLines = 3) }
    }
}

@Composable
internal fun PosterCard(
    row: VodRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    /** Episodes have been added since the viewer last opened this series. */
    newEpisodes: Boolean = false,
) {
    val colors = AppTheme.colors
    val watched = LocalWatched.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(8.dp),
            outlined = false,
        ) { focused ->
            LaunchedEffect(focused) { if (focused) onFocus() }
            // The title stays underneath, so it shows if the poster is missing or fails to load.
            Label(
                text = row.name,
                style = AppType.label,
                color = colors.textDim,
                maxLines = 4,
                modifier = Modifier.padding(8.dp),
            )
            // The playlist's picture; if it's missing or won't load, TMDB's poster instead.
            val lookup = LocalPosterLookup.current
            var ownFailed by remember(row.logo) { mutableStateOf(row.logo.isNullOrBlank()) }
            val fallback by produceState<String?>(initialValue = null, ownFailed, row.id) {
                if (ownFailed) value = runCatching { lookup(row) }.getOrNull()
            }
            val picture = if (ownFailed) fallback else row.logo
            if (!picture.isNullOrBlank()) {
                AsyncImage(
                    model = picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { if (!ownFailed) ownFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (row.kind == ChannelEntity.KIND_MOVIE && row.url in watched) WatchedTick(Modifier.align(Alignment.TopEnd))
            if (newEpisodes) NewEpisodesBadge(Modifier.align(Alignment.TopStart))
        }
        Label(text = row.name, style = AppType.caption, color = colors.textDim)
    }
}

