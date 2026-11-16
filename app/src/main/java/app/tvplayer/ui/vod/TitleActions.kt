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

/** Plays a trailer in the YouTube app (or a browser if there isn't one). Returns false if neither is there. */
internal fun openTrailer(context: Context, youtubeId: String): Boolean {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(app)
        true
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(web)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
