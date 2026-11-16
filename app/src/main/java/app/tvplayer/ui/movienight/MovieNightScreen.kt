package app.tvplayer.ui.movienight

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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
import app.tvplayer.ui.components.LocalHoldFocus
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
    /** Titles already offered for these answers, left out of "Show me others". */
    val shown = HashSet<Long>()

    fun restart() {
        step = 0
        answers.clear()
        picks = null
        problem = null
        focusedPick = 0
    }
}

/** Which screen of Movie night is showing; used to animate between them. */
private sealed interface Scene {
    data object Intro : Scene
    data class Ask(val step: Int) : Scene
    data object Searching : Scene
    data object Results : Scene
    data object Problem : Scene
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
    /** Leaves Movie night for Home. */
    onHome: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val questions = MovieNight.questions
    // Each step replaces the last one's buttons, so focus is parked while it changes.
    val holdFocus = LocalHoldFocus.current
    // Quitting part-way, or Home from the shortlist: next time starts from the welcome screen.
    fun leave() {
        holdFocus()
        state.restart()
        state.step = -1
        onHome()
    }

    fun search(reroll: Boolean = false) {
        if (!reroll) state.shown.clear()
        state.searching = true
        state.problem = null
        scope.launch {
            try {
                val found = metadata.movieNight(state.answers.toMap(), watched.recent.value, exclude = state.shown.toSet())
                when {
                    found == null -> state.problem = "Movie night uses TMDB to find films and series. Add your free TMDB key in Settings › Film info."
                    found.isEmpty() && reroll -> state.problem = "That's everything in your playlists that fits those answers. Start again to try different ones."
                    found.isEmpty() -> state.problem = "Nothing in your playlists matched, even with looser choices. Try different answers."
                    else -> {
                        state.picks = found
                        state.focusedPick = 0
                    }
                }
            } catch (e: Exception) {
                state.problem = "Couldn't reach TMDB. Check the internet connection and try again."
            }
            // A moment on the "finding" screen even when the answer is quick, so the change isn't abrupt.
            delay(400)
            holdFocus()
            state.searching = false
        }
    }

    BackHandler(enabled = state.step > 0 || state.picks != null || state.problem != null) {
        holdFocus()
        when {
            state.picks != null || state.problem != null -> {
                state.picks = null
                state.problem = null
                state.step = questions.size - 1
            }
            else -> state.step--
        }
    }

    val scene: Scene = when {
        state.searching -> Scene.Searching
        state.problem != null -> Scene.Problem
        state.picks != null -> Scene.Results
        state.step < 0 -> Scene.Intro
        else -> Scene.Ask(state.step)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val picks = state.picks
        if (scene == Scene.Results && picks != null) {
            // The highlighted pick's artwork behind the shortlist.
            Crossfade(targetState = picks.getOrNull(state.focusedPick)?.backdrop, animationSpec = tween(500), label = "backdrop") { url ->
                if (url != null) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.55f))
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to AppTheme.colors.groundBottom, 0.55f to AppTheme.colors.groundBottom.copy(alpha = 0.6f), 1f to Color.Transparent),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.45f to Color.Transparent, 1f to AppTheme.colors.groundBottom),
                ),
            )
        } else {
            SoftLights()
        }

        AnimatedContent(
            targetState = scene,
            transitionSpec = {
                val from = initialState
                val to = targetState
                val duration = 380
                if (from is Scene.Ask && to is Scene.Ask) {
                    // Questions slide along: forwards to the left, Back to the right.
                    val forward = to.step > from.step
                    (slideInHorizontally(tween(duration, easing = FastOutSlowInEasing)) { w -> if (forward) w / 4 else -w / 4 } +
                        fadeIn(tween(duration))) togetherWith
                        (slideOutHorizontally(tween(duration, easing = FastOutSlowInEasing)) { w -> if (forward) -w / 4 else w / 4 } +
                            fadeOut(tween(duration / 2)))
                } else {
                    (slideInVertically(tween(duration + 100, easing = FastOutSlowInEasing)) { h -> h / 10 } + fadeIn(tween(duration + 100))) togetherWith
                        (slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { h -> -h / 12 } + fadeOut(tween(duration / 2)))
                }
            },
            label = "movieNight",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            when (shown) {
                Scene.Intro -> Intro {
                    holdFocus()
                    state.restart()
                }
                is Scene.Ask -> {
                    val question = questions[shown.step]
                    QuestionCard(
                        number = shown.step + 1,
                        total = questions.size,
                        question = question.text,
                        choices = question.choices.map { it.id to it.label },
                        selected = state.answers[question.id],
                        key = question.id,
                        onQuit = ::leave,
                    ) { choice ->
                        state.answers[question.id] = choice
                        holdFocus()
                        if (shown.step + 1 < questions.size) state.step = shown.step + 1 else search()
                    }
                }
                Scene.Searching -> Searching()
                Scene.Problem -> Message(state.problem.orEmpty(), "Start again") {
                    holdFocus()
                    state.restart()
                }
                Scene.Results -> picks?.let {
                    Shortlist(
                        state,
                        it,
                        onOpen = onOpen,
                        onRestart = {
                            holdFocus()
                            state.restart()
                        },
                        onHome = ::leave,
                        onReroll = {
                            holdFocus()
                            state.shown += it.map { pick -> pick.row.id }
                            search(reroll = true)
                        },
                    )
                }
            }
        }
    }
}

