package app.tvplayer.ui.search

import app.tvplayer.ui.components.entersAt
import androidx.compose.ui.graphics.Color
import app.tvplayer.ui.vod.PosterCard
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import app.tvplayer.ui.components.EntryPoint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.MetadataRepository
import app.tvplayer.ui.person.PersonPhoto
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.components.TvTextField
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.delay

/** Search across live channels, films and series by name. Results update as you type. */
@Composable
fun SearchScreen(
    channels: ChannelsRepository,
    metadata: MetadataRepository,
    onOpen: (VodRow) -> Unit,
    onPerson: (TmdbPerson) -> Unit,
    voiceToken: Int = 0,
    /** The search kept while a result is open, so coming back finds it as it was. */
    memory: SearchMemory = remember { SearchMemory() },
) {
    val colors = AppTheme.colors
    var query by remember { mutableStateOf(memory.query) }
    LaunchedEffect(query) { memory.query = query }
    // Back from a result: highlight it again (once the results are back), rather than the search box.
    val returnKey = remember { memory.opened.also { memory.opened = null } }
    val fieldFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }
    if (returnKey == null) EntryPoint(fieldFocus)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) { if (returnKey == null && autoFocus()) runCatching { fieldFocus.requestFocus() } }
    fun opened(key: String) {
        memory.opened = key
    }

    // Listens in the app through the remote's mic; the words fill the search box as they're heard.
    // Searches asked for (typed and finished, said, or a result opened), most recent first, kept on this box.
    val history = rememberSearchHistory()
    val voice = rememberVoiceInput(onWords = { words -> query = words }, onFinal = { words -> history.add(words) })
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start() else voice.problem = "Allow the microphone to search by voice."
    }
    fun listen() {
        if (voice.listening) {
            voice.stop()
        } else if (voice.hasPermission()) {
            voice.start()
        } else {
            askMic.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(voiceToken) { if (voiceToken > 0) listen() }

    val text = query.trim()
    val results by produceState<List<VodRow>?>(initialValue = emptyList(), text) {
        if (text.length < 2) {
            value = emptyList()
            return@produceState
        }
        value = null
        delay(300) // wait for typing to pause
        value = channels.search(text)
    }

    // Actors, directors and writers, from TMDB when a key is set.
    val people by produceState<List<TmdbPerson>>(initialValue = emptyList(), text) {
        if (text.length < 3) {
            value = emptyList()
            return@produceState
        }
        delay(400)
        value = metadata.searchPeople(text)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text = "Search", style = AppType.headline)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                onDone = { history.add(query) },
                label = "Channels, films, series and people",
                placeholder = "Press OK to type",
                modifier = Modifier
                    .width(430.dp)
                    .focusRequester(fieldFocus),
            )
            FocusSurface(
                onClick = { listen() },
                modifier = Modifier.size(42.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                container = if (voice.listening) colors.accent else colors.glass,
                focusedContainer = colors.accent,
            ) { focused ->
                MicIcon(
                    tint = if (focused || voice.listening) colors.onAccent else colors.text,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(22.dp),
                )
            }
        }
        when {
            voice.listening -> Label(text = "Listening… say a channel, film, series or person", style = AppType.body, color = colors.accent)
            voice.problem != null -> Label(text = voice.problem.orEmpty(), style = AppType.body, color = colors.accent)
        }

        // With nothing typed: the searches made before, to search again with OK.
        if (text.length < 2 && history.items.isNotEmpty()) {
            HistoryPills(
                items = history.items,
                onPick = { picked ->
                    query = picked
                    history.add(picked)
                    // The pills make way for the results: focus goes back to the search box.
                    runCatching { fieldFocus.requestFocus() }
                },
                onClear = {
                    history.clear()
                    runCatching { fieldFocus.requestFocus() }
                },
            )
        }

        val list = results
        val status = when {
            text.length < 2 -> if (history.items.isEmpty()) "Type at least two letters." else ""
            list == null -> "Searching…"
            list.isEmpty() && people.isEmpty() -> "Nothing found for \"$text\"."
            else -> listOfNotNull(
                list.size.takeIf { it > 0 }?.let { if (it == 1) "1 title" else "$it titles" },
                people.size.takeIf { it > 0 }?.let { if (it == 1) "1 person" else "$it people" },
            ).joinToString(" · ")
        }
        Label(text = status, style = AppType.caption, color = colors.textDim)

        // Results as tiles: films and series as posters (OK opens their page), people, then live channels.
        val titles = list.orEmpty().filter { it.kind != ChannelEntity.KIND_LIVE }
        val liveChannels = list.orEmpty().filter { it.kind == ChannelEntity.KIND_LIVE }
        val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        // The grid's items in order, to find a result's place in it.
        val keys = remember(titles, people, liveChannels) {
            buildList {
                if (titles.isNotEmpty()) { add("h-titles"); titles.forEach { add("t${it.id}") } }
                if (people.isNotEmpty()) { add("h-people"); people.forEach { add("p${it.id}") } }
                if (liveChannels.isNotEmpty()) { add("h-live"); liveChannels.forEach { add("c${it.id}") } }
            }
        }
        // New results always start at the top (films and series first), not wherever the grid had got to.
        var returned by remember { mutableStateOf(returnKey == null) }
        LaunchedEffect(keys) {
            if (keys.isEmpty()) return@LaunchedEffect
            if (!returned) {
                val at = keys.indexOf(returnKey)
                if (at < 0) {
                    if (list != null) returned = true
                    return@LaunchedEffect
                }
                returned = true
                gridState.scrollToItem((at - 1).coerceAtLeast(0))
                repeat(30) {
                    androidx.compose.runtime.withFrameNanos { }
                    if (runCatching { returnFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                }
                return@LaunchedEffect
            }
            gridState.scrollToItem(0)
        }
        fun tileFocus(key: String) = if (key == returnKey) Modifier.focusRequester(returnFocus) else Modifier
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 118.dp),
            contentPadding = PaddingValues(bottom = 24.dp, start = 2.dp, end = 2.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (titles.isNotEmpty()) {
                item(key = "h-titles", span = { GridItemSpan(maxLineSpan) }) { SectionHeading("Films and series") }
                items(titles, key = { "t${it.id}" }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PosterCard(
                            row = row,
                            onClick = {
                                history.add(text)
                                opened("t${row.id}")
                                onOpen(row)
                            },
                            modifier = Modifier.fillMaxWidth().then(tileFocus("t${row.id}")),
                        )
                        Label(
                            text = if (row.kind == ChannelEntity.KIND_MOVIE) "Film" else "Series",
                            style = AppType.caption,
                            color = colors.textFaint,
                        )
                    }
                }
            }
            if (people.isNotEmpty()) {
                item(key = "h-people", span = { GridItemSpan(maxLineSpan) }) { SectionHeading("People") }
                items(people, key = { "p${it.id}" }) { person ->
                    PersonTile(person = person, onClick = { history.add(text); onPerson(person) })
                }
            }
            if (liveChannels.isNotEmpty()) {
                item(key = "h-live", span = { GridItemSpan(maxLineSpan) }) { SectionHeading("Live channels") }
                items(liveChannels, key = { "c${it.id}" }) { row ->
                    ChannelTile(
                        row = row,
                        onClick = {
                            history.add(text)
                            opened("c${row.id}")
                            onOpen(row)
                        },
                        modifier = tileFocus("c${row.id}"),
                    )
                }
            }
        }
    }
}

