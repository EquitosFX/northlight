package app.tvplayer.core.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroDbTest {
    @Test
    fun buildsAddresses() {
        assertEquals("https://api.theintrodb.org/v4/media?tmdb_id=42", IntroDb.url(42))
        assertEquals("https://api.theintrodb.org/v4/media?tmdb_id=42&season=1&episode=3", IntroDb.url(42, 1, 3))
    }

    @Test
    fun readsSegmentsWithOpenEnds() {
        val text = """{"tmdb_id":42,"intro":[{"start_ms":60000,"end_ms":95000}],
            "recap":[{"start_ms":null,"end_ms":40000}],
            "credits":[{"start_ms":2400000,"end_ms":null}],"preview":[]}"""
        val s = IntroDb.parse(text)
        assertEquals(listOf(Segment(60_000, 95_000)), s.intro)
        assertEquals(listOf(Segment(null, 40_000)), s.recap)
        assertEquals(listOf(Segment(2_400_000, null)), s.credits)
        assertTrue(s.preview.isEmpty())
    }

    @Test
    fun offersTheRightSkip() {
        val s = IntroSegments(intro = listOf(Segment(60_000, 95_000)), recap = listOf(Segment(null, 40_000)))
        assertEquals(SkipOffer("Skip recap", 40_000), IntroDb.offer(s, 5_000, 2_600_000))
        assertNull(IntroDb.offer(s, 50_000, 2_600_000))
        assertEquals(SkipOffer("Skip intro", 95_000), IntroDb.offer(s, 70_000, 2_600_000))
        assertNull(IntroDb.offer(s, 95_000, 2_600_000))
    }

    @Test
    fun findsWhenCreditsStart() {
        val s = IntroSegments(credits = listOf(Segment(2_400_000, null)))
        assertEquals(2_400_000L, IntroDb.creditsStart(s, 2_600_000))
        assertEquals(2_570_000L, IntroDb.creditsStart(IntroSegments(), 2_600_000))
        assertNull(IntroDb.creditsStart(s, 0))
        assertTrue(IntroDb.parse("not json").isEmpty)
    }
}
