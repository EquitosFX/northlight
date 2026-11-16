package app.tvplayer.ui.settings

import app.tvplayer.ui.theme.snows
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId
import app.tvplayer.ui.theme.paletteFor

/** Settings › Appearance: pick one of the colour themes. The whole app re-colours as soon as one is chosen. */
@Composable
fun AppearancePane(themeId: ThemeId, onThemeChange: (ThemeId) -> Unit) {
    val colors = AppTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text = "Appearance", style = AppType.headline)
        Label(text = "Theme", style = AppType.body, color = colors.textDim)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            ThemeId.entries.forEach { id ->
                ThemeTile(
                    id = id,
                    inUse = id == themeId,
                    onClick = { onThemeChange(id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ThemeTile(id: ThemeId, inUse: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val preview = paletteFor(id)

    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(172.dp),
        shape = RoundedCornerShape(12.dp),
        focusScale = 1.02f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A miniature of the Home screen drawn in this theme's colours.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(preview.ground),
            ) {
                if (id.snows) app.tvplayer.ui.theme.Snowfall(Modifier.matchParentSize(), count = 18)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 9.dp)
                        .size(width = 90.dp, height = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White.copy(alpha = 0.18f)),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 87.dp, height = 10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White),
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 13.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(preview.accent),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Label(text = id.label, style = AppType.title)
                    Label(text = id.description, style = AppType.caption, color = colors.textDim)
                }
                if (inUse) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.accent)
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    ) {
                        Label(text = "In use", style = AppType.caption, color = colors.onAccent)
                    }
                }
            }
        }
    }
}
