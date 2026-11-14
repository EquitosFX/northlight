package app.tvplayer.core.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelVariantsTest {

    @Test
    fun readsQualityFromTheName() {
        assertEquals(Quality.HD, ChannelVariants.analyse("North One HD").quality)
        assertEquals(Quality.FHD, ChannelVariants.analyse("North One FHD").quality)
        assertEquals(Quality.UHD, ChannelVariants.analyse("North One 4K HEVC").quality)
        assertEquals(Quality.SD, ChannelVariants.analyse("North One SD").quality)
        assertEquals(Quality.UNKNOWN, ChannelVariants.analyse("North One").quality)
    }

    @Test
    fun removesMarkersFromTheBaseName() {
        assertEquals("North One", ChannelVariants.analyse("North One HD").baseName)
        assertEquals("North One", ChannelVariants.analyse("North One FHD (Backup)").baseName)
        assertEquals("UK North One", ChannelVariants.analyse("UK | North One 4K HEVC").baseName)
        assertEquals("Arena Sport 1", ChannelVariants.analyse("Arena Sport 1 [1080p] 50fps").baseName)
    }

    @Test
    fun spotsBackups() {
        assertTrue(ChannelVariants.analyse("North One FHD (Backup)").isBackup)
        assertTrue(ChannelVariants.analyse("North One HD backup2").isBackup)
        assertFalse(ChannelVariants.analyse("North One HD").isBackup)
    }

    @Test
    fun keepsANameThatIsOnlyAMarker() {
        assertEquals("HD", ChannelVariants.analyse("HD").baseName)
    }

    @Test
    fun groupsCopiesBestFirstWithBackupsLast() {
        val names = listOf(
            "North One SD",
            "North One HD",
            "Metro News",
            "North One FHD",
            "North One 4K Backup",
            "North One +1",
            "north one hd",
        )
        val groups = ChannelVariants.group(names) { it }

        assertEquals(listOf("North One", "Metro News", "North One +1"), groups.map { it.baseName })
        assertEquals(
            listOf("North One FHD", "North One HD", "north one hd", "North One SD", "North One 4K Backup"),
            groups[0].variants,
        )
        assertEquals(listOf("Metro News"), groups[1].variants)
        assertEquals(listOf("North One +1"), groups[2].variants)
    }

    @Test
    fun keepsNumberedChannelsApart() {
        val groups = ChannelVariants.group(listOf("Arena Sport 1 HD", "Arena Sport 2 HD")) { it }
        assertEquals(2, groups.size)
    }
}
