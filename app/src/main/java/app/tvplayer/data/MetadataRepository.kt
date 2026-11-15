package app.tvplayer.data

import app.tvplayer.core.tmdb.TitleCleaner
import app.tvplayer.core.tmdb.TmdbParser
import app.tvplayer.core.tmdb.TmdbUrls
import app.tvplayer.data.db.AppDatabase
import app.tvplayer.data.db.ChannelEntity
import app.tvplayer.data.db.MetadataEntity
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks films and series up on TMDB with the viewer's own key, and caches the answers.
 * Without a key, or offline, look-ups quietly return nothing and the screens use the playlist's own details.
 */
class MetadataRepository(
    private val db: AppDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private class KeyRejected : IOException("TMDB didn't accept that key.")

    suspend fun lookup(name: String, kind: String): MetadataEntity? = withContext(Dispatchers.IO) {
        val key = settings.tmdbKey.first().trim()
        if (key.isEmpty()) return@withContext null
        val type = if (kind == ChannelEntity.KIND_SERIES) "tv" else "movie"
        val (title, year) = TitleCleaner.clean(name)
        val cacheKey = "$type|${title.lowercase()}|${year ?: ""}"
        val now = System.currentTimeMillis()

        val cached = db.metadata().get(cacheKey)
        if (cached != null && (cached.found || cached.fetchedAt > now - RETRY_MISS_MILLIS)) {
            return@withContext cached.takeIf { it.found }
        }
        try {
            var best = TmdbParser.pickBest(TmdbParser.parseSearch(get(TmdbUrls.search(type, title, year), key)), title, year)
            // Playlists sometimes have the wrong year; try once more without it.
            if (best == null && year != null) {
                best = TmdbParser.pickBest(TmdbParser.parseSearch(get(TmdbUrls.search(type, title, null), key)), title, null)
            }
            val entry = if (best == null) {
                MetadataEntity(cacheKey = cacheKey, found = false, fetchedAt = now)
            } else {
                val details = TmdbParser.parseDetails(get(TmdbUrls.details(type, best.id), key))
                MetadataEntity(
                    cacheKey = cacheKey,
                    found = true,
                    fetchedAt = now,
                    tmdbId = details.id,
                    title = details.title,
                    year = details.year,
                    overview = details.overview,
                    poster = details.poster,
                    backdrop = details.backdrop,
                    rating = details.rating,
                    runtimeMinutes = details.runtimeMinutes,
                    genres = details.genres.joinToString(", "),
                    cast = details.cast.joinToString(", "),
                    seasons = details.seasons,
                )
            }
            db.metadata().save(entry)
            entry.takeIf { it.found }
        } catch (e: Exception) {
            null
        }
    }

    /** Returns null when [key] works, otherwise a reason it doesn't. */
    suspend fun checkKey(key: String): String? = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext "Enter a key first."
        try {
            get(TmdbUrls.check(), key)
            null
        } catch (e: KeyRejected) {
            e.message
        } catch (e: UnknownHostException) {
            "Couldn't reach TMDB. Check the internet connection."
        } catch (e: Exception) {
            e.message ?: "The check failed."
        }
    }

    private fun get(url: String, key: String): String {
        val request = Request.Builder()
            .url(TmdbUrls.withKey(url, key))
            .header("Accept", "application/json")
            .apply { if (TmdbUrls.usesBearer(key)) header("Authorization", "Bearer ${key.trim()}") }
            .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401) throw KeyRejected()
            if (!response.isSuccessful) throw IOException("TMDB answered with error ${response.code}.")
            response.body?.string() ?: throw IOException("TMDB sent nothing back.")
        }
    }

    private companion object {
        /** A title TMDB didn't know is tried again after a week. */
        const val RETRY_MISS_MILLIS = 7 * 86_400_000L
    }
}
