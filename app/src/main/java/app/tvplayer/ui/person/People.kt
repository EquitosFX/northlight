package app.tvplayer.ui.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tvplayer.core.tmdb.TmdbPerson
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.vod.PosterCard
import coil.compose.AsyncImage

/** A round photo, or the person's initials when there isn't one. */
@Composable
fun PersonPhoto(person: TmdbPerson, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val initials = person.name.split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }
        Label(text = initials, style = AppType.label, color = AppTheme.colors.textDim)
        if (person.photo != null) {
            AsyncImage(
                model = person.photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Directors and cast as a row of selectable names. [people] pairs are (is director, person). */
@Composable
fun PeopleRow(people: List<Pair<Boolean, TmdbPerson>>, onPerson: (TmdbPerson) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    if (people.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
    ) {
        items(people, key = { (director, person) -> "$director${person.id}" }) { (director, person) ->
            FocusSurface(
                onClick = { onPerson(person) },
                modifier = Modifier.height(44.dp),
                shape = RoundedCornerShape(22.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 4.dp, end = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonPhoto(person = person, modifier = Modifier.size(36.dp))
                    Column(modifier = Modifier.width(112.dp)) {
                        Label(text = person.name, style = AppType.label)
                        val role = if (director) person.role ?: "Director" else person.role
                        if (role != null) Label(text = role, style = AppType.caption, color = colors.textDim)
                    }
                }
            }
        }
    }
}

/** A person's page: who they are, and every film and series of theirs in the viewer's playlists. */
@Composable
fun PersonScreen(person: TmdbPerson, metadata: MetadataRepository, onOpen: (VodRow) -> Unit) {
    val colors = AppTheme.colors
    val found by produceState<List<VodRow>?>(initialValue = null, person.id) { value = metadata.personInLibrary(person.id) }
    var loaded = false
    val firstItem = remember { FocusRequester() }
    val autoFocus = LocalAutoFocus.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 48.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonPhoto(person = person, modifier = Modifier.size(72.dp))
            Column {
                Label(text = person.name, style = AppType.headline)
                val list = found
                Label(
                    text = when {
                        list == null -> "Looking through your playlists…"
                        list.isEmpty() -> "Nothing of theirs is in your playlists."
                        list.size == 1 -> "1 title in your playlists"
                        else -> "${list.size} titles in your playlists"
                    },
                    style = AppType.body,
                    color = colors.textDim,
                )
            }
        }

        val list = found.orEmpty()
        if (list.isNotEmpty()) {
            loaded = true
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(list, key = { _, row -> row.id }) { index, row ->
                    PosterCard(
                        row = row,
                        onClick = { onOpen(row) },
                        modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                    )
                }
            }
        }
    }

    LaunchedEffect(found != null && loaded) {
        if (!loaded) return@LaunchedEffect
        withFrameNanos { }
        if (autoFocus()) runCatching { firstItem.requestFocus() }
    }
}
