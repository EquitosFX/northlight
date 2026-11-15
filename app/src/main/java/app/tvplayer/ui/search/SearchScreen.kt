package app.tvplayer.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
) {
    val colors = AppTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    EntryPoint(fieldFocus)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) { if (autoFocus()) runCatching { fieldFocus.requestFocus() } }

    // Listens in the app through the remote's mic; the words fill the search box as they're heard.
    val voice = rememberVoiceInput { words -> query = words }
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

        val list = results
        val status = when {
            text.length < 2 -> "Type at least two letters."
            list == null -> "Searching…"
            list.isEmpty() && people.isEmpty() -> "Nothing found for \"$text\"."
            else -> listOfNotNull(
                list.size.takeIf { it > 0 }?.let { if (it == 1) "1 title" else "$it titles" },
                people.size.takeIf { it > 0 }?.let { if (it == 1) "1 person" else "$it people" },
            ).joinToString(" · ")
        }
        Label(text = status, style = AppType.caption, color = colors.textDim)

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(people, key = { "person${it.id}" }) { person ->
                FocusSurface(
                    onClick = { onPerson(person) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    focusScale = 1.01f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonPhoto(person = person, modifier = Modifier.size(34.dp))
                        Label(text = person.name, style = AppType.title, modifier = Modifier.weight(1f))
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
                            color = colors.textDim,
                        )
                    }
                }
            }
            items(list.orEmpty(), key = { it.id }) { row ->
                FocusSurface(
                    onClick = { onOpen(row) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    focusScale = 1.01f,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Logo(url = row.logo, modifier = Modifier.size(width = 44.dp, height = 32.dp))
                        Label(text = row.name, style = AppType.title, modifier = Modifier.weight(1f))
                        Label(
                            text = when (row.kind) {
                                ChannelEntity.KIND_LIVE -> "Live channel"
                                ChannelEntity.KIND_MOVIE -> "Film"
                                else -> "Series"
                            },
                            style = AppType.caption,
                            color = colors.textDim,
                        )
                    }
                }
            }
        }
    }
}
