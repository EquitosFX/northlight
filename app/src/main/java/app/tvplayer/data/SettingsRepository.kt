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

/** App-wide settings saved on the device. They survive restarts. */
class SettingsRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val lastChannelKey = stringPreferencesKey("last_channel")
    private val tmdbKeyKey = stringPreferencesKey("tmdb_key")

    val theme: Flow<ThemeId> = context.settingsStore.data.map { prefs -> ThemeId.fromKey(prefs[themeKey]) }

    suspend fun setTheme(id: ThemeId) {
        context.settingsStore.edit { prefs -> prefs[themeKey] = id.key }
    }

    /** The channel watched last, so Live TV opens where the viewer left off. */
    val lastChannel: Flow<String?> = context.settingsStore.data.map { prefs -> prefs[lastChannelKey] }

    /** The viewer's own TMDB key; empty when they haven't added one. */
    val tmdbKey: Flow<String> = context.settingsStore.data.map { prefs -> prefs[tmdbKeyKey].orEmpty() }

    suspend fun setTmdbKey(key: String) {
        context.settingsStore.edit { prefs -> prefs[tmdbKeyKey] = key.trim() }
    }

    suspend fun setLastChannel(key: String) {
        context.settingsStore.edit { prefs -> prefs[lastChannelKey] = key }
    }
}
