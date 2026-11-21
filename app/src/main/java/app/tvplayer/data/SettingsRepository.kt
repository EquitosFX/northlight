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

    private val audioDelayKey = intPreferencesKey("audio_delay_ms")
    private val guideTextKey = intPreferencesKey("guide_text_size")
    private val guideLogosKey = androidx.datastore.preferences.core.booleanPreferencesKey("guide_logos")
    private val guideDetailsKey = androidx.datastore.preferences.core.booleanPreferencesKey("guide_details")
    private val matchFrameRateKey = androidx.datastore.preferences.core.booleanPreferencesKey("match_frame_rate")
    private val subtitleLanguageKey = stringPreferencesKey("subtitle_language")
    private val subtitleLookKey = stringPreferencesKey("subtitle_look")
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

    /** Whether the TV switches to a refresh rate that suits the video (50 Hz for UK channels). */
    val matchFrameRate: Flow<Boolean> = context.settingsStore.data.map { prefs -> prefs[matchFrameRateKey] ?: false }

    suspend fun setMatchFrameRate(on: Boolean) {
        context.settingsStore.edit { prefs -> prefs[matchFrameRateKey] = on }
    }

    /** TV guide text and row size: 0 smaller (more channels), 1 standard, 2 larger. */
    val guideTextSize: Flow<Int> = context.settingsStore.data.map { prefs -> prefs[guideTextKey] ?: 1 }

    suspend fun setGuideTextSize(size: Int) {
        context.settingsStore.edit { prefs -> prefs[guideTextKey] = size.coerceIn(0, 2) }
    }

    /** Whether the TV guide shows channel logos. */
    val guideLogos: Flow<Boolean> = context.settingsStore.data.map { prefs -> prefs[guideLogosKey] ?: true }

    suspend fun setGuideLogos(on: Boolean) {
        context.settingsStore.edit { prefs -> prefs[guideLogosKey] = on }
    }

    /** Whether the TV guide shows the highlighted programme's details at the top (off fits more channels). */
    val guideDetails: Flow<Boolean> = context.settingsStore.data.map { prefs -> prefs[guideDetailsKey] ?: true }

    suspend fun setGuideDetails(on: Boolean) {
        context.settingsStore.edit { prefs -> prefs[guideDetailsKey] = on }
    }

    /** Sound moved against the picture, in milliseconds: positive is later. */
    val audioDelayMs: Flow<Int> = context.settingsStore.data.map { prefs -> prefs[audioDelayKey] ?: 0 }

    suspend fun setAudioDelayMs(ms: Int) {
        context.settingsStore.edit { prefs -> prefs[audioDelayKey] = ms.coerceIn(-MAX_AUDIO_DELAY_MS, MAX_AUDIO_DELAY_MS) }
    }

    /**
     * Minutes added to a programme's times when asking the provider for catch-up. Some providers' clocks
     * are off by an hour or more, which plays the programme before (or after) the one chosen.
     */
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
    /** How subtitles look: the box behind them (or a Netflix-style shadow), size, typeface and colour. */
    val subtitleLook: Flow<SubtitleLook> = context.settingsStore.data.map { prefs -> SubtitleLook.decode(prefs[subtitleLookKey]) }

    suspend fun setSubtitleLook(look: SubtitleLook) {
        context.settingsStore.edit { prefs -> prefs[subtitleLookKey] = look.encode() }
    }

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
        const val MAX_AUDIO_DELAY_MS = 1_000
    }
}

/** How subtitles look on screen. */
data class SubtitleLook(
    /**
     * "netflix": a soft shadow and the faintest outline, no box (as Netflix); "box": on a dark box;
     * "shadow": a firmer drop shadow; "outline": a dark outline; "plain": nothing behind the text.
     */
    val style: String = "box",
    /** Percent of the standard size. */
    val size: Int = 100,
    /** One of [FONTS]' keys. */
    val font: String = "roboto_medium",
    /** "white", "yellow" or "grey". */
    val colour: String = "white",
    /** How strong the shadow or outline is: 1 soft, 2 normal, 3 strong. */
    val edge: Int = 2,
    /** How dark the box is, in percent (box style only). */
    val boxOpacity: Int = 60,
    /** How far up from the bottom: 0 low, 1 normal, 2 higher, 3 highest. */
    val lift: Int = 1,
) {
    fun encode() = "v2;style=$style;size=$size;font=$font;colour=$colour;edge=$edge;box=$boxOpacity;lift=$lift"

    companion object {
        /** Closest to Netflix: Inter (much like Netflix Sans), white, a soft shadow, no box. */
        val NETFLIX = SubtitleLook(style = "netflix", size = 100, font = "inter", colour = "white", edge = 2, lift = 1)

        /** The typefaces on offer: key to the name shown. */
        val FONTS = listOf(
            "inter" to "Inter (Netflix-like)",
            "inter_semibold" to "Inter Semibold",
            "roboto_medium" to "Roboto",
            "roboto_bold" to "Roboto Bold",
            "open_sans" to "Open Sans",
            "source_sans" to "Source Sans",
            "lato" to "Lato Bold",
            "condensed" to "Condensed",
            "figtree" to "Figtree",
            "serif" to "Serif",
            "mono" to "Typewriter",
            "casual" to "Casual",
        )

        fun decode(text: String?): SubtitleLook {
            if (text == null) return SubtitleLook()
            if (text.startsWith("v2;")) {
                val values = text.removePrefix("v2;").split(';').mapNotNull { part ->
                    part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                }.toMap()
                val base = SubtitleLook()
                return SubtitleLook(
                    style = values["style"] ?: base.style,
                    size = values["size"]?.toIntOrNull()?.coerceIn(60, 200) ?: base.size,
                    font = values["font"]?.takeIf { key -> FONTS.any { it.first == key } } ?: base.font,
                    colour = values["colour"] ?: base.colour,
                    edge = values["edge"]?.toIntOrNull()?.coerceIn(1, 3) ?: base.edge,
                    boxOpacity = values["box"]?.toIntOrNull()?.coerceIn(20, 100) ?: base.boxOpacity,
                    lift = values["lift"]?.toIntOrNull()?.coerceIn(0, 3) ?: base.lift,
                )
            }
            // The first version: style|size|font|colour.
            val parts = text.split('|')
            if (parts.size != 4) return SubtitleLook()
            return SubtitleLook(
                style = parts[0],
                size = parts[1].toIntOrNull()?.coerceIn(60, 200) ?: 100,
                font = when (parts[2]) {
                    "bold" -> "roboto_bold"
                    "serif" -> "serif"
                    else -> "roboto_medium"
                },
                colour = parts[3],
            )
        }
    }
}

data class OpenSubtitlesAccount(val apiKey: String, val username: String, val password: String)
