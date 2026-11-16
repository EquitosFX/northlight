package app.tvplayer.core.seasonal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class ChristmasTest {
    @Test
    fun season() {
        assertTrue(Christmas.inSeason(LocalDate.of(2026, 11, 1)))
        assertTrue(Christmas.inSeason(LocalDate.of(2026, 12, 25)))
        assertTrue(Christmas.inSeason(LocalDate.of(2027, 1, 6)))
        assertFalse(Christmas.inSeason(LocalDate.of(2027, 1, 7)))
        assertFalse(Christmas.inSeason(LocalDate.of(2026, 10, 31)))
        assertFalse(Christmas.inSeason(LocalDate.of(2026, 7, 4)))
    }

    @Test
    fun festiveTitles() {
        listOf("Elf (2003)", "A Christmas Carol", "The Santa Clause 2", "How the Grinch Stole Christmas", "Last Xmas", "Arthur Christmas")
            .forEach { if (!Christmas.looksFestive(it)) fail("$it should count as a Christmas title") }
        listOf("Elfrida's Garden", "Santana Live", "Noelle", "The Long Night", "Shelf Life", "Elfin Knight")
            .forEach { if (Christmas.looksFestive(it)) fail("$it shouldn't count as a Christmas title") }
    }

    @Test
    fun discoverUrl() {
        val url = Christmas.discoverUrl("movie", 2)
        assertTrue(url.contains("/discover/movie?"))
        assertTrue(url.contains("with_keywords=207317"))
        assertEquals(1, Regex("page=2").findAll(url).count())
    }
}