/** The search in progress, kept while a result is open (the search screen itself is rebuilt each time). */
class SearchMemory {
    var query: String = ""
    /** The result opened, to highlight again on coming back. */
    var opened: String? = null
}

/** The searches made before, kept on this box (the most recent twelve). */
@androidx.compose.runtime.Stable
class SearchHistory(private val prefs: android.content.SharedPreferences) {
    var items by mutableStateOf(prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
        private set

    fun add(search: String) {
        val clean = search.trim().replace('\n', ' ')
        if (clean.length < 2) return
        items = (listOf(clean) + items.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
        prefs.edit().putString(KEY, items.joinToString("\n")).apply()
    }

    fun clear() {
        items = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "searches"
        const val MAX = 12
    }
}

@Composable
private fun rememberSearchHistory(): SearchHistory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember { SearchHistory(context.getSharedPreferences("search_history", android.content.Context.MODE_PRIVATE)) }
}

/** Earlier searches as pills: OK searches again. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryPills(items: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    val colors = AppTheme.colors
    // Down from the search box lands on the most recent search, not whichever pill is nearest.
    val entry = remember { app.tvplayer.ui.components.EntryFocus() }
    val firstPill = remember { androidx.compose.ui.focus.FocusRequester() }
    app.tvplayer.ui.components.EntryPoint(firstPill, entry)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(text = "Recent searches", style = AppType.title)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.entersAt(entry),
        ) {
            for ((index, item) in items.withIndex()) {
                FocusSurface(
                    onClick = { onPick(item) },
                    modifier = Modifier
                        .height(36.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstPill) else Modifier),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    container = colors.glass,
                    focusedContainer = colors.accent,
                    outlined = false,
                ) { focused ->
                    Row(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HistoryClock(tint = if (focused) colors.onAccent else colors.textDim, modifier = Modifier.size(14.dp))
                        Label(text = item, style = AppType.label, color = if (focused) colors.onAccent else colors.text, maxLines = 1)
                    }
                }
            }
            FocusSurface(
                onClick = onClear,
                modifier = Modifier.height(36.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                container = Color.Transparent,
                focusedContainer = colors.glassStrong,
                outlined = false,
            ) { focused ->
                Label(
                    text = "Clear history",
                    style = AppType.label,
                    color = if (focused) colors.text else colors.textFaint,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** A small clock: "searched before". */
@Composable
private fun HistoryClock(tint: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.width * 0.12f
        drawCircle(tint, radius = size.width / 2 - stroke / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        val c = center
        drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x, size.height * 0.24f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, c, androidx.compose.ui.geometry.Offset(size.width * 0.72f, c.y), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun SectionHeading(text: String) {
    Label(text = text, style = AppType.title, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun PersonTile(person: TmdbPerson, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = androidx.compose.foundation.shape.CircleShape,
            outlined = false,
        ) {
            PersonPhoto(person = person, modifier = Modifier.fillMaxSize())
        }
        Label(text = person.name, style = AppType.label, maxLines = 2)
        Label(
            text = when (person.role) {
                "Acting" -> "Actor"
                "Directing" -> "Director"
                "Writing" -> "Writer"
                "Production" -> "Producer"
                null -> "Person"
                else -> person.role ?: "Person"
            },
            style = AppType.caption,
            color = colors.textFaint,
        )
    }
}

@Composable
private fun ChannelTile(row: VodRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FocusSurface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            outlined = false,
        ) {
            Logo(url = row.logo, modifier = Modifier.align(Alignment.Center).fillMaxSize(0.7f))
        }
        Label(text = row.name, style = AppType.label, maxLines = 2)
        Label(text = "Live channel", style = AppType.caption, color = colors.textFaint)
    }
}
