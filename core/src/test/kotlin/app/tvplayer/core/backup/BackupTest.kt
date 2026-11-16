package app.tvplayer.core.backup

import app.tvplayer.core.mylist.Reminder
import app.tvplayer.core.mylist.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    @Test
    fun roundTrips() {
        val data = BackupData(
            createdAt = 42,
            playlists = listOf(
                PlaylistBackup("Home", "xtream", "http://server:8080", "user", "pass", "http://server/xmltv.php"),
                PlaylistBackup("Free", "m3u", "http://list.m3u"),
            ),
            settings = mapOf("theme" to "skyfall", "tmdb_key" to "abc"),
            favourites = listOf("1|bbc one", "2|itv"),
            watchlist = listOf(WatchItem("movie", "Low Tide", null, "http://s/1.mkv", 5)),
            reminders = listOf(Reminder("1|bbc one", "BBC One", null, "The Quiz", 1, 2)),
        )
        assertEquals(data, BackupCodec.decode(BackupCodec.encode(data)))
    }

    @Test
    fun rejectsOtherFiles() {
        try {
            BackupCodec.decode("""{"hello":1}""")
            fail("should reject")
        } catch (e: BackupException) {
            assertEquals("That isn't a Skyfall backup file.", e.message)
        }
        try {
            BackupCodec.decode("""{"format":"skyfall-backup","version":9}""")
            fail("should reject")
        } catch (e: BackupException) {
            assertEquals("That backup comes from a newer Skyfall. Update this one first.", e.message)
        }
    }
}