/** Two soft coloured lights drifting slowly behind the quiz, like a cinema before the film. */
@Composable
private fun SoftLights() {
    val colors = AppTheme.colors
    val drift = rememberInfiniteTransition(label = "lights")
    val t by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t",
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawCircle(
            brush = Brush.radialGradient(
                listOf(colors.accent.copy(alpha = 0.20f), Color.Transparent),
                center = Offset(w * (0.25f + 0.15f * t), h * (0.3f + 0.1f * t)),
                radius = h * 0.75f,
            ),
            radius = h * 0.75f,
            center = Offset(w * (0.25f + 0.15f * t), h * (0.3f + 0.1f * t)),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xFFE8A33D).copy(alpha = 0.13f), Color.Transparent),
                center = Offset(w * (0.8f - 0.12f * t), h * (0.75f - 0.12f * t)),
                radius = h * 0.65f,
            ),
            radius = h * 0.65f,
            center = Offset(w * (0.8f - 0.12f * t), h * (0.75f - 0.12f * t)),
        )
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    val colors = AppTheme.colors
    val start = remember { FocusRequester() }
    EntryPoint(start)
    AutoFocusOnce(start)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clapperboard(tint = colors.accent, modifier = Modifier.size(width = 64.dp, height = 56.dp))
        Spacer(Modifier.height(18.dp))
        Label(text = "MOVIE NIGHT", style = AppType.label.copy(letterSpacing = 3.sp), color = colors.accent)
        Spacer(Modifier.height(6.dp))
        Label(text = "Can't decide what to watch?", style = AppType.display.copy(fontSize = 34.sp, lineHeight = 40.sp))
        Spacer(Modifier.height(10.dp))
        Label(
            text = "Ten quick questions, then a shortlist from your own playlists",
            style = AppType.body.copy(fontSize = 16.sp),
            color = colors.textDim,
        )
        Label(
            text = "with ratings, running times and how each one differs.",
            style = AppType.body.copy(fontSize = 16.sp),
            color = colors.textDim,
        )
        Spacer(Modifier.height(26.dp))
        // A slow glow around the button invites the first press.
        val glow = rememberInfiniteTransition(label = "glow")
        val pulse by glow.animateFloat(0.2f, 0.55f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "pulse")
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 200.dp, height = 66.dp)
                    .background(
                        Brush.radialGradient(listOf(colors.accent.copy(alpha = pulse), Color.Transparent)),
                        RoundedCornerShape(33.dp),
                    ),
            )
            BigButton("Let's start", modifier = Modifier.focusRequester(start).widthIn(min = 170.dp), onClick = onStart)
        }
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
    /** Leaves the quiz part-way. */
    onQuit: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val first = remember(key) { FocusRequester() }
    EntryPoint(first)
    AutoFocusOnce(first)
    val scope = rememberCoroutineScope()
    // The answer just chosen lights up for a moment before the next question slides in.
    var chosen by remember(key) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 60.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(text = "MOVIE NIGHT  ·  QUESTION $number OF $total", style = AppType.label.copy(letterSpacing = 2.sp), color = colors.accent)
        Spacer(Modifier.height(12.dp))
        // Progress: fills smoothly from the last question's point.
        val progress by animateFloatAsState(number / total.toFloat(), tween(600, easing = FastOutSlowInEasing), label = "progress")
        Box(
            Modifier
                .width(360.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.14f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(Brush.horizontalGradient(listOf(colors.accent.copy(alpha = 0.7f), colors.accent))),
            )
        }
        Spacer(Modifier.height(30.dp))
        Label(text = question, style = AppType.display.copy(fontSize = 32.sp, lineHeight = 38.sp), maxLines = 2)
        Spacer(Modifier.height(30.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.widthIn(max = 780.dp),
        ) {
            choices.forEachIndexed { index, (id, label) ->
                val isFirst = if (selected != null) id == selected else index == 0
                // Answers rise into place one after another.
                val appear = remember(key) { Animatable(0f) }
                LaunchedEffect(key) {
                    delay(90L + index * 55L)
                    appear.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                }
                val pop by animateFloatAsState(if (chosen == id) 1.08f else 1f, tween(160), label = "pop")
                BigButton(
                    text = label,
                    highlighted = id == selected || id == chosen,
                    modifier = Modifier
                        .then(if (isFirst) Modifier.focusRequester(first) else Modifier)
                        .alpha(appear.value)
                        .offset(y = (14 * (1 - appear.value)).dp)
                        .scale(pop),
                ) {
                    if (chosen == null) {
                        chosen = id
                        scope.launch {
                            delay(230)
                            onChoose(id)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        QuitButton(onQuit)
        Spacer(Modifier.height(12.dp))
        Label(
            text = if (number > 1) "Back for the previous question" else "Back to leave Movie night",
            style = AppType.caption.copy(fontSize = 12.sp),
            color = colors.textFaint,
        )
    }
}

/** A small, quiet button under the answers, so it's never chosen by accident. */
@Composable
private fun QuitButton(onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(17.dp),
        container = Color.Transparent,
        focusedContainer = colors.glassStrong,
    ) { focused ->
        Label(
            text = "Quit quiz",
            style = AppType.label,
            color = if (focused) colors.text else colors.textDim,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
        )
    }
}

/** While TMDB is asked: a turning film reel and changing words. */
@Composable
private fun Searching() {
    val colors = AppTheme.colors
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "angle")
    val lines = listOf("Checking your playlists…", "Comparing ratings…", "Weighing up running times…", "Picking the best few…")
    var line by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_300)
            line = (line + 1) % lines.size
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilmReel(tint = colors.accent, angle = angle, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(22.dp))
        Label(text = "Finding something to watch", style = AppType.display.copy(fontSize = 28.sp, lineHeight = 34.sp))
        Spacer(Modifier.height(8.dp))
        Crossfade(targetState = lines[line], animationSpec = tween(350), label = "line") { text ->
            Label(text = text, style = AppType.body.copy(fontSize = 16.sp), color = colors.textDim)
        }
    }
}

