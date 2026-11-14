package app.tvplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType

private val SideMargin = 48.dp

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    yourChannels: List<Tile> = emptyList(),
    onPlayChannel: (Long) -> Unit = {},
    onLiveTv: () -> Unit = {},
) {
    val playTile: (Tile) -> Unit = { tile -> tile.channelId?.let(onPlayChannel) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Hero(item = SampleData.hero, watchFocus = firstFocus) }
        // Real channels from the user's playlists; everything below is still sample content.
        if (yourChannels.isNotEmpty()) {
            item { Rail(title = "Your channels", tiles = yourChannels, onTileClick = playTile) }
        }
        item { Rail(title = "For you", tiles = SampleData.forYou) }
        item { ShortcutRow(labels = SampleData.shortcuts, onClick = { label -> if (label == "Live TV") onLiveTv() }) }
        item { Rail(title = "On now and next", tiles = SampleData.onNow) }
    }
}

@Composable
private fun Hero(item: HeroItem, watchFocus: FocusRequester) {
    val colors = AppTheme.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(236.dp),
    ) {
        // Stand-in for backdrop artwork; it fades into the themed background on the left.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.6f)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color(item.artColor)))),
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = SideMargin)
                .width(410.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(text = item.overline, style = AppType.overline, color = colors.accent)
            Label(text = item.title, style = AppType.display, maxLines = 2)
            Label(text = item.tagline, style = AppType.body, color = colors.textDim, maxLines = 2)
            Label(text = item.meta, style = AppType.label, color = colors.textFaint)

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSurface(
                    onClick = { },
                    modifier = Modifier
                        .focusRequester(watchFocus)
                        .height(36.dp),
                    container = colors.accent,
                    focusedContainer = colors.accent,
                    outlined = false,
                ) {
                    Label(
                        text = "Watch",
                        style = AppType.title,
                        color = colors.onAccent,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                FocusSurface(onClick = { }, modifier = Modifier.height(36.dp)) {
                    Label(text = "Trailer", style = AppType.title, modifier = Modifier.padding(horizontal = 18.dp))
                }
            }
        }
    }
}

@Composable
private fun Rail(title: String, tiles: List<Tile>, onTileClick: (Tile) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(
            text = title,
            style = AppType.label,
            color = AppTheme.colors.textDim,
            modifier = Modifier.padding(start = SideMargin),
        )
        LazyRow(
            // The vertical padding leaves room for the focused card to scale up without being clipped.
            contentPadding = PaddingValues(horizontal = SideMargin, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tiles) { tile -> TileCard(tile = tile, onClick = { onTileClick(tile) }) }
        }
    }
}

@Composable
private fun TileCard(tile: Tile, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val art = Color(tile.color)

    FocusSurface(
        onClick = onClick,
        modifier = Modifier.size(width = 163.dp, height = 92.dp),
        shape = RoundedCornerShape(9.dp),
        container = art,
        focusedContainer = art,
        outlined = false,
    ) {
        if (tile.logo != null) {
            Logo(
                url = tile.logo,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .size(width = 72.dp, height = 44.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to colors.scrim)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 9.dp, end = 9.dp, bottom = 9.dp),
        ) {
            if (tile.badge != null) {
                Label(text = tile.badge, style = AppType.caption, color = colors.accent)
            }
            Label(text = tile.title, style = AppType.label)
            Label(text = tile.caption, style = AppType.caption, color = colors.textDim)
        }
        if (tile.progress != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(tile.progress)
                    .height(3.dp)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun ShortcutRow(labels: List<String>, onClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SideMargin, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        labels.forEach { label ->
            FocusSurface(
                onClick = { onClick(label) },
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
            ) {
                Label(text = label, style = AppType.title)
            }
        }
    }
}
