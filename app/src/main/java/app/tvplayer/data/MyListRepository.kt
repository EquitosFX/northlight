package app.tvplayer.data

import android.content.Context
import app.tvplayer.core.mylist.MyListJson
import app.tvplayer.core.mylist.Reminder
import app.tvplayer.core.mylist.Reminders
import app.tvplayer.core.mylist.WatchItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The viewer's reminders and watchlist, kept on the device (and in backups). Reminders for
 * programmes that have finished are dropped.
 */
class MyListRepository(context: Context) {
    private val prefs = context.getSharedPreferences("my_list", Context.MODE_PRIVATE)

    private val reminderState = MutableStateFlow(
        Reminders.upcoming(MyListJson.decodeReminders(prefs.getString(REMINDERS, null)), System.currentTimeMillis() - HOUR),
    )
    val reminders: StateFlow<List<Reminder>> = reminderState.asStateFlow()

    private val watchState = MutableStateFlow(MyListJson.decodeWatchlist(prefs.getString(WATCHLIST, null)))
    val watchlist: StateFlow<List<WatchItem>> = watchState.asStateFlow()

    fun hasReminder(id: String): Boolean = reminderState.value.any { it.id == id }

    /** Sets the reminder, or cancels it if it's already set. Returns true if it's now set. */
    fun toggleReminder(reminder: Reminder): Boolean {
        val list = Reminders.toggle(reminderState.value, reminder)
        saveReminders(list)
        return list.any { it.id == reminder.id }
    }

    fun removeReminder(id: String) = saveReminders(reminderState.value.filterNot { it.id == id })

    fun inWatchlist(key: String): Boolean = watchState.value.any { it.key == key }

    /** Adds the title, or takes it off if it's already there. Returns true if it's now on the list. */
    fun toggleWatch(item: WatchItem): Boolean {
        val list = if (watchState.value.any { it.key == item.key }) {
            watchState.value.filterNot { it.key == item.key }
        } else {
            listOf(item) + watchState.value
        }
        saveWatchlist(list)
        return list.any { it.key == item.key }
    }

    /** Replaces everything (from a backup). */
    fun restore(reminders: List<Reminder>, watchlist: List<WatchItem>) {
        saveReminders(Reminders.upcoming(reminders, System.currentTimeMillis()))
        saveWatchlist(watchlist)
    }

    private fun saveReminders(list: List<Reminder>) {
        val sorted = list.sortedBy { it.start }
        reminderState.value = sorted
        prefs.edit().putString(REMINDERS, MyListJson.encodeReminders(sorted)).apply()
    }

    private fun saveWatchlist(list: List<WatchItem>) {
        watchState.value = list
        prefs.edit().putString(WATCHLIST, MyListJson.encodeWatchlist(list)).apply()
    }

    private companion object {
        const val REMINDERS = "reminders"
        const val WATCHLIST = "watchlist"
        const val HOUR = 3_600_000L
    }
}
