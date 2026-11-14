package app.tvplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tvplayer.ui.theme.ThemeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App-wide settings saved on the device. The chosen theme survives restarts. */
class SettingsRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")

    val theme: Flow<ThemeId> = context.settingsStore.data.map { prefs -> ThemeId.fromKey(prefs[themeKey]) }

    suspend fun setTheme(id: ThemeId) {
        context.settingsStore.edit { prefs -> prefs[themeKey] = id.key }
    }
}
