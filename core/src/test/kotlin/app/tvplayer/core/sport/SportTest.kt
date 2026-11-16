package app.tvplayer.core.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportTest {
    @Test
    fun sortsProgrammesIntoSports() {
        assertEquals(SportKind.FOOTBALL, SportFinder.classify("Live Premier League: Arsenal v Chelsea", "Sport", true))
        assertEquals(SportKind.DARTS, SportFinder.classify("Live Premier League Darts", null, true))
        assertEquals(SportKind.RUGBY, SportFinder.classify("Live Super League: Wigan v Leeds", null, true))
        assertEquals(SportKind.MOTORSPORT, SportFinder.classify("F1: Brazilian Grand Prix", null, false))
        assertEquals(SportKind.CRICKET, SportFinder.classify("The Ashes: Day 2", "Sport", false))
        assertEquals(SportKind.OTHER, SportFinder.classify("Live: Leicester v Ospreys", null, true))
        assertEquals(SportKind.FIGHTING, SportFinder.classify("UFC 310: Prelims", null, false))
    }

    @Test
    fun leavesOutNonSport() {
        assertNull(SportFinder.classify("EastEnders", "Soap", false))
        assertNull(SportFinder.classify("Sky Sports News", "Sport", true))
        assertNull(SportFinder.classify("The Championship Bakers", "Entertainment", false))
        assertNull(SportFinder.classify("Coronation Street", null, true))
    }

    @Test
    fun spotsLiveMatchesAndSportsChannels() {
        assertTrue(SportFinder.isLive("LIVE: Wolves v Fulham"))
        assertFalse(SportFinder.isLive("Olivers Twist"))
        assertTrue(SportFinder.isMatch("Wolves v Fulham"))
        assertFalse(SportFinder.isMatch("Vote Now"))
        assertEquals("Wolves v Fulham", SportFinder.cleanTitle("LIVE: Wolves v Fulham"))
        assertEquals("Boxing: Fight Night", SportFinder.cleanTitle("Live Boxing: Fight Night"))
        assertEquals("Liverpool v Spurs", SportFinder.cleanTitle("Liverpool v Spurs"))
        assertTrue(SportFinder.isSportsChannel("UK: TNT Sports 1 HD"))
        assertTrue(SportFinder.isSportsChannel("Channel 7", "UK Sports"))
        assertFalse(SportFinder.isSportsChannel("BBC One"))
    }
}
