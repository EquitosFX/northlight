package app.tvplayer.core.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNamesTest {

    @Test
    fun readsTheCommonShapes() {
        assertEquals(EpisodeName("Hollow Fen", 1, 3, null), EpisodeNames.parse("Hollow Fen S01 E03"))
        assertEquals(EpisodeName("Hollow Fen", 1, 3, "The Flood"), EpisodeNames.parse("Hollow Fen S01E03 The Flood"))
        assertEquals(EpisodeName("Hollow Fen", 2, 10, "Reckoning"), EpisodeNames.parse("Hollow Fen - S2 E10 - Reckoning"))
        assertEquals(EpisodeName("Hollow Fen", 1, 3, null), EpisodeNames.parse("Hollow.Fen.s01.e03"))
        assertEquals(EpisodeName("Hollow Fen", 4, 12, null), EpisodeNames.parse("Hollow Fen 4x12"))
        assertEquals(EpisodeName("EN - Hollow Fen (2023)", 1, 1, null), EpisodeNames.parse("EN - Hollow Fen (2023) S01 E01"))
    }

    @Test
    fun leavesOtherNamesAlone() {
        assertNull(EpisodeNames.parse("Signal Lost"))
        assertNull(EpisodeNames.parse("S01E01"))
        assertNull(EpisodeNames.parse("Area 51"))
        assertNull(EpisodeNames.parse("Mission 1x2"))
    }

    @Test
    fun episodesOfOneShowShareAKey() {
        assertEquals(EpisodeNames.showKey("Hollow Fen S01 E01"), EpisodeNames.showKey("hollow.fen.S02E05"))
        assertEquals("hollow fen", EpisodeNames.showKey("Hollow Fen"))
    }
}
