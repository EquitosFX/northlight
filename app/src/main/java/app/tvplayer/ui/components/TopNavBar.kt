package app.tvplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The sections reachable from the top navigation bar. */
enum class Dest(val label: String) {
    RECENTS("Recents"),
    HOME("Home"),
    GUIDE("TV Guide"),
    LIBRARY("Library"),
    SPORT("Sport"),
    ACCOUNT("Account"),
    SEARCH("Search"),
    SETTINGS("Settings"),
}

private val tabs = listOf(Dest.HOME, Dest.GUIDE, Dest.LIBRARY, Dest.SPORT, Dest.ACCOUNT)

@Composable
fun TopNavBar(
    selected: Dest,
    onSelect: (Dest) -> Unit,
    appName: String,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val barShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.width(150.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = rememberVectorPainter(image = Icons.Filled.PlayArrow),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.onAccent),
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Label(text = appName, style = AppType.title)
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .clip(barShape)
                .background(colors.glass)
                .border(width = 1.dp, color = colors.glassBorder, shape = barShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavIcon(Icons.Filled.Refresh, Dest.RECENTS, selected, onSelect)
            tabs.forEach { dest ->
                NavTab(dest = dest, selected = selected, onSelect = onSelect)
            }
            NavIcon(Icons.Filled.Search, Dest.SEARCH, selected, onSelect)
            NavIcon(Icons.Filled.Settings, Dest.SETTINGS, selected, onSelect)
        }

        Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.width(150.dp), contentAlignment = Alignment.CenterEnd) {
            Label(text = rememberClock(), style = AppType.title)
        }
    }
}

@Composable
private fun NavTab(dest: Dest, selected: Dest, onSelect: (Dest) -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = { onSelect(dest) },
        modifier = Modifier.height(33.dp),
        container = if (dest == selected) colors.glassStrong else Color.Transparent,
        outlined = false,
    ) {
        Label(text = dest.label, style = AppType.label, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

@Composable
private fun NavIcon(icon: ImageVector, dest: Dest, selected: Dest, onSelect: (Dest) -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = { onSelect(dest) },
        modifier = Modifier.size(33.dp),
        container = if (dest == selected) colors.glassStrong else Color.Transparent,
        outlined = false,
    ) {
        Image(
            painter = rememberVectorPainter(image = icon),
            contentDescription = dest.label,
            colorFilter = ColorFilter.tint(colors.text),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun rememberClock(): String {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    var now by remember { mutableStateOf(LocalTime.now().format(formatter)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = LocalTime.now().format(formatter)
        }
    }
    return now
}
