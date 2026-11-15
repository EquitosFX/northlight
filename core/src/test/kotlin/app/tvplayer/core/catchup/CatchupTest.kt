package app.tvplayer.core.catchup

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    // 15 Nov 2026 18:30–20:00 UTC, watched at 21:00 UTC.
    private val window = CatchupWindow(startMs = 1_794_767_400_000, endMs = 1_794_772_800_000, nowMs = 1_794_776_400_000)

    @Test
    fun xtreamUsesTheServersLocalTime() {
        assertEquals(
            "http://host.example:8080/timeshift/user/pass/90/2026-11-15:18-30/1234.ts",
            Catchup.url("http://host.example:8080/live/user/pass/1234.ts", "xc", null, window, ZoneId.of("Europe/London")),
        )
        assertEquals(
            "http://host.example:8080/timeshift/user/pass/90/2026-11-15:19-30/1234.ts",
            Catchup.url("http://host.example:8080/user/pass/1234", null, null, window, ZoneId.of("Europe/Paris")),
        )
    }

    @Test
    fun fillsTemplates() {
        assertEquals(
            "http://c.example/ch1/index.m3u8?start=1794767400&end=1794772800&dur=5400",
            Catchup.url("http://c.example/ch1/live.m3u8", "default", "http://c.example/ch1/index.m3u8?start={utc}&end={utcend}&dur={duration}", window),
        )
        assertEquals(
            "http://c.example/live/ch1.ts?archive=1794767400&min=90&ago=150",
            Catchup.url("http://c.example/live/ch1.ts", "append", "?archive=\${start}&min={duration:60}&ago={offset:60}", window),
        )
        assertEquals(
            "http://c.example/rec/20261115183000.ts",
            Catchup.fill("http://c.example/rec/{utc:YmdHMS}.ts", window),
        )
        assertEquals("http://c.example/2026/11/15/18/30", Catchup.fill("http://c.example/{Y}/{m}/{d}/{H}/{M}", window))
    }

    @Test
    fun shiftAndFlussonic() {
        assertEquals("http://c.example/ch1.m3u8?token=x&utc=1794767400&lutc=1794776400", Catchup.url("http://c.example/ch1.m3u8?token=x", "shift", null, window))
        assertEquals("http://f.example/ch1/timeshift_abs-1794767400.m3u8", Catchup.url("http://f.example/ch1/index.m3u8", "flussonic", null, window))
        assertEquals("http://f.example/ch1/timeshift_abs_mpegts-1794767400.ts?t=1", Catchup.url("http://f.example/ch1/mpegts?t=1", "fs", null, window))
    }

    @Test
    fun knowsWhatCanBeWatchedAgain() {
        val now = window.nowMs
        assertTrue(Catchup.available(7, window.startMs, window.endMs, now))
        assertFalse(Catchup.available(null, window.startMs, window.endMs, now))
        assertFalse(Catchup.available(0, window.startMs, window.endMs, now))
        assertFalse(Catchup.available(1, now - 3 * 86_400_000L, now - 3 * 86_400_000L + 3_600_000, now))
        assertFalse(Catchup.available(7, now + 3_600_000, now + 7_200_000, now))
    }
}
