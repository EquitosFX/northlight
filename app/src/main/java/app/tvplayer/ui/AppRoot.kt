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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tvplayer.R
import app.tvplayer.ui.components.Dest
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.TopNavBar
import app.tvplayer.ui.home.HomeScreen
import app.tvplayer.ui.settings.AppearanceScreen
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.theme.ThemeId

/** The app shell: themed background, top navigation, and whichever section is open. */
@Composable
fun AppRoot(themeId: ThemeId, onThemeChange: (ThemeId) -> Unit) {
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }

    // Back returns to Home from any other section; from Home it leaves the app as usual.
    BackHandler(enabled = dest != Dest.HOME) { dest = Dest.HOME }

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
                Dest.HOME -> HomeScreen()
                Dest.SETTINGS -> AppearanceScreen(themeId = themeId, onThemeChange = onThemeChange)
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
