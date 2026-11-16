package app.tvplayer.ui.sport

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tvplayer.core.live.LineupChannel
import app.tvplayer.core.sport.SportKind
import app.tvplayer.data.ChannelsRepository
import app.tvplayer.data.SportEvent
import app.tvplayer.ui.components.EntryPoint
import app.tvplayer.ui.components.FocusSurface
import app.tvplayer.ui.components.Label
import app.tvplayer.ui.components.LocalAutoFocus
import app.tvplayer.ui.components.Logo
import app.tvplayer.ui.components.clockTime
import app.tvplayer.ui.theme.AppTheme
import app.tvplayer.ui.theme.AppType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sport: what's on now and coming up in the next few days, found in the TV guide, with the channels
 * showing each event. OK on something live watches it; on something to come, sets a reminder.
 */
@Composable
fun SportScreen(
    channels: ChannelsRepository,
    reminders: Set<String>,
    onWatch: (LineupChannel) -> Unit,
    onRemind: (SportEvent, LineupChannel) -> Unit,
) {
    val colors = AppTheme.colors
    val events by produceState(initialValue = channels.cachedSport()) { value = channels.sport() }
    var filter by remember { mutableStateOf<SportKind?>(null) }
    val first = remember { FocusRequester() }
    EntryPoint(first)
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(events != null) {
        withFrameNanos { }
        if (autoFocus()) runCatching { first.requestFocus() }
    }
    val list = events
    val now = System.currentTimeMillis()
    val kinds = list.orEmpty().map { it.kind }.distinct().sortedBy { it.ordinal }
    val shown = list.orEmpty().filter { filter == null || it.kind == filter }
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun section(e: SportEvent): String {
        if (now in e.start until e.stop) return "On now"
        val day = Instant.ofEpochMilli(e.start).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Later today"
            today.plusDays(1) -> "Tomorrow"
            else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK))
        }
    }
    val sections = shown.groupBy(::section)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 24.dp, top = 22.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Label(text = "Sport", style = AppType.display.copy(fontSize = 32.sp, lineHeight = 38.sp)) }
        item {
            Label(
                text = "Live and upcoming sport from your TV guide. OK on something live to watch it, or on something to come to set a reminder.",
                style = AppType.body,
                color = colors.textDim,
                maxLines = 2,
            )
        }
        if (list == null) {
            item { Label(text = "Looking through the guide…", style = AppType.title, color = colors.textDim) }
            return@LazyColumn
        }
        if (list.isEmpty()) {
            item {
                Label(
                    text = "No sport found in the guide for the next few days. Sport shows up here once your playlists have a TV guide.",
                    style = AppType.title,
                    color = colors.textDim,
                    maxLines = 2,
                    modifier = Modifier.focusRequester(first),
                )
            }
            return@LazyColumn
        }
        // Filter by sport.
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
                item {
                    FilterChip("All sport", filter == null, Modifier.focusRequester(first)) { filter = null }
                }
                items(kinds) { kind ->
                    FilterChip(kind.label, filter == kind) { filter = kind }
                }
            }
        }
        sections.forEach { (title, sectionEvents) ->
            item(key = "h$title") {
                Label(text = title, style = AppType.headline, modifier = Modifier.padding(top = 8.dp))
            }
            item(key = "r$title") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp)) {
                    itemsIndexed(sectionEvents, key = { _, e -> e.title + e.start }) { _, event ->
                        val reminded = event.channels.any { "${it.key}|${event.start}" in reminders }
                        EventCard(
                            event = event,
                            now = now,
                            reminded = reminded,
                            onClick = {
                                val channel = event.channels.first()
                                if (now >= event.start) onWatch(channel) else onRemind(event, channel)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = AppTheme.colors
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(18.dp),
        container = if (selected) colors.glassStrong else Color.Transparent,
        focusedContainer = colors.accent,
    ) { focused ->
        Label(
            text = text,
            style = AppType.title,
            color = if (focused) colors.onAccent else if (selected) colors.text else colors.textDim,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun EventCard(event: SportEvent, now: Long, reminded: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    val onNow = now in event.start until event.stop
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.width(300.dp).height(138.dp),
        shape = RoundedCornerShape(12.dp),
        container = Color(0xFF14213D).copy(alpha = 0.85f),
        focusScale = 1.04f,
    ) { focused ->
        // A coloured edge for the sport.
        Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxSize().background(kindColour(event.kind)))
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(text = event.kind.label.uppercase(), style = AppType.caption.copy(letterSpacing = 1.sp), color = kindColour(event.kind))
                if (onNow && event.live) Label(text = "LIVE", style = AppType.caption.copy(letterSpacing = 1.sp), color = Color(0xFFFF5A5F))
                if (reminded) Label(text = "REMINDER SET", style = AppType.caption.copy(letterSpacing = 1.sp), color = colors.accent)
            }
            Label(text = event.title, style = AppType.title.copy(fontSize = 16.sp, lineHeight = 20.sp), maxLines = 2)
            Label(
                text = if (onNow) "Until ${clockTime(event.stop)}" else "${clockTime(event.start)}–${clockTime(event.stop)}",
                style = AppType.label,
                color = if (onNow) colors.accent else colors.textDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val channel = event.channels.first()
                Logo(url = channel.logo, modifier = Modifier.size(width = 30.dp, height = 20.dp))
                Label(
                    text = channel.name + if (event.channels.size > 1) "  +${event.channels.size - 1} more" else "",
                    style = AppType.label,
                    color = colors.textDim,
                    maxLines = 1,
                )
            }
            if (focused) {
                Label(
                    text = when {
                        onNow -> "OK to watch"
                        reminded -> "OK to cancel the reminder"
                        else -> "OK to set a reminder"
                    },
                    style = AppType.caption,
                    color = colors.accent,
                )
            }
        }
    }
}

private fun kindColour(kind: SportKind): Color = when (kind) {
    SportKind.FOOTBALL -> Color(0xFF3DDC84)
    SportKind.RUGBY -> Color(0xFFE8A33D)
    SportKind.CRICKET -> Color(0xFFF2E3B3)
    SportKind.MOTORSPORT -> Color(0xFFFF5A5F)
    SportKind.GOLF -> Color(0xFF8BD17C)
    SportKind.TENNIS -> Color(0xFFD7F34B)
    SportKind.FIGHTING -> Color(0xFFFF7A45)
    SportKind.DARTS -> Color(0xFFFF4D8D)
    SportKind.SNOOKER -> Color(0xFF2EC4B6)
    SportKind.AMERICAN_FOOTBALL -> Color(0xFFB07CFF)
    SportKind.BASKETBALL -> Color(0xFFFF9F1C)
    SportKind.ICE_HOCKEY -> Color(0xFF9AD1FF)
    SportKind.BASEBALL -> Color(0xFFFFFFFF)
    SportKind.RACING -> Color(0xFFC49A6C)
    SportKind.CYCLING -> Color(0xFFFFD23F)
    SportKind.OTHER -> Color(0xFF00BEEB)
}
