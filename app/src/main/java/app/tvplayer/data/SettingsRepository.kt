package app.tvplayer.data

import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    private val timeshiftKey = intPreferencesKey("timeshift_minutes")
    private val catchupOffsetKey = intPreferencesKey("catchup_offset_minutes")
    private val autoRefreshKey = intPreferencesKey("auto_refresh_hours")
    private val backupPlayerKey = androidx.datastore.preferences.core.booleanPreferencesKey("backup_player")
    private val subtitleLanguageKey = stringPreferencesKey("subtitle_language")
    private val osKeyKey = stringPreferencesKey("opensubtitles_key")
    private val osUserKey = stringPreferencesKey("opensubtitles_user")
    private val osPasswordKey = stringPreferencesKey("opensubtitles_password")

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

    /** How many minutes of live TV are kept so it can be paused and rewound; 0 turns it off. */
    val timeshiftMinutes: Flow<Int> = context.settingsStore.data.map { prefs -> prefs[timeshiftKey] ?: DEFAULT_TIMESHIFT_MINUTES }

    suspend fun setTimeshiftMinutes(minutes: Int) {
        context.settingsStore.edit { prefs -> prefs[timeshiftKey] = minutes }
    }

    /**
     * Minutes added to a programme's times when asking the provider for catch-up. Some providers' clocks
     * are off by an hour or more, which plays the programme before (or after) the one chosen.
     */
    /** Whether the backup player takes over when the normal one can't play a stream. */
    val backupPlayer: Flow<Boolean> = context.settingsStore.data.map { prefs -> prefs[backupPlayerKey] ?: true }

    suspend fun setBackupPlayer(on: Boolean) {
        context.settingsStore.edit { prefs -> prefs[backupPlayerKey] = on }
    }

    /** How often playlists are refreshed by themselves, in hours (0: never). */
    val autoRefreshHours: Flow<Int> = context.settingsStore.data.map { prefs -> prefs[autoRefreshKey] ?: 24 }

    suspend fun setAutoRefreshHours(hours: Int) {
        context.settingsStore.edit { prefs -> prefs[autoRefreshKey] = hours }
    }

    val catchupOffsetMinutes: Flow<Int> = context.settingsStore.data.map { prefs -> prefs[catchupOffsetKey] ?: 0 }

    suspend fun setCatchupOffsetMinutes(minutes: Int) {
        context.settingsStore.edit { prefs -> prefs[catchupOffsetKey] = minutes.coerceIn(-MAX_CATCHUP_OFFSET, MAX_CATCHUP_OFFSET) }
    }

    /**
     * Every setting as text ("s:" text, "i:" whole number, "l:" long number, "b:" yes/no), for backups.
     * The last channel watched is left out, as it belongs to this device's playlists.
     */
    suspend fun exportAll(): Map<String, String> = context.settingsStore.data.first().asMap().mapNotNull { (key, value) ->
        if (key.name == "last_channel") return@mapNotNull null
        val text = when (value) {
            is String -> "s:$value"
            is Int -> "i:$value"
            is Long -> "l:$value"
            is Boolean -> "b:$value"
            else -> return@mapNotNull null
        }
        key.name to text
    }.toMap()

    /** Restores settings from [exportAll]'s form. Unknown or malformed entries are skipped. */
    suspend fun importAll(values: Map<String, String>) {
        context.settingsStore.edit { prefs ->
            values.forEach { (name, text) ->
                val value = text.drop(2)
                when (text.take(2)) {
                    "s:" -> prefs[androidx.datastore.preferences.core.stringPreferencesKey(name)] = value
                    "i:" -> value.toIntOrNull()?.let { prefs[intPreferencesKey(name)] = it }
                    "l:" -> value.toLongOrNull()?.let { prefs[androidx.datastore.preferences.core.longPreferencesKey(name)] = it }
                    "b:" -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = value == "true"
                }
            }
        }
    }

    /** The language subtitles are searched for in, as an OpenSubtitles code. */
    val subtitleLanguage: Flow<String> = context.settingsStore.data.map { prefs -> prefs[subtitleLanguageKey] ?: "en" }

    suspend fun setSubtitleLanguage(code: String) {
        context.settingsStore.edit { prefs -> prefs[subtitleLanguageKey] = code }
    }

    /** The viewer's OpenSubtitles API key, and optional account (which raises the daily download limit). */
    val openSubtitles: Flow<OpenSubtitlesAccount> = context.settingsStore.data.map { prefs ->
        OpenSubtitlesAccount(prefs[osKeyKey].orEmpty(), prefs[osUserKey].orEmpty(), prefs[osPasswordKey].orEmpty())
    }

    suspend fun setOpenSubtitles(account: OpenSubtitlesAccount) {
        context.settingsStore.edit { prefs ->
            prefs[osKeyKey] = account.apiKey.trim()
            prefs[osUserKey] = account.username.trim()
            prefs[osPasswordKey] = account.password
        }
    }

    companion object {
        const val DEFAULT_TIMESHIFT_MINUTES = 30
        const val MAX_CATCHUP_OFFSET = 12 * 60
    }
}

data class OpenSubtitlesAccount(val apiKey: String, val username: String, val password: String)
