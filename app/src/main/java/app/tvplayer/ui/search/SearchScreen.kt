package app.tvplayer.ui.search

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
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.components.TvTextField
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import kotlinx.coroutines.delay

/** Search across live channels, films and series by name. Results update as you type. */
@Composable
fun SearchScreen(channels: ChannelsRepository, onOpen: (VodRow) -> Unit) {
    val colors = AppTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text = "Search", style = AppType.headline)
        TvTextField(
            value = query,
            onValueChange = { query = it },
            label = "Channels, films and series",
            placeholder = "Press OK to type",
            modifier = Modifier
                .width(430.dp)
                .focusRequester(fieldFocus),
        )

        val list = results
        val status = when {
            text.length < 2 -> "Type at least two letters."
            list == null -> "Searching…"
            list.isEmpty() -> "Nothing found for \"$text\"."
            else -> "${list.size} results"
        }
        Label(text = status, style = AppType.caption, color = colors.textDim)

        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp, start = 2.dp, end = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
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
