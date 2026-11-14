package app.tvplayer.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgMatcherTest {

    private val guide = listOf(
        GuideChannel("North.One.uk", listOf("North One", "North 1"), null),
        GuideChannel("metro.news", listOf("Metro News"), null),
        GuideChannel("arena.sport.1", listOf("Arena Sport 1"), null),
        GuideChannel("arena.sport.2", listOf("Arena Sport 2"), null),
    )

    private fun matchOne(channel: ChannelRef): GuideMatch? = EpgMatcher.match(listOf(channel), guide).single()

    @Test
    fun matchesByGuideIdIgnoringCase() {
        val match = matchOne(ChannelRef(name = "Something Else", tvgId = "north.one.UK"))
        assertEquals(GuideMatch("North.One.uk", MatchMethod.TVG_ID), match)
    }

    @Test
    fun fallsBackToGuideNameWhenTheIdIsUnknown() {
        val match = matchOne(ChannelRef(name = "Ch 3", tvgId = "not-in-guide", tvgName = "Metro News"))
        assertEquals(GuideMatch("metro.news", MatchMethod.TVG_NAME), match)
    }

    @Test
    fun matchesByNameIgnoringCountryPrefixAndQuality() {
        assertEquals(
            GuideMatch("North.One.uk", MatchMethod.NAME),
            matchOne(ChannelRef(name = "UK: North One FHD")),
        )
        assertEquals(
            GuideMatch("arena.sport.2", MatchMethod.NAME),
            matchOne(ChannelRef(name = "UK | Arena Sport 2 HD (Backup)")),
        )
    }

    @Test
    fun matchesAnyOfTheGuidesNamesForAChannel() {
        assertEquals("North.One.uk", matchOne(ChannelRef(name = "North 1 HD"))?.guideId)
    }

    @Test
    fun leavesUnknownChannelsUnmatched() {
        assertNull(matchOne(ChannelRef(name = "Wild Hours")))
        assertNull(matchOne(ChannelRef(name = "", tvgId = "", tvgName = "")))
    }

    @Test
    fun returnsOneResultPerChannelInOrder() {
        val results = EpgMatcher.match(
            listOf(ChannelRef("Metro News"), ChannelRef("Nope"), ChannelRef("Arena Sport 1 4K")),
            guide,
        )
        assertEquals(listOf("metro.news", null, "arena.sport.1"), results.map { it?.guideId })
    }
}
