package app.tvplayer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.R
import app.tvplayer.data.PlaylistRepository
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TopNavBar
import app.tvplayer.ui.home.HomeScreen
import app.tvplayer.ui.home.Tile
import app.tvplayer.ui.settings.SettingsScreen
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId

// Stand-in tile colours until channel logos are shown.
private val tileColors = listOf(0xFF2F7D50, 0xFF3E5C8A, 0xFF8A4B2E, 0xFF5A3B78, 0xFF2E6F78, 0xFF4B5D67)

/** The app shell: themed background, top navigation, and whichever section is open. */
@Composable
fun AppRoot(
    themeId: ThemeId,
    onThemeChange: (ThemeId) -> Unit,
    playlists: PlaylistRepository,
) {
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }

    // Back returns to Home from any other section; from Home it leaves the app as usual.
    BackHandler(enabled = dest != Dest.HOME) { dest = Dest.HOME }

    val liveChannels by playlists.firstLiveChannels.collectAsStateWithLifecycle(initialValue = emptyList())
    val yourChannels = remember(liveChannels) {
        liveChannels
            .distinctBy { it.variantKey } // one tile per channel, however many quality copies it has
            .take(20)
            .map { channel ->
                Tile(
                    title = channel.baseName,
                    caption = channel.groupName ?: "Live",
                    color = tileColors[(channel.variantKey.hashCode() and 0x7fffffff) % tileColors.size],
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.ground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopNavBar(
                selected = dest,
                onSelect = { chosen -> dest = chosen },
                appName = stringResource(R.string.app_name),
            )
            Spacer(Modifier.height(10.dp))
            when (dest) {
                Dest.HOME -> HomeScreen(yourChannels = yourChannels)
                Dest.SETTINGS -> SettingsScreen(
                    themeId = themeId,
                    onThemeChange = onThemeChange,
                    playlists = playlists,
                )
                else -> ComingLater(dest)
            }
        }
    }
}

@Composable
private fun ComingLater(dest: Dest) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Label(
            text = "${dest.label} is built in a later phase",
            style = AppType.headline,
            color = AppTheme.colors.textDim,
        )
    }
}
