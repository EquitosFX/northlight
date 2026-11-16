package app.tvplayer.core.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatchTest {
    private val googleTv = listOf(60f, 59.94f, 50f, 30f, 25f, 24f, 23.976f)

    @Test
    fun ukChannelsGoTo50() {
        assertEquals(2, FrameRateMatch.pick(googleTv, current = 60f, fps = 50f))
        // 25 fps: 50 Hz, not 25 Hz, which would make the menus sluggish.
        assertEquals(2, FrameRateMatch.pick(googleTv, current = 60f, fps = 25f))
    }

    @Test
    fun filmsGoTo24() {
        assertEquals(6, FrameRateMatch.pick(googleTv, current = 60f, fps = 23.976f))
        assertEquals(5, FrameRateMatch.pick(googleTv, current = 60f, fps = 24f))
    }

    @Test
    fun staysWhenAlreadySuitable() {
        assertNull(FrameRateMatch.pick(googleTv, current = 50f, fps = 25f))
        assertNull(FrameRateMatch.pick(googleTv, current = 60f, fps = 30f))
        assertNull(FrameRateMatch.pick(listOf(60f), current = 60f, fps = 25f))
    }

    @Test
    fun measuresFromFrameGaps() {
        assertEquals(25.0, FrameRateMatch.fromGaps(List(30) { 40_000L })!!.toDouble(), 0.01)
        assertEquals(50.0, FrameRateMatch.fromGaps(List(30) { if (it % 2 == 0) 20_000L else 20_001L })!!.toDouble(), 0.01)
        assertEquals(23.976, FrameRateMatch.fromGaps(List(30) { 41_708L })!!.toDouble(), 0.01)
        assertNull(FrameRateMatch.fromGaps(List(5) { 40_000L }))
        assertNull(FrameRateMatch.fromGaps(List(30) { (it * 7_919L) % 90_000L + 6_000L }))
    }
}
