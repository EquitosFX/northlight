package app.tvplayer.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineupTest {

    private fun row(
        id: Long,
        name: String,
        quality: Int = 0,
        backup: Boolean = false,
        playlist: Long = 1,
        group: String? = null,
        number: Int? = null,
        logo: String? = null,
    ) = LineupRow(
        id = id,
        playlistId = playlist,
        name = name,
        variantKey = name.lowercase().replace(" ", ""),
        quality = quality,
        isBackup = backup,
        logo = logo,
        group = group,
        number = number,
    )

    @Test
    fun mergesCopiesBestFirstWithBackupsLast() {
        val lineup = Lineup.build(
            listOf(
                row(1, "North One", quality = 1),
                row(2, "North One", quality = 4, backup = true),
                row(3, "Metro News"),
                row(4, "North One", quality = 3),
                row(5, "North One", quality = 2),
            ),
        )
        assertEquals(listOf("North One", "Metro News"), lineup.channels.map { it.name })
        assertEquals(listOf(4L, 5L, 1L, 2L), lineup.channels[0].variantIds)
    }

    @Test
    fun keepsTheSameChannelFromTwoPlaylistsApart() {
        val lineup = Lineup.build(listOf(row(1, "North One", playlist = 1), row(2, "North One", playlist = 2)))
        assertEquals(2, lineup.channels.size)
        assertEquals("1|northone", lineup.channels[0].key)
        assertEquals("2|northone", lineup.channels[1].key)
    }

    @Test
    fun numbersComeFromThePlaylistOrFromPosition() {
        val lineup = Lineup.build(
            listOf(row(1, "North One", number = 101), row(2, "Metro News"), row(3, "Comedy Yard", number = 106)),
        )
        assertEquals(listOf(101, 2, 106), lineup.channels.map { it.number })
    }

    @Test
    fun takesTheFirstLogoAnyCopyHas() {
        val lineup = Lineup.build(
            listOf(row(1, "North One", logo = ""), row(2, "North One", logo = "http://l.example/n1.png")),
        )
        assertEquals("http://l.example/n1.png", lineup.channels.single().logo)
    }

    @Test
    fun listsGroupsInOrderAndFiltersByThem() {
        val lineup = Lineup.build(
            listOf(
                row(1, "North One", group = "Entertainment"),
                row(2, "Metro News", group = "News"),
                row(3, "Comedy Yard", group = "Entertainment"),
                row(4, "Ungrouped"),
            ),
        )
        assertEquals(listOf("Entertainment", "News"), lineup.groups)
        assertEquals(listOf("North One", "Comedy Yard"), lineup.inGroup("Entertainment").map { it.name })
        assertEquals(4, lineup.inGroup(null).size)
        assertTrue(lineup.inGroup("Nope").isEmpty())
    }

    @Test
    fun findsChannelsByAnyCopyOrByKey() {
        val lineup = Lineup.build(listOf(row(1, "North One"), row(2, "North One", quality = 3), row(3, "Metro News")))
        assertEquals("North One", lineup.findById(1)?.name)
        assertEquals("North One", lineup.findById(2)?.name)
        assertEquals("Metro News", lineup.findByKey("1|metronews")?.name)
        assertNull(lineup.findById(99))
        assertNull(lineup.findByKey("2|metronews"))
    }

    @Test
    fun stepsWrapAroundAtBothEnds() {
        assertEquals(1, Lineup.step(5, 0, 1))
        assertEquals(0, Lineup.step(5, 4, 1))
        assertEquals(4, Lineup.step(5, 0, -1))
        assertEquals(0, Lineup.step(1, 0, -1))
        assertEquals(-1, Lineup.step(0, 0, 1))
    }

    @Test
    fun anEmptyLineupIsEmpty() {
        assertTrue(Lineup.build(emptyList()).isEmpty)
        assertTrue(Lineup.EMPTY.groups.isEmpty())
    }

    @Test
    fun reconnectWaitsLongerEachTime() {
        assertEquals(1000L, Reconnect.delayMillis(1))
        assertEquals(2000L, Reconnect.delayMillis(2))
        assertEquals(4000L, Reconnect.delayMillis(3))
        assertEquals(8000L, Reconnect.delayMillis(10))
        assertEquals(1000L, Reconnect.delayMillis(0))
    }
}
