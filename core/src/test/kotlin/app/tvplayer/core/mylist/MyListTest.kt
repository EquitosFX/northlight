package app.tvplayer.core.mylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyListTest {
    private val r = Reminder("1|bbc one", "BBC One", null, "The Quiz", 1_000_000, 4_600_000, "A quiz.")

    @Test
    fun remindersSurviveTheRoundTrip() {
        val list = listOf(r, r.copy(channelKey = "2|itv", channelLogo = "http://logo", description = null))
        assertEquals(list, MyListJson.decodeReminders(MyListJson.encodeReminders(list)))
        assertTrue(MyListJson.decodeReminders("not json").isEmpty())
        assertTrue(MyListJson.decodeReminders(null).isEmpty())
    }

    @Test
    fun watchlistSurvivesTheRoundTrip() {
        val list = listOf(WatchItem("movie", "Low Tide", "http://p", "http://s/1.mkv", 5), WatchItem("series", "Harbour", null, "", 6))
        assertEquals(list, MyListJson.decodeWatchlist(MyListJson.encodeWatchlist(list)))
    }

    @Test
    fun remindersComeDueAMinuteBefore() {
        val list = listOf(r)
        assertTrue(Reminders.due(list, r.start - 120_000).isEmpty())
        assertEquals(listOf(r), Reminders.due(list, r.start - 30_000))
        assertEquals(listOf(r), Reminders.due(list, r.start + 60_000))
        assertTrue(Reminders.due(list, r.start + 10 * 60_000).isEmpty())
        assertTrue(Reminders.upcoming(list, r.stop + 1).isEmpty())
        assertTrue(Reminders.toggle(list, r).isEmpty())
        assertEquals(2, Reminders.toggle(list, r.copy(start = 9)).size)
    }
}
