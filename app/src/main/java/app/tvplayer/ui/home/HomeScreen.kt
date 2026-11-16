package app.tvplayer.ui.home

import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.fadeIntoBackground
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
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

private val SideMargin = 48.dp

/** Home: a hero for the channel last watched (or a welcome), then rails of the viewer's own content and shortcuts. */
@Composable
fun HomeScreen(
    hero: HomeHero,
    rails: List<HomeRail>,
    shortcuts: List<String>,
    onTile: (Tile) -> Unit,
    onShortcut: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) { if (autoFocus()) runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Hero(hero = hero, primaryFocus = firstFocus) }
        item { ShortcutRow(labels = shortcuts, onClick = onShortcut) }
        for (rail in rails) {
            if (rail.tiles.isNotEmpty()) item(key = rail.title) { Rail(title = rail.title, tiles = rail.tiles, onTileClick = onTile) }
        }
    }
}

/** Recents: channels watched lately, and films and episodes to carry on with. */
@Composable
fun RecentsScreen(rails: List<HomeRail>, onTile: (Tile) -> Unit) {
    val colors = AppTheme.colors
    if (rails.all { it.tiles.isEmpty() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label(text = "Nothing watched yet. Channels and films you watch will appear here.", style = AppType.headline, color = colors.textDim)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 27.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (rail in rails) {
            if (rail.tiles.isNotEmpty()) item(key = rail.title) { Rail(title = rail.title, tiles = rail.tiles, onTileClick = onTile) }
        }
    }
}

@Composable
private fun Hero(hero: HomeHero, primaryFocus: FocusRequester) {
    EntryPoint(primaryFocus)
    val colors = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp),
    ) {
        val backdrop = hero.backdrop
        if (backdrop != null) {
            // Full-bleed artwork to the right, fading into the background behind the text and at the bottom.
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.78f)
                    .fadeIntoBackground(solidFrom = 0.5f, solidUntil = 0.55f),
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.55f)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, colors.glassStrong))),
            )
        }
        if (hero.logo != null && backdrop == null) {
            Logo(
                url = hero.logo,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 96.dp)
                    .size(width = 220.dp, height = 130.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                // Room above, so the first line isn't tight against the top of the TV; the artwork still runs to the top.
                .padding(start = SideMargin, top = 26.dp)
                .width(430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(text = hero.overline, style = AppType.overline, color = colors.accent)
            Label(text = hero.title, style = AppType.display, maxLines = 2)
            hero.tagline?.let { Label(text = it, style = AppType.body, color = colors.textDim, maxLines = 2) }
            hero.meta?.let { Label(text = it, style = AppType.label, color = colors.textFaint) }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSurface(
                    onClick = hero.onPrimary,
                    modifier = Modifier
                        .focusRequester(primaryFocus)
                        .height(36.dp),
                    container = colors.accent,
                    focusedContainer = colors.accent,
                    outlined = false,
                ) {
                    Label(
                        text = hero.primaryLabel,
                        style = AppType.title,
                        color = colors.onAccent,
                        modifier = Modifier.padding(horizontal = 22.dp),
                    )
                }
                val secondary = hero.secondaryLabel
                if (secondary != null) {
                    FocusSurface(onClick = hero.onSecondary, modifier = Modifier.height(36.dp)) {
                        Label(text = secondary, style = AppType.title, modifier = Modifier.padding(horizontal = 18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun Rail(title: String, tiles: List<Tile>, onTileClick: (Tile) -> Unit = {}) {
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
        if (tile.vod != null && tile.logo != null) {
            // Films and episodes fill the card with their artwork.
            AsyncImage(
                model = tile.logo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (tile.logo != null) {
            Logo(
                url = tile.logo,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp)
                    .size(width = 72.dp, height = 40.dp),
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
