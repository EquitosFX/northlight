package app.tvplayer.core.vod

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class AddedDatesTest {
    private val now = 1_763_500_000L

    @Test fun providerDatesAreKept() {
        val dates = AddedDates.merge(null, listOf("http://x/movie/1.mkv" to 1_700_000_000L, "http://x/movie/2.mkv" to 1_760_000_000L), now)
        assertEquals(1_700_000_000L, dates.dateOf("http://x/movie/1.mkv"))
        assertEquals(1_760_000_000L, dates.dateOf("http://x/movie/2.mkv"))
        assertNull(dates.dateOf("http://x/movie/3.mkv"))
    }

    @Test fun withoutAProviderDateTheFirstSightingIsKept() {
        val first = AddedDates.merge(null, listOf("a" to null), now - 1000)
        val second = AddedDates.merge(first, listOf("a" to null, "b" to null), now)
        assertEquals(now - 1000, second.dateOf("a"))
        assertEquals(now, second.dateOf("b"))
    }

    @Test fun nonsenseDatesCountAsMissing() {
        val dates = AddedDates.merge(null, listOf("old" to 5L, "future" to now + 30 * 86_400L), now)
        assertEquals(now, dates.dateOf("old"))
        assertEquals(now, dates.dateOf("future"))
    }

    @Test fun titlesGoneFromThePlaylistAreDropped() {
        val first = AddedDates.merge(null, listOf("a" to null, "b" to null), now)
        val second = AddedDates.merge(first, listOf("b" to null), now)
        assertEquals(1, second.size)
        assertNull(second.dateOf("a"))
    }

    @Test fun savesAndReadsBack() {
        val dates = AddedDates.merge(null, (1..500).map { "http://x/movie/$it.mp4" to 1_700_000_000L + it }, now)
        val bytes = ByteArrayOutputStream().also { dates.write(it) }.toByteArray()
        val back = AddedDates.read(ByteArrayInputStream(bytes))
        assertEquals(500, back.size)
        assertEquals(1_700_000_123L, back.dateOf("http://x/movie/123.mp4"))
    }
}
