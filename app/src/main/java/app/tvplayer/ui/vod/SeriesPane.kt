package app.tvplayer.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvplayer.core.tmdb.TmdbEpisode
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.Episode
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.data.SeriesDetails
import app.tvplayer.data.WatchedTitles
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.MetadataEntity
import app.tvplayer.data.db.ProgressEntity
import app.tvplayer.data.db.VodRow
import app.tvplayer.data.peopleList
import app.tvplayer.player.VodItem
import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.person.PeopleRow
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import coil.compose.AsyncImage

/** What the big button on a series page does. */
private data class NextUp(val episode: Episode, val resume: Boolean, val fraction: Float)

/**
 * A series page in the style of the big streaming apps: the show's artwork filling the screen, its
 * title logo, a Resume (or Play) button for the right episode, the season picker, a description of
 * the episode in view, and the season's episodes as picture cards along the bottom.
 */
@Composable
internal fun SeriesPane(
    series: VodRow,
    channels: ChannelsRepository,
    playlists: PlaylistRepository,
    metadata: MetadataRepository,
    watched: WatchedTitles?,
    onPlay: (VodItem, List<VodItem>) -> Unit,
    onPerson: (TmdbPerson) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppTheme.colors
    var details by remember(series.id) { mutableStateOf<SeriesDetails?>(null) }
    var problem by remember(series.id) { mutableStateOf<String?>(null) }
    val info by produceState<MetadataEntity?>(initialValue = null, series.id) {
        value = metadata.lookup(series.name, ChannelEntity.KIND_SERIES)
    }
    val logo by produceState<String?>(initialValue = null, info?.tmdbId) {
        value = info?.tmdbId?.let { metadata.titleLogo(it, isSeries = true) }
    }
    LaunchedEffect(series.id) {
        try {
            details = playlists.seriesDetails(series)
        } catch (e: Exception) {
            problem = playlists.explainError(e)
        }
    }
    val episodes = details?.episodes.orEmpty()
    val progress by produceState(initialValue = emptyList<ProgressEntity>(), episodes) {
        value = if (episodes.isEmpty()) emptyList() else channels.progressFor(episodes.map { it.url })
    }

    // Resume the episode part-watched most recently; after a finished one, play the next.
    val nextUp: NextUp? = remember(episodes, progress) {
        if (episodes.isEmpty()) return@remember null
        val byUrl = progress.associateBy { it.url }
        fun fraction(p: ProgressEntity?) = if (p == null || p.durationMs <= 0) 0f else (p.positionMs.toFloat() / p.durationMs).coerceIn(0f, 1f)
        val last = watched?.lastEpisode(series.name)?.let { url -> episodes.indexOfFirst { it.url == url } }?.takeIf { it >= 0 }
        val recent = progress.maxByOrNull { it.updatedAt }?.let { p -> episodes.indexOfFirst { it.url == p.url } }?.takeIf { it >= 0 }
        when {
            last != null && byUrl[episodes[last].url] != null -> NextUp(episodes[last], true, fraction(byUrl[episodes[last].url]))
            // Watched to the end: the next one (or back to the start after the last).
            last != null && watched?.isFinished(episodes[last].url) == true ->
                episodes.getOrNull(last + 1)?.let { NextUp(it, false, 0f) } ?: NextUp(episodes.first(), false, 0f)
            // Only just started: that episode again.
            last != null -> NextUp(episodes[last], false, 0f)
            recent != null -> NextUp(episodes[recent], true, fraction(byUrl[episodes[recent].url]))
            else -> NextUp(episodes.first(), false, 0f)
        }
    }

    val seasons = episodes.map { it.season }.distinct()
    var season by remember(series.id) { mutableIntStateOf(-1) }
    LaunchedEffect(nextUp?.episode?.season) { if (season == -1) nextUp?.episode?.season?.let { season = it } }
    val shownSeason = if (season in seasons) season else seasons.firstOrNull() ?: 1
    val seasonEpisodes = episodes.filter { it.season == shownSeason }
    val tmdbEpisodes by produceState(initialValue = emptyMap<Int, TmdbEpisode>(), info?.tmdbId, shownSeason) {
        value = info?.tmdbId?.let { id -> metadata.season(id, shownSeason).associateBy { it.number } }.orEmpty()
    }
    // The episode the description talks about: the one highlighted below, else the one the button plays.
    var highlighted by remember(series.id) { mutableStateOf<Episode?>(null) }
    val described = highlighted ?: nextUp?.episode

    // Every episode in order, so playback carries on into the next one, across seasons.
    val queue = remember(episodes) {
        episodes.map { episode ->
            VodItem(
                url = episode.url,
                title = "${series.name} · S${episode.season} E${episode.number}",
                poster = series.logo,
                userAgent = series.userAgent,
                referrer = series.referrer,
            )
        }
    }
    fun play(episode: Episode, fromStart: Boolean = false) {
        val item = queue.firstOrNull { it.url == episode.url } ?: return
        onPlay(if (fromStart) item.copy(fromStart = true) else item, queue)
    }

    val primary = remember { FocusRequester() }
    val episodeRowFocus = remember { FocusRequester() }
    var seasonsOpen by remember(series.id) { mutableStateOf(false) }
    val currentSeasonFocus = remember { FocusRequester() }
    EntryPoint(primary)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(nextUp != null) {
        withFrameNanos { }
        if (autoFocus()) runCatching { primary.requestFocus() }
    }
    LaunchedEffect(seasonsOpen) {
        if (seasonsOpen) {
            withFrameNanos { }
            runCatching { currentSeasonFocus.requestFocus() }
        }
    }
    BackHandler {
        if (seasonsOpen) seasonsOpen = false else onBack()
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.groundBottom)) {
        // The show's artwork across the screen, fading into the page on the left and at the bottom.
        val backdrop = info?.backdrop
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.TopEnd),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to colors.groundBottom.copy(alpha = 0.92f),
                        0.38f to colors.groundBottom.copy(alpha = 0.55f),
                        0.7f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to colors.groundBottom.copy(alpha = 0.35f),
                        0.22f to Color.Transparent,
                        0.55f to colors.groundBottom.copy(alpha = 0.55f),
                        0.85f to colors.groundBottom,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── The hero: logo at the top, buttons and description at the bottom ──
            Box(modifier = Modifier.fillMaxWidth().height(440.dp)) {
                val title = info?.title ?: series.name
                Box(modifier = Modifier.padding(start = 48.dp, top = 26.dp)) {
                    val logoUrl = logo
                    if (logoUrl != null) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier.widthIn(max = 360.dp).heightIn(max = 100.dp),
                        )
                    } else {
                        Label(text = title, style = AppType.display.copy(fontSize = 40.sp, lineHeight = 44.sp), maxLines = 2, modifier = Modifier.width(520.dp))
                    }
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 40.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    // Buttons.
                    Column(modifier = Modifier.width(250.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val up = nextUp
                        if (up != null && up.resume) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RoundIconButton(icon = { tint -> RestartGlyph(tint) }, label = "Start over") { play(up.episode, fromStart = true) }
                            }
                        }
                        val label = when {
                            up == null -> if (problem != null) "Can't load episodes" else "Loading episodes…"
                            up.resume -> "Resume S${up.episode.season} E${up.episode.number}"
                            else -> "Play S${up.episode.season} E${up.episode.number}"
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            FocusSurface(
                                onClick = { up?.let { play(it.episode) } },
                                modifier = Modifier.fillMaxWidth().height(44.dp).focusRequester(primary),
                                container = Color.White.copy(alpha = 0.9f),
                                focusedContainer = colors.accent,
                                outlined = false,
                                focusScale = 1.03f,
                            ) { focused ->
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val ink = if (focused) colors.onAccent else Color(0xFF0B1426)
                                    PlayGlyph(ink, Modifier.size(14.dp))
                                    Label(text = label, style = AppType.title.copy(fontSize = 16.sp), color = ink)
                                }
                            }
                            if (up != null && up.resume && up.fraction > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.25f)),
                                ) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(up.fraction).background(colors.accent))
                                }
                            }
                        }
                        WideButton("Episodes") { runCatching { episodeRowFocus.requestFocus() } }
                        if (seasons.size > 1 || seasonsOpen) {
                            WideButton("Season $shownSeason", chevron = true) { seasonsOpen = !seasonsOpen }
                            if (seasonsOpen) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    seasons.forEach { number ->
                                        WideButton(
                                            text = "Season $number",
                                            selected = number == shownSeason,
                                            modifier = if (number == shownSeason) Modifier.focusRequester(currentSeasonFocus) else Modifier,
                                        ) {
                                            season = number
                                            highlighted = null
                                            seasonsOpen = false
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // The episode in view: badges, description, details.
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tmdb = described?.let { if (it.season == shownSeason) tmdbEpisodes[it.number] else null }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (described != null) Badge("SEASON ${described.season} · EPISODE ${described.number}")
                            info?.rating?.let { Label(text = "★ %.1f".format(it), style = AppType.title, color = colors.accent) }
                        }
                        val episodeTitle = tmdb?.name ?: described?.title?.takeIf { it.isNotBlank() && !it.equals(series.name, true) }
                        val text = listOfNotNull(
                            described?.let { "S${it.season} E${it.number}" + (episodeTitle?.let { t -> " · $t" } ?: "") },
                            tmdb?.overview ?: described?.plot ?: details?.plot ?: info?.overview,
                        ).joinToString("  ")
                        if (text.isNotEmpty()) {
                            Label(text = text, style = AppType.body.copy(fontSize = 15.sp, lineHeight = 21.sp), maxLines = 3)
                        }
                        val meta = listOfNotNull(
                            info?.genres?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString("  •  ")?.takeIf { it.isNotEmpty() },
                            info?.rating?.let { "TMDB %.1f/10".format(it) },
                            "${seasonEpisodes.size} episodes".takeIf { seasonEpisodes.isNotEmpty() },
                            info?.year?.toString(),
                        ).joinToString("  •  ")
                        if (meta.isNotEmpty()) Label(text = meta, style = AppType.label, color = colors.textDim, maxLines = 1)
                        if (problem != null) Label(text = problem.orEmpty(), style = AppType.body, color = colors.accent, maxLines = 2)
                    }

                    // Cast.
                    val cast = info?.peopleList()?.filter { !it.first }?.map { it.second.name }?.take(4).orEmpty()
                    if (cast.isNotEmpty()) {
                        Label(
                            text = "Cast: " + cast.joinToString(", "),
                            style = AppType.caption.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            color = colors.textDim,
                            maxLines = 4,
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }

            // ── The season's episodes, as picture cards ──
            Label(
                text = if (seasonEpisodes.isEmpty()) "" else "Season $shownSeason · ${seasonEpisodes.size} episodes",
                style = AppType.title,
                modifier = Modifier.padding(start = 48.dp, top = 6.dp, bottom = 6.dp),
            )
            val rowState = rememberLazyListState()
            LaunchedEffect(shownSeason, nextUp) {
                val index = seasonEpisodes.indexOfFirst { it.url == nextUp?.episode?.url }
                if (index > 0) rowState.scrollToItem(index)
            }
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 4.dp, bottom = 8.dp),
            ) {
                itemsIndexed(seasonEpisodes, key = { _, episode -> episode.url }) { index, episode ->
                    val tmdb = tmdbEpisodes[episode.number]
                    val saved = progress.firstOrNull { it.url == episode.url }
                    val isNext = episode.url == nextUp?.episode?.url
                    EpisodeCard(
                        episode = episode,
                        tmdb = tmdb,
                        fallbackImage = info?.backdrop ?: series.logo,
                        fraction = saved?.let { if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f } ?: 0f,
                        onFocus = { highlighted = episode },
                        onClick = { play(episode) },
                        modifier = if (isNext || (nextUp?.episode?.season != shownSeason && index == 0)) {
                            Modifier.focusRequester(episodeRowFocus)
                        } else {
                            Modifier
                        },
                    )
                }
            }

            // ── Cast and crew ──
            info?.let { details ->
                val people = details.peopleList()
                if (people.isNotEmpty()) {
                    Label(text = "Cast and crew", style = AppType.title, modifier = Modifier.padding(start = 48.dp, top = 10.dp))
                    PeopleRow(people = people, onPerson = onPerson, modifier = Modifier.fillMaxWidth().padding(start = 46.dp))
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    tmdb: TmdbEpisode?,
    fallbackImage: String?,
    fraction: Float,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    Column(modifier = Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(8.dp),
            container = Color(0xFF1B2A4A),
            outlined = false,
        ) { focused ->
            LaunchedEffect(focused) { if (focused) onFocus() }
            val image = tmdb?.still ?: fallbackImage
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (tmdb?.still == null) {
                // No still for this episode: the show's artwork, darkened, with the episode number on it.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                Label(text = "E${episode.number}", style = AppType.display.copy(fontSize = 30.sp), modifier = Modifier.align(Alignment.Center))
            }
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) { PlayGlyph(Color.White, Modifier.align(Alignment.Center).size(16.dp)) }
            }
            if (fraction > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.3f)),
                ) { Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(colors.accent)) }
            }
        }
        val name = tmdb?.name ?: episode.title
        Label(text = "${episode.number}. $name", style = AppType.title, maxLines = 1)
        val minutes = tmdb?.runtimeMinutes ?: episode.durationSecs?.takeIf { it > 0 }?.let { it / 60 }
        val meta = listOfNotNull(minutes?.let { "$it min" }, tmdb?.airDate?.take(4)).joinToString(" · ")
        if (meta.isNotEmpty()) Label(text = meta, style = AppType.caption, color = colors.textDim)
        (tmdb?.overview ?: episode.plot)?.let { Label(text = it, style = AppType.caption, color = colors.textDim, maxLines = 2) }
    }
}