@Composable
private fun Shortlist(
    state: MovieNightState,
    picks: List<MoviePick>,
    onOpen: (VodRow) -> Unit,
    onRestart: () -> Unit,
    /** A fresh shortlist for the same answers. */
    onReroll: () -> Unit,
    onHome: () -> Unit,
) {
    val colors = AppTheme.colors
    val focused = picks.getOrNull(state.focusedPick) ?: picks.first()
    val firstPoster = remember { FocusRequester() }
    EntryPoint(firstPoster)
    AutoFocusOnce(firstPoster)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize().padding(start = 48.dp, top = 28.dp, end = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Clapperboard(tint = colors.accent, modifier = Modifier.size(width = 22.dp, height = 19.dp))
            Label(text = "MOVIE NIGHT  ·  YOUR SHORTLIST", style = AppType.label.copy(letterSpacing = 2.sp), color = colors.accent)
        }
        // The highlighted pick's details change with a gentle fade.
        // Every line has a fixed height, so the posters below never move whatever the length of the description.
        Crossfade(targetState = focused, animationSpec = tween(220), label = "pick") { pick ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(720.dp).height(DetailsHeight)) {
                Label(text = pick.title, style = AppType.display.copy(fontSize = 34.sp, lineHeight = 40.sp), maxLines = 1, modifier = Modifier.height(40.dp))
                val meta = listOfNotNull(
                    if (pick.isSeries) "Series" else "Film",
                    pick.year?.toString(),
                    pick.rating?.let { "★ %.1f".format(it) },
                    when {
                        pick.isSeries && pick.seasons != null -> if (pick.seasons == 1) "1 season" else "${pick.seasons} seasons"
                        pick.runtimeMinutes != null -> MovieNight.hours(pick.runtimeMinutes)
                        else -> null
                    },
                    pick.genres.take(3).joinToString(", ").takeIf { it.isNotEmpty() },
                ).joinToString("  ·  ")
                Label(text = meta, style = AppType.title.copy(fontSize = 16.sp), color = colors.accent, maxLines = 1)
                Box(modifier = Modifier.height(63.dp)) {
                    Label(text = pick.overview ?: "No description available.", style = AppType.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = colors.textDim, maxLines = 3)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(30.dp)) {
                    pick.differences.forEach { note -> Chip(note) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
        ) {
            itemsIndexed(picks, key = { _, pick -> pick.row.id }) { index, pick ->
                // Posters deal in one after another.
                val appear = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(120L + index * 70L)
                    appear.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
                }
                PosterCard(
                    row = pick.row.copy(logo = pick.poster ?: pick.row.logo, name = pick.title),
                    onClick = { onOpen(pick.row) },
                    onFocus = { state.focusedPick = index },
                    modifier = Modifier
                        .width(118.dp)
                        .alpha(appear.value)
                        .offset(y = (20 * (1 - appear.value)).dp)
                        .then(if (index == 0) Modifier.focusRequester(firstPoster) else Modifier),
                )
            }
            item(key = "again") {
                Column(
                    modifier = Modifier.height(177.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    BigButton("Show me others", onClick = onReroll)
                    BigButton("Start again", onClick = onRestart)
                    BigButton("Home", onClick = onHome)
                }
            }
        }
        Label(text = "OK on a title for its page · Back to change your answers", style = AppType.caption, color = colors.textFaint)
    }
}

@Composable
private fun Chip(text: String) {
    val colors = AppTheme.colors
    Label(
        text = text,
        style = AppType.label.copy(fontSize = 13.sp),
        color = colors.text,
        modifier = Modifier
            .background(colors.glassStrong, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun Message(text: String, button: String, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val focus = remember { FocusRequester() }
    EntryPoint(focus)
    AutoFocusOnce(focus)
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Clapperboard(tint = colors.accent, modifier = Modifier.size(width = 48.dp, height = 42.dp))
        Spacer(Modifier.height(14.dp))
        Box(modifier = Modifier.widthIn(max = 620.dp)) {
            Label(text = text, style = AppType.title.copy(fontSize = 18.sp, lineHeight = 24.sp), maxLines = 4)
        }
        Spacer(Modifier.height(20.dp))
        BigButton(button, modifier = Modifier.focusRequester(focus), onClick = onClick)
    }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, highlighted: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(26.dp),
        container = if (highlighted) colors.accent.copy(alpha = 0.35f) else Color(0xFF14213D).copy(alpha = 0.75f),
        focusedContainer = colors.accent,
        focusScale = 1.06f,
    ) { focused ->
        Label(
            text = text,
            style = AppType.title.copy(fontSize = 17.sp),
            color = if (focused) colors.onAccent else colors.text,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 26.dp),
        )
    }
}

/** A clapperboard, drawn. */
@Composable
private fun Clapperboard(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.06f
        // Board.
        drawRoundRect(tint, topLeft = Offset(0f, h * 0.36f), size = Size(w, h * 0.64f), cornerRadius = CornerRadius(w * 0.06f), style = Stroke(stroke))
        // Clapper, tilted open.
        val top = Path().apply {
            moveTo(0f, h * 0.30f)
            lineTo(w * 0.94f, h * 0.02f)
            lineTo(w, h * 0.20f)
            lineTo(w * 0.06f, h * 0.48f)
            close()
        }
        drawPath(top, tint)
        // Stripes on the clapper.
        for (i in 1..3) {
            val f = i * 0.25f
            val top = Offset(w * 0.94f * f, h * (0.30f - 0.28f * f))
            drawLine(Color.Black.copy(alpha = 0.5f), top, Offset(top.x + w * 0.06f, top.y + h * 0.18f), strokeWidth = w * 0.06f)
        }
        // Lines on the board.
        drawLine(tint, Offset(w * 0.15f, h * 0.62f), Offset(w * 0.85f, h * 0.62f), strokeWidth = stroke * 0.7f)
        drawLine(tint, Offset(w * 0.15f, h * 0.80f), Offset(w * 0.6f, h * 0.80f), strokeWidth = stroke * 0.7f)
    }
}

/** A film reel, turning. */
@Composable
private fun FilmReel(tint: Color, angle: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(tint, radius = r * 0.95f, center = c, style = Stroke(width = r * 0.1f))
        drawCircle(tint, radius = r * 0.14f, center = c)
        for (i in 0 until 6) {
            val a = Math.toRadians((angle + i * 60f).toDouble())
            val hole = Offset(c.x + (r * 0.52f * kotlin.math.cos(a)).toFloat(), c.y + (r * 0.52f * kotlin.math.sin(a)).toFloat())
            drawCircle(tint.copy(alpha = 0.85f), radius = r * 0.19f, center = hole)
        }
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

/** The shortlist's details block: title, details line, three lines of description and the notes. */
private val DetailsHeight = 40.dp + 22.dp + 63.dp + 30.dp + 8.dp * 3
