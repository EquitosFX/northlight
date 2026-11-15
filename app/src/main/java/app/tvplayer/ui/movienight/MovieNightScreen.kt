package app.tvplayer.ui.movienight

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.tvplayer.core.movienight.MovieNight
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.MoviePick
import app.tvplayer.data.WatchedTitles
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.vod.PosterCard
import kotlinx.coroutines.launch

/** Kept by the app so the shortlist is still there after opening one of its titles and coming back. */
class MovieNightState {
    var step by mutableIntStateOf(-1)
    val answers = mutableStateMapOf<String, String>()
    var picks by mutableStateOf<List<MoviePick>?>(null)
    var searching by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    var focusedPick by mutableIntStateOf(0)

    fun restart() {
        step = 0
        answers.clear()
        picks = null
        problem = null
        focusedPick = 0
    }
}

/**
 * Movie night: ten quick questions, then a shortlist of films and series from the viewer's own
 * playlists, with what each is like and how they differ.
 */
@Composable
fun MovieNightScreen(
    state: MovieNightState,
    metadata: MetadataRepository,
    watched: WatchedTitles,
    onOpen: (VodRow) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val questions = MovieNight.questions

    fun search() {
        state.searching = true
        state.problem = null
        scope.launch {
            try {
                val found = metadata.movieNight(state.answers.toMap(), watched.recent.value)
                when {
                    found == null -> state.problem = "Movie night uses TMDB to find films and series. Add your free TMDB key in Settings › Film info."
                    found.isEmpty() -> state.problem = "Nothing in your playlists matched, even with looser choices. Try different answers."
                    else -> {
                        state.picks = found
                        state.focusedPick = 0
                    }
                }
            } catch (e: Exception) {
                state.problem = "Couldn't reach TMDB. Check the internet connection and try again."
            }
            state.searching = false
        }
    }

    // Back steps through the questions; from the shortlist it goes back to the last question.
    BackHandler(enabled = state.step > 0 || state.picks != null || state.problem != null) {
        when {
            state.picks != null || state.problem != null -> {
                state.picks = null
                state.problem = null
                state.step = questions.size - 1
            }
            else -> state.step--
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 8.dp)) {
        val picks = state.picks
        val problem = state.problem
        when {
            state.searching -> Centered("Finding something to watch…", "Checking TMDB's suggestions against your playlists.")
            problem != null -> Message(problem, "Start again") { state.restart() }
            picks != null -> Shortlist(state, picks, onOpen = onOpen, onRestart = { state.restart() })
            state.step < 0 -> Intro { state.restart() }
            else -> {
                val question = questions[state.step]
                QuestionCard(
                    number = state.step + 1,
                    total = questions.size,
                    question = question.text,
                    choices = question.choices.map { it.id to it.label },
                    selected = state.answers[question.id],
                    key = question.id,
                ) { choice ->
                    state.answers[question.id] = choice
                    if (state.step + 1 < questions.size) state.step++ else search()
                }
            }
        }
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    val colors = AppTheme.colors
    val start = remember { FocusRequester() }
    EntryPoint(start)
    AutoFocusOnce(start)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 24.dp).width(560.dp)) {
        Label(text = "MOVIE NIGHT", style = AppType.caption, color = colors.accent)
        Label(text = "Can't decide what to watch?", style = AppType.headline)
        Label(
            text = "Answer ten quick questions and Skyfall picks a shortlist from your playlists, with what each one is " +
                "like, its rating and running time, and how they differ.",
            style = AppType.body,
            color = colors.textDim,
            maxLines = 4,
        )
        Spacer(Modifier.height(8.dp))
        BigButton("Start", modifier = Modifier.focusRequester(start), onClick = onStart)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    number: Int,
    total: Int,
    question: String,
    choices: List<Pair<String, String>>,
    selected: String?,
    key: String,
    onChoose: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val first = remember(key) { FocusRequester() }
    EntryPoint(first)
    AutoFocusOnce(first)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 24.dp)) {
        Label(text = "MOVIE NIGHT  ·  QUESTION $number OF $total", style = AppType.caption, color = colors.accent)
        // A row of dots showing progress through the questions.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .width(22.dp)
                        .height(4.dp)
                        .padding(0.dp)
                        .then(Modifier),
                ) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color = if (i < number) colors.accent else colors.textFaint.copy(alpha = 0.4f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                        )
                    }
                }
            }
        }
        Label(text = question, style = AppType.headline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, (id, label) ->
                val isFirst = if (selected != null) id == selected else index == 0
                BigButton(
                    text = label,
                    highlighted = id == selected,
                    modifier = if (isFirst) Modifier.focusRequester(first) else Modifier,
                ) { onChoose(id) }
            }
        }
        if (number > 1) Label(text = "Back for the previous question", style = AppType.caption, color = colors.textFaint)
    }
}

