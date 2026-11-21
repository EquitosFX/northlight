package app.tvplayer.ui.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The guide says which day a programme is on when it isn't today, and marks where each day starts. */
class GuideDaysTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 11, 21)
    private val now = at(today, 17, 30)

    @Test fun programmesOnOtherDaysSayWhich() {
        assertNull(relativeDay(at(today, 21), now))
        assertEquals("Tomorrow", relativeDay(at(today.plusDays(1), 6), now))
        assertEquals("Yesterday", relativeDay(at(today.minusDays(1), 22), now))
        assertTrue(relativeDay(at(today.plusDays(3), 9), now)!!.contains("24"))
    }

    @Test fun midnightIsWhereADayStarts() {
        assertTrue(isMidnight(at(today.plusDays(1), 0)))
        assertFalse(isMidnight(at(today, 23, 30)))
        assertFalse(isMidnight(at(today.plusDays(1), 0, 30)))
    }
}
