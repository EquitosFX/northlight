package app.tvplayer.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    private fun rank(name: String, search: String) = SearchText.rank(SearchText.condense(name), SearchText.Query(search))

    @Test fun spacesAndPunctuationDontMatter() {
        assertTrue(rank("Spider-Man: No Way Home", "spiderman") > 0)
        assertTrue(rank("Spider-Man: No Way Home", "spider man") > 0)
        assertTrue(rank("Ghostbusters", "ghost busters") > 0)
        assertTrue(rank("Ghost Busters II", "ghostbusters") > 0)
        assertTrue(rank("Mission: Impossible - Dead Reckoning", "mission impossible dead") > 0)
        assertTrue(rank("WALL·E", "walle") > 0)
    }

    @Test fun capitalsAccentsAndAmpersands() {
        assertTrue(rank("Amélie", "amelie") > 0)
        assertTrue(rank("Fast & Furious", "fast and furious") > 0)
        assertTrue(rank("Law and Order", "law & order") > 0)
    }

    @Test fun startsWithRanksAboveContains() {
        assertEquals(3, rank("The Lantern Coast", "the lantern"))
        assertEquals(2, rank("The Lantern Coast", "lantern coast"))
        assertEquals(1, rank("The Lantern Coast", "coast lantern"))
        assertEquals(0, rank("The Lantern Coast", "harbour"))
    }

    @Test fun tooShortToSearch() {
        assertEquals(false, SearchText.Query(" - ").usable)
        assertEquals(true, SearchText.Query("Up").usable)
    }
}
