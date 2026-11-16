package app.tvplayer.ui.mylist

import app.tvplayer.ui.components.LocalNewEpisodes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tvplayer.core.mylist.Reminder
import app.tvplayer.core.mylist.WatchItem
import app.tvplayer.data.MyListRepository
import app.tvplayer.data.db.VodRow
import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.components.LocalHoldFocus
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.components.clockTime
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import app.tvplayer.ui.vod.PosterCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** My list: films and series saved for later, and the programmes with reminders set. */
@Composable
fun MyListScreen(
    myList: MyListRepository,
    onOpenTitle: (WatchItem) -> Unit,
    onWatchChannel: (String) -> Unit,
) {
    val colors = AppTheme.colors
    val watchlist by myList.watchlist.collectAsStateWithLifecycle()
    val newEpisodes = LocalNewEpisodes.current.keys
    val reminders by myList.reminders.collectAsStateWithLifecycle()
    val holdFocus = LocalHoldFocus.current
    val first = remember { FocusRequester() }
    EntryPoint(first)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (autoFocus()) runCatching { first.requestFocus() }
    }
    val now = System.currentTimeMillis()
    val upcoming = reminders.filter { it.stop > now }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 32.dp, top = 22.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Label(text = "My list", style = AppType.display.copy(fontSize = 32.sp, lineHeight = 38.sp))
        }
        item {
            Label(text = "Watchlist", style = AppType.headline, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            if (watchlist.isEmpty()) {
                Label(
                    text = "Nothing saved yet. On a film or series page, choose “+ My list”.",
                    style = AppType.body,
                    color = colors.textDim,
                    modifier = Modifier.then(if (upcoming.isEmpty()) Modifier.focusRequester(first) else Modifier),
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp)) {
                    itemsIndexed(watchlist, key = { _, w -> w.key }) { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            PosterCard(
                                row = VodRow(0, 0, item.kind, item.name, "", item.poster, item.url, null, null, null),
                                onClick = {
                                    holdFocus()
                                    onOpenTitle(item)
                                },
                                modifier = Modifier.width(118.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                                newEpisodes = item.key in newEpisodes,
                            )
                            Label(text = if (item.kind == "series") "Series" else "Film", style = AppType.caption, color = colors.textFaint)
                        }
                    }
                }
            }
        }
        item {
            Label(text = "Reminders", style = AppType.headline, modifier = Modifier.padding(top = 14.dp))
        }
        if (upcoming.isEmpty()) {
            item {
                Label(
                    text = "No reminders. In the TV guide or Sport, OK on a programme still to come sets one.",
                    style = AppType.body,
                    color = colors.textDim,
                )
            }
        }
        items(upcoming, key = { it.id }) { reminder ->
            ReminderRow(
                reminder = reminder,
                now = now,
                modifier = if (watchlist.isEmpty() && reminder == upcoming.first()) Modifier.focusRequester(first) else Modifier,
                onWatch = { onWatchChannel(reminder.channelKey) },
                onRemove = {
                    holdFocus()
                    myList.removeReminder(reminder.id)
                },
            )
        }
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, now: Long, modifier: Modifier, onWatch: () -> Unit, onRemove: () -> Unit) {
    val colors = AppTheme.colors
    val onNow = now in reminder.start until reminder.stop
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusSurface(
            onClick = { if (onNow) onWatch() },
            modifier = modifier.width(620.dp).height(62.dp),
            shape = RoundedCornerShape(10.dp),
            focusScale = 1.02f,
        ) { focused ->
            Row(
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.width(110.dp)) {
                    Label(text = dayText(reminder.start, now), style = AppType.label, color = if (onNow) colors.accent else colors.textDim)
                    Label(text = "${clockTime(reminder.start)}–${clockTime(reminder.stop)}", style = AppType.title)
                }
                Logo(url = reminder.channelLogo, modifier = Modifier.size(width = 44.dp, height = 30.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Label(text = reminder.title, style = AppType.title.copy(fontSize = 16.sp), maxLines = 1)
                    Label(
                        text = reminder.channelName + if (onNow && focused) "  ·  On now · OK to watch" else "",
                        style = AppType.label,
                        color = if (onNow) colors.accent else colors.textDim,
                        maxLines = 1,
                    )
                }
            }
        }
        FocusSurface(onClick = onRemove, modifier = Modifier.height(40.dp), shape = RoundedCornerShape(20.dp)) { focused ->
            Label(
                text = "Remove",
                style = AppType.label,
                color = if (focused) colors.text else colors.textDim,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            )
        }
    }
}

private fun dayText(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> if (now >= at) "On now" else "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK))
    }
}
