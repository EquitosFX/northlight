package app.tvplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import app.tvplayer.core.display.FrameRateMatch

/**
 * Works out a video's frame rate. Live TV streams rarely say what it is, so it's measured from
 * the gaps between the first few dozen frames. Runs on the player's own thread; the answer is
 * handed to [onFound] on the main thread.
 */
@UnstableApi
class FrameProbe(private val onFound: (Float) -> Unit) : VideoFrameMetadataListener {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    private var seenGeneration = -1
    private var last = Long.MIN_VALUE
    private val gaps = ArrayList<Long>()
    private var tries = 0
    private var done = false

    /** A new stream: measure again. */
    fun reset() {
        generation++
    }

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: MediaFormat?) {
        val current = generation
        if (current != seenGeneration) {
            seenGeneration = current
            last = Long.MIN_VALUE
            gaps.clear()
            tries = 0
            done = false
        }
        if (done) return
        if (format.frameRate > 0f) {
            report(current, format.frameRate)
            return
        }
        if (last != Long.MIN_VALUE) gaps += presentationTimeUs - last
        last = presentationTimeUs
        if (gaps.size < SAMPLE) return
        val fps = FrameRateMatch.fromGaps(gaps)
        gaps.clear()
        if (fps != null) report(current, fps) else if (++tries >= MAX_TRIES) done = true
    }

    private fun report(forGeneration: Int, fps: Float) {
        done = true
        main.post { if (forGeneration == generation) onFound(fps) }
    }

    private companion object {
        const val SAMPLE = 48
        const val MAX_TRIES = 4
    }
}
