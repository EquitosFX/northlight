package app.tvplayer.ui.vod

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.tvplayer.data.MetadataRepository
import app.tvplayer.data.db.MetadataEntity

/** The YouTube id of a title's trailer, once TMDB has been asked (null while unknown or if none). */
@Composable
internal fun rememberTrailer(metadata: MetadataRepository, info: MetadataEntity?, isSeries: Boolean): String? {
    val trailer by produceState<String?>(initialValue = null, info?.tmdbId) {
        value = info?.tmdbId?.let { metadata.trailer(it, isSeries) }
    }
    return trailer
}

/**
 * Plays a trailer inside Skyfall (set by the app's main screen): the YouTube id, and what to do once it
 * closes (put focus back on the Trailer button). Without it, [openTrailer].
 */
internal val LocalPlayTrailer = androidx.compose.runtime.staticCompositionLocalOf<((String, () -> Unit) -> Unit)?> { null }

/**
 * Plays a trailer in the YouTube app, for trailers YouTube won't play inside Skyfall. On a TV, the
 * YouTube app is asked for the video by its address (its TV version opens on its home page, not the
 * video, when asked the older way); then the older way; then a browser. Returns false if none is there.
 */
internal fun openTrailer(context: Context, youtubeId: String): Boolean {
    val watch = Uri.parse("https://www.youtube.com/watch?v=$youtubeId")
    val tries = listOf(
        Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube.tv"),
        Intent(Intent.ACTION_VIEW, watch).setPackage("com.google.android.youtube"),
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId")),
        Intent(Intent.ACTION_VIEW, watch),
    )
    for (intent in tries) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (e: ActivityNotFoundException) {
            // Next way.
        } catch (e: SecurityException) {
            // Next way.
        }
    }
    return false
}
