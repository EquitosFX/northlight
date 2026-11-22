package app.tvplayer.data

import android.content.Context
import app.tvplayer.core.extras.NextEpisode
import app.tvplayer.core.extras.TvMaze
import app.tvplayer.core.mylist.WatchItem
import app.tvplayer.core.tmdb.TitleCleaner
import app.tvplayer.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneId

/** A series in My list with an episode out today. */
data class AiringSoon(val item: WatchItem, val episode: NextEpisode)

/**
 * When the next episodes of series in My list air, from TVmaze (free, no key). Found by IMDb id when
 * a TMDB key gives one, else by name. Each answer is kept for six hours.
 */
class AiringToday(
    context: Context,
    private val http: OkHttpClient,
    private val metadata: MetadataRepository,
) {
    private val prefs = context.getSharedPreferences("airing_today", Context.MODE_PRIVATE)

    /** Series in [watchlist] with an episode airing today (local time), soonest first. */
    suspend fun today(watchlist: List<WatchItem>, now: Long = System.currentTimeMillis()): List<AiringSoon> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        watchlist.filter { it.kind == "series" }.mapNotNull { item ->
            val next = nextEpisode(item, now) ?: return@mapNotNull null
            val airs = Instant.ofEpochMilli(next.airsAt).atZone(zone).toLocalDate()
            // Out today, or up to six hours ago (late last night counts as tonight's).
            if (airs == day || (next.airsAt in (now - 6 * 3_600_000L)..now)) AiringSoon(item, next) else null
        }.sortedBy { it.episode.airsAt }
    }

    private suspend fun nextEpisode(item: WatchItem, now: Long): NextEpisode? {
        val keyName = "next|" + item.key
        prefs.getString(keyName, null)?.let { kept ->
            val parts = kept.split("|")
            val at = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            if (now - at < KEEP_MILLIS) {
                val airs = parts.getOrNull(1)?.toLongOrNull() ?: return null
                return NextEpisode(parts.getOrNull(2)?.toIntOrNull(), parts.getOrNull(3)?.toIntOrNull(), parts.getOrNull(4)?.ifEmpty { null }, airs)
            }
        }
        val found = runCatching { fetch(item) }.getOrElse { return null }
        prefs.edit().putString(
            keyName,
            if (found == null) "$now|" else "$now|${found.airsAt}|${found.season ?: ""}|${found.number ?: ""}|${found.name.orEmpty().replace("|", " ")}",
        ).apply()
        return found
    }

    private suspend fun fetch(item: WatchItem): NextEpisode? {
        // By IMDb id when TMDB knows the series: no mix-ups between series of the same name.
        val info = metadata.peek(item.name, ChannelEntity.KIND_SERIES) ?: metadata.lookup(item.name, ChannelEntity.KIND_SERIES)
        val imdb = info?.tmdbId?.let { metadata.imdbId(it, isSeries = true) }
        if (imdb != null) {
            val show = get(TvMaze.byImdb(imdb))?.let(TvMaze::showId)
            if (show != null) return get(TvMaze.withNextEpisode(show))?.let(TvMaze::nextEpisode)
        }
        val name = TitleCleaner.clean(item.name).first.ifBlank { item.name }
        return get(TvMaze.byName(name))?.let(TvMaze::nextEpisode)
    }

    private fun get(url: String): String? =
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }

    companion object {
        const val KEEP_MILLIS = 6 * 3_600_000L
    }
}