@Composable
private fun WideButton(
    text: String,
    modifier: Modifier = Modifier,
    chevron: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(38.dp),
        container = if (selected) colors.glassStrong else Color(0xFF14213D).copy(alpha = 0.78f),
        focusedContainer = colors.accent,
        outlined = false,
        focusScale = 1.03f,
    ) { focused ->
        val ink = if (focused) colors.onAccent else colors.text
        Label(
            text = text,
            style = AppType.title,
            color = ink,
            modifier = Modifier.align(if (chevron) Alignment.CenterStart else Alignment.Center).padding(horizontal = 16.dp),
        )
        if (chevron) ChevronGlyph(ink, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(12.dp))
    }
}

@Composable
private fun RoundIconButton(icon: @Composable (Color) -> Unit, label: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            container = Color.Black.copy(alpha = 0.35f),
            focusedContainer = colors.accent,
            outlined = false,
        ) { focused ->
            Box(Modifier.align(Alignment.Center).size(16.dp)) { icon(if (focused) colors.onAccent else colors.text) }
        }
        Label(text = label, style = AppType.label, color = colors.textDim)
    }
}

@Composable
private fun Badge(text: String) {
    val colors = AppTheme.colors
    Label(
        text = text,
        style = AppType.caption.copy(fontSize = 11.sp, letterSpacing = 0.8.sp),
        color = colors.text,
        modifier = Modifier
            .border(1.dp, colors.text.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun PlayGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.15f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.15f, size.height)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
private fun ChevronGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.16f
        drawLine(tint, Offset(w * 0.1f, h * 0.3f), Offset(w * 0.5f, h * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.9f, h * 0.3f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun RestartGlyph(tint: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = size.width * 0.13f
        drawArc(
            color = tint,
            startAngle = -70f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(stroke, stroke),
            size = Size(size.width - 2 * stroke, size.height - 2 * stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val head = Path().apply {
            moveTo(size.width * 0.18f, 0f)
            lineTo(size.width * 0.5f, size.height * 0.06f)
            lineTo(size.width * 0.26f, size.height * 0.36f)
            close()
        }
        drawPath(head, tint)
    }
}
