package app.tvplayer.core.display

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picking the TV's refresh rate for a video, so 25 and 50 fps UK channels aren't shown at 60 Hz
 * (which makes movement judder, most noticeably on Google TV, which runs at 60 Hz by default).
 */
object FrameRateMatch {
    /** How many times [refresh] shows each frame of [fps] video, if it's a whole number (allowing for 23.976 and the like). */
    fun repeats(refresh: Float, fps: Float): Int? {
        if (fps < 10f || refresh < 10f) return null
        val ratio = refresh / fps
        val whole = ratio.roundToInt()
        if (whole < 1 || abs(ratio - whole) > 0.012f * whole) return null
        return whole
    }

    /**
     * Which of [refreshRates] to switch to for [fps] video, or null to stay on [current] (it already
     * suits the video, or nothing suits it better). 50 Hz for 25 fps, 23.976 Hz for 23.976 fps films.
     */
    fun pick(refreshRates: List<Float>, current: Float, fps: Float): Int? {
        if (repeats(current, fps) != null) return null
        val suitable = refreshRates.withIndex()
            .mapNotNull { (index, rate) -> repeats(rate, fps)?.let { Triple(index, it, abs(rate - fps * it)) } }
        // A smooth 50 or 60 Hz where it suits (menus are sluggish at 25 Hz); the low film rates only for films.
        val fast = suitable.filter { refreshRates[it.first] >= 47f }
        return (fast.ifEmpty { suitable })
            // Fewest repeats, then the closest (23.976 Hz for 23.976 fps rather than 24 Hz).
            .minWithOrNull(compareBy({ it.second }, { it.third }))
            ?.first
    }

    /** The frame rate from the gaps between frames (in microseconds), or null if they're too uneven to say. */
    fun fromGaps(gapsUs: List<Long>): Float? {
        val usable = gapsUs.filter { it in 5_000L..100_000L }.sorted()
        if (usable.size < 12) return null
        val middle = usable[usable.size / 2]
        // Most gaps should agree with the middle one (dropped or doubled frames aside).
        val agreeing = usable.count { abs(it - middle) <= middle / 20 }
        if (agreeing < usable.size * 0.6) return null
        val fps = 1_000_000f / middle
        // Snap to the usual rates.
        val usual = listOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)
        return usual.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) < 0.6f } ?: fps
    }
}