@Composable
private fun Shortlist(state: MovieNightState, picks: List<MoviePick>, onOpen: (VodRow) -> Unit, onRestart: () -> Unit) {
    val colors = AppTheme.colors
    val focused = picks.getOrNull(state.focusedPick) ?: picks.first()
    val firstPoster = remember { FocusRequester() }
    EntryPoint(firstPoster)
    AutoFocusOnce(firstPoster)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Label(text = "MOVIE NIGHT  ·  YOUR SHORTLIST", style = AppType.caption, color = colors.accent)
        Label(text = focused.title, style = AppType.headline, maxLines = 1)
        val meta = listOfNotNull(
            if (focused.isSeries) "Series" else "Film",
            focused.year?.toString(),
            focused.rating?.let { "★ %.1f".format(it) },
            when {
                focused.isSeries && focused.seasons != null -> if (focused.seasons == 1) "1 season" else "${focused.seasons} seasons"
                focused.runtimeMinutes != null -> MovieNight.hours(focused.runtimeMinutes)
                else -> null
            },
            focused.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() },
        ).joinToString("  ·  ")
        Label(text = meta, style = AppType.title, color = colors.accent, maxLines = 1)
        Label(text = focused.overview ?: "No description available.", style = AppType.body, color = colors.textDim, maxLines = 3)
        // How this pick differs from the others.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            focused.differences.forEach { note -> Chip(note) }
        }
        Spacer(Modifier.height(2.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
        ) {
            itemsIndexed(picks, key = { _, pick -> pick.row.id }) { index, pick ->
                PosterCard(
                    row = pick.row.copy(logo = pick.poster ?: pick.row.logo, name = pick.title),
                    onClick = { onOpen(pick.row) },
                    onFocus = { state.focusedPick = index },
                    modifier = Modifier
                        .width(118.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstPoster) else Modifier),
                )
            }
            item(key = "again") {
                Box(modifier = Modifier.height(177.dp), contentAlignment = Alignment.Center) {
                    BigButton("Start again", onClick = onRestart)
                }
            }
        }
        Label(text = "OK on a title for its page · Back to change your answers", style = AppType.caption, color = colors.textFaint)
    }
}

@Composable
private fun Chip(text: String) {
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .then(Modifier.padding(0.dp)),
    ) {
        Label(
            text = text,
            style = AppType.label,
            color = colors.text,
            modifier = Modifier
                .background(colors.glassStrong, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun Centered(title: String, subtitle: String) {
    val colors = AppTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(text = title, style = AppType.headline)
        Label(text = subtitle, style = AppType.body, color = colors.textDim)
    }
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val focus = remember { FocusRequester() }
    EntryPoint(focus)
    AutoFocusOnce(focus)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 24.dp).width(600.dp)) {
        Label(text = "MOVIE NIGHT", style = AppType.caption, color = colors.accent)
        Label(text = text, style = AppType.title, maxLines = 4)
        BigButton(button, modifier = Modifier.focusRequester(focus), onClick = onClick)
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        container = if (highlighted) colors.glassStrong else null,
        focusedContainer = colors.accent,
    ) { focused ->
        Label(
            text = text,
            style = AppType.title,
            color = if (focused) colors.onAccent else colors.text,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 22.dp),
        )
    }
}

@Composable
private fun AutoFocusOnce(requester: FocusRequester) {
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(requester) {
        withFrameNanos { }
        if (autoFocus()) runCatching { requester.requestFocus() }
    }
}
