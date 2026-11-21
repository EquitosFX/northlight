package app.tvplayer.data

import app.tvplayer.core.sport.LiveScore
import app.tvplayer.core.sport.LiveScores
import app.tvplayer.core.sport.SportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Live scores from ESPN's free scoreboard feeds (football, rugby union, NFL, NBA, NHL, MLB). Each feed is
 * read again every 25 seconds while it has a match on or about to start, otherwise every 5 minutes.
 */
class ScoresRepository(http: OkHttpClient) {
    private val client = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<LiveScore>>>()

    /** The scores on every feed for [kinds] (from memory where recent enough). */
    suspend fun scores(kinds: Set<SportKind>): List<LiveScore> = withContext(Dispatchers.IO) {
        val feeds = kinds.flatMap { LiveScores.feeds[it].orEmpty() }.distinct()
        coroutineScope { feeds.map { feed -> async { feed(feed) } }.awaitAll().flatten() }
    }

    private fun feed(feed: String): List<LiveScore> {
        val now = System.currentTimeMillis()
        cache[feed]?.let { (at, scores) ->
            val busy = scores.any { it.state == LiveScore.State.PLAYING || it.start in now..now + SOON_MILLIS }
            if (now - at < if (busy) BUSY_MILLIS else QUIET_MILLIS) return scores
        }
        val read = runCatching {
            client.newCall(Request.Builder().url(LiveScores.url(feed)).build()).execute().use { response ->
                if (response.isSuccessful) LiveScores.parse(response.body?.string().orEmpty()) else emptyList()
            }
        }.getOrNull()
        // A feed that couldn't be read keeps what it had, for now.
        val scores = read ?: cache[feed]?.second.orEmpty()
        cache[feed] = now to scores
        return scores
    }

    private companion object {
        const val BUSY_MILLIS = 25_000L
        const val QUIET_MILLIS = 5 * 60_000L
        const val SOON_MILLIS = 30 * 60_000L
    }
}
