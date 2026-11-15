package app.tvplayer.ui.vod

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Color
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
) {
    val colors = AppTheme.colors
    val series = state.series
    if (series != null) {
        EpisodesPane(
            series = series,
            playlists = playlists,
            metadata = metadata,
            onPlay = onPlay,
            onPerson = onPerson,
            onBack = { state.series = null },
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
            onBack = { state.film = null },
        )
        return
    }

    val groups by produceState<List<GroupCount>?>(initialValue = null, kind) { value = channels.groups(kind) }
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

    val items by produceState<List<VodRow>?>(initialValue = null, kind, state.group) {
        value = channels.inGroup(kind, state.group)
    }
    val groupFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.lastIndex)

    // On arrival focus the chosen group; coming back from watching, focus the poster that was picked.
    LaunchedEffect(items != null) {
        if (items == null) return@LaunchedEffect
        withFrameNanos { }
        val target = if (state.returnToGrid) gridFocus else groupFocus
        runCatching { target.requestFocus() }
        state.returnToGrid = false
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = SideMargin, end = SideMargin, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .width(200.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(list) { group ->
                val selected = group.groupName == state.group
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
                        Label(text = group.groupName ?: "Other", style = AppType.label, modifier = Modifier.weight(1f))
                        Label(text = group.count.toString(), style = AppType.caption, color = colors.textDim)
                    }
                }
            }
        }

        val rows = items.orEmpty()
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = gridState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                PosterCard(
                    row = row,
                    modifier = if (index == state.lastIndex) Modifier.focusRequester(gridFocus) else Modifier,
                    onClick = {
                        state.lastIndex = index
                        state.returnToGrid = true
                        // Xtream Codes series are listed as whole series; M3U lists each episode on its own.
                        if (row.kind == ChannelEntity.KIND_SERIES && row.remoteId != null) {
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

@Composable
internal fun PosterCard(row: VodRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(8.dp),
            outlined = false,
        ) {
            // The title stays underneath, so it shows if the poster is missing or fails to load.
            Label(
                text = row.name,
                style = AppType.label,
                color = colors.textDim,
                maxLines = 4,
                modifier = Modifier.padding(8.dp),
            )
            if (!row.logo.isNullOrBlank()) {
                AsyncImage(
                    model = row.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Label(text = row.name, style = AppType.caption, color = colors.textDim)
    }
}

@Composable
private fun EpisodesPane(
    series: VodRow,
    playlists: PlaylistRepository,
    metadata: MetadataRepository,
    onPlay: (VodItem, List<VodItem>) -> Unit,
    onPerson: (TmdbPerson) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    var details by remember(series.id) { mutableStateOf<SeriesDetails?>(null) }
    var problem by remember(series.id) { mutableStateOf<String?>(null) }
    var season by remember(series.id) { mutableIntStateOf(-1) }
    val firstEpisode = remember { FocusRequester() }
    val info by produceState<MetadataEntity?>(initialValue = null, series.id) {
        value = metadata.lookup(series.name, ChannelEntity.KIND_SERIES)
    }

    BackHandler { onBack() }
    LaunchedEffect(series.id) {
        try {
            details = playlists.seriesDetails(series)
        } catch (e: Exception) {
            problem = playlists.explainError(e)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = SideMargin, end = SideMargin, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val poster = series.logo?.takeIf { it.isNotBlank() } ?: info?.poster
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(150.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Label(text = info?.title ?: series.name, style = AppType.headline, maxLines = 2)
            val meta = listOfNotNull(
                info?.year?.toString(),
                info?.rating?.let { "★ %.1f".format(it) },
                info?.seasons?.let { if (it == 1) "1 season" else "$it seasons" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Label(text = meta, style = AppType.label, color = colors.accent)
            info?.genres?.takeIf { it.isNotEmpty() }?.let { Label(text = it, style = AppType.caption, color = colors.textDim) }
            (details?.plot ?: info?.overview)?.let { Label(text = it, style = AppType.body, color = colors.textDim, maxLines = 6) }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            info?.let { PeopleRow(people = it.peopleList(), onPerson = onPerson) }
            val loaded = details
            val message = when {
                problem != null -> problem
                loaded == null -> "Loading episodes…"
                loaded.episodes.isEmpty() -> "No episodes found for this series."
                else -> null
            }
            if (message != null || loaded == null) {
                Label(text = message.orEmpty(), style = AppType.body, color = colors.textDim, maxLines = 3)
                return@Column
            }

            // Every episode in order, so playback can carry on into the next one, across seasons.
            val queue = loaded.episodes.map { episode ->
                VodItem(
                    url = episode.url,
                    title = "${series.name} · S${episode.season} E${episode.number}",
                    poster = series.logo,
                    userAgent = series.userAgent,
                    referrer = series.referrer,
                )
            }
            val seasons = loaded.episodes.map { it.season }.distinct()
            val shown = if (season in seasons) season else seasons.first()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons) { number ->
                    FocusSurface(
                        onClick = { season = number },
                        modifier = Modifier.height(32.dp),
                        container = if (number == shown) colors.glassStrong else null,
                    ) { focused ->
                        LaunchedEffect(focused) { if (focused) season = number }
                        Label(text = "Season $number", style = AppType.label, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }

            val episodes = loaded.episodes.filter { it.season == shown }
            LaunchedEffect(loaded) {
                withFrameNanos { }
                runCatching { firstEpisode.requestFocus() }
            }
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(episodes, key = { _, episode -> episode.url }) { index, episode ->
                    FocusSurface(
                        onClick = {
                            queue.firstOrNull { it.url == episode.url }?.let { item -> onPlay(item, queue) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .then(if (index == 0) Modifier.focusRequester(firstEpisode) else Modifier),
                        focusScale = 1.01f,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label(
                                text = "E${episode.number}",
                                style = AppType.title,
                                color = colors.accent,
                                modifier = Modifier.width(44.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Label(text = episode.title, style = AppType.title)
                                episode.plot?.let { Label(text = it, style = AppType.caption, color = colors.textDim) }
                            }
                            episode.durationSecs?.takeIf { it > 0 }?.let {
                                Label(text = "${it / 60} min", style = AppType.caption, color = colors.textDim)
                            }
                        }
                    }
                }
            }
        }
    }
}
