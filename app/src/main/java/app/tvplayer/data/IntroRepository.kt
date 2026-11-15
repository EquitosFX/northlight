package app.tvplayer.data

import app.tvplayer.core.intro.IntroDb
import app.tvplayer.core.intro.IntroSegments
import app.tvplayer.core.subtitles.OpenSubtitles
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.player.VodItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Intro, recap and credits timings from TheIntroDB, for Skip intro and Up next. Needs the title's
 * TMDB id, so it works when a TMDB key is set; otherwise (or offline) it quietly finds nothing.
 */
class IntroRepository(private val http: OkHttpClient, private val metadata: MetadataRepository) {
    private val cache = HashMap<String, IntroSegments>()

    suspend fun segments(item: VodItem): IntroSegments = withContext(Dispatchers.IO) {
        if (item.catchup) return@withContext IntroSegments()
        cache[item.url]?.let { return@withContext it }
        val episode = OpenSubtitles.seasonEpisode(item.title)
        val info = if (episode != null) {
            metadata.lookup(item.title.substringBefore(" · "), ChannelEntity.KIND_SERIES)
        } else {
            metadata.lookup(item.title, ChannelEntity.KIND_MOVIE)
        }
        val id = info?.tmdbId ?: return@withContext IntroSegments()
        val found = try {
            val request = Request.Builder()
                .url(IntroDb.url(id, episode?.first, episode?.second))
                .header("Accept", "application/json")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) IntroSegments() else IntroDb.parse(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            return@withContext IntroSegments()
        }
        cache[item.url] = found
        found
    }
}
