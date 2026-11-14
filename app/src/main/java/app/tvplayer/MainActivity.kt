package app.tvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.data.SettingsRepository
import app.tvplayer.ui.AppRoot
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.ThemeId
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository(applicationContext)

        setContent {
            val themeId by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeId.LAGOON)
            val scope = rememberCoroutineScope()

            AppTheme(themeId = themeId) {
                AppRoot(
                    themeId = themeId,
                    onThemeChange = { chosen -> scope.launch { settings.setTheme(chosen) } },
                )
            }
        }
    }
}
