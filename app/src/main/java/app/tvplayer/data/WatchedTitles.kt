package app.tvplayer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A film or series the viewer has played, for suggesting more like it. */
data class WatchedTitle(val name: String, val isSeries: Boolean)

/**
 * The films and series played recently, newest first, kept on the device. Episodes are stored
 * under their show's name, so a binge counts once.
 */
class WatchedTitles(context: Context) {
    private val prefs = context.getSharedPreferences("watched_titles", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val recent: StateFlow<List<WatchedTitle>> = state.asStateFlow()

    /** Records a play, given the title as the player shows it ("Low Tide" or "Harbour Lights · S1 E3 · Cold Case"). */
    fun record(playerTitle: String, url: String? = null) {
        val isSeries = EPISODE.containsMatchIn(playerTitle)
        val name = if (isSeries) playerTitle.substringBefore(" · ").trim() else playerTitle.trim()
        if (name.isEmpty()) return
        // The episode last played, so the series page can offer to resume it or play the next one.
        if (isSeries && url != null) prefs.edit().putString(LAST_EPISODE + name.lowercase(), url).apply()
        val entry = WatchedTitle(name, isSeries)
        val list = (listOf(entry) + state.value.filterNot { it.name.equals(name, ignoreCase = true) && it.isSeries == isSeries })
            .take(MAX)
        state.value = list
        prefs.edit().putString(KEY, list.joinToString("\n") { (if (it.isSeries) "S" else "F") + "\t" + it.name.replace('\n', ' ') }).apply()
    }

    /** Notes that the film or episode at [url] was watched to the end. */
    fun finished(url: String) {
        val set = (prefs.getStringSet(FINISHED, emptySet()).orEmpty().toList().takeLast(MAX_FINISHED - 1) + url).toSet()
        prefs.edit().putStringSet(FINISHED, set).apply()
    }

    fun isFinished(url: String): Boolean = url in prefs.getStringSet(FINISHED, emptySet()).orEmpty()

    /** The address of the episode of [show] played last, if any. */
    fun lastEpisode(show: String): String? = prefs.getString(LAST_EPISODE + show.trim().lowercase(), null)

    private fun read(): List<WatchedTitle> =
        prefs.getString(KEY, null).orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) null else WatchedTitle(parts[1], parts[0] == "S")
        }

    private companion object {
        const val KEY = "titles"
        const val LAST_EPISODE = "last_episode|"
        const val FINISHED = "finished"
        const val MAX_FINISHED = 2_000
        const val MAX = 30
        val EPISODE = Regex(""" · S\d+ ?E\d+""")
    }
}
