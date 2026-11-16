package app.tvplayer.data

import android.content.Context
import app.tvplayer.core.mylist.WatchItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notices when series in My list gain episodes. Each check counts a series' episodes and compares
 * the number with last time; a series with more is flagged until its page is opened.
 */
class NewEpisodes(
    context: Context,
    private val channels: ChannelsRepository,
    private val playlists: PlaylistRepository,
) {
    private val prefs = context.getSharedPreferences("new_episodes", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getStringSet(FLAGGED, emptySet()).orEmpty().toSet())

    /** My list keys ("series|name") of series with episodes the viewer hasn't seen listed yet. */
    val flagged: StateFlow<Set<String>> = state.asStateFlow()

    /** Checks every series in [watchlist]; returns the names of those that have gained episodes. */
    suspend fun check(watchlist: List<WatchItem>): List<String> {
        val found = ArrayList<String>()
        for (item in watchlist) {
            if (item.kind != "series") continue
            val row = channels.findTitle(item.kind, item.name, item.url) ?: continue
            val count = runCatching { playlists.seriesDetails(row, fresh = true).episodes.size }.getOrNull() ?: continue
            if (count == 0) continue
            val before = prefs.getInt(COUNT + item.key, -1)
            prefs.edit().putInt(COUNT + item.key, count).apply()
            // The first count for a series is only a starting point.
            if (before in 0 until count && item.key !in state.value) {
                found += item.name
                flag(item.key, true)
            }
        }
        return found
    }

    /** The series' page has been opened, so its new episodes have been seen. */
    fun seen(key: String) {
        if (key in state.value) flag(key, false)
    }

    private fun flag(key: String, on: Boolean) {
        val set = if (on) state.value + key else state.value - key
        state.value = set
        prefs.edit().putStringSet(FLAGGED, set).apply()
    }

    companion object {
        private const val FLAGGED = "flagged"
        private const val COUNT = "count|"
        const val CHECK_EVERY_MILLIS = 4 * 3_600_000L
    }
}
