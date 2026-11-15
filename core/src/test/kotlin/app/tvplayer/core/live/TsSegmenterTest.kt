package app.tvplayer.core.live

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsSegmenterTest {

    /** Keeps slices in memory. */
    private class MemoryStore : SegmentStore {
        val files = LinkedHashMap<Long, ByteArrayOutputStream>()
        var playlist = ""
        var writes = 0
        override fun open(sequence: Long): OutputStream = ByteArrayOutputStream().also { files[sequence] = it }
        override fun delete(sequence: Long) { files.remove(sequence) }
        override fun writePlaylist(text: String) { playlist = text; writes++ }
    }

    private val videoPid = 0x101
    private val audioPid = 0x102

    private fun packet(pid: Int, unitStart: Boolean = false, randomAccess: Boolean = false, pcr: Long = -1, payload: ByteArray = ByteArray(0)): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (((if (unitStart) 0x40 else 0) or (pid shr 8)) and 0xFF).toByte()
        p[2] = (pid and 0xFF).toByte()
        val adaptation = randomAccess || pcr >= 0
        var at = 4
        if (adaptation) {
            p[3] = 0x30
            val afLength = if (pcr >= 0) 7 else 1
            p[4] = afLength.toByte()
            p[5] = ((if (randomAccess) 0x40 else 0) or (if (pcr >= 0) 0x10 else 0)).toByte()
            if (pcr >= 0) {
                val base = pcr / 300; val ext = pcr % 300
                p[6] = (base shr 25).toByte(); p[7] = (base shr 17).toByte(); p[8] = (base shr 9).toByte(); p[9] = (base shr 1).toByte()
                p[10] = (((base and 1) shl 7) or 0x7E or (ext shr 8)).toByte(); p[11] = ext.toByte()
            }
            at = 5 + afLength
        } else {
            p[3] = 0x10
        }
        System.arraycopy(payload, 0, p, at, minOf(payload.size, 188 - at))
        return p
    }

    private fun pat() = packet(0, unitStart = true, payload = byteArrayOf(0, 0x00, 0xB0.toByte(), 13, 0, 1, 0xC1.toByte(), 0, 0, 0, 1, 0xE1.toByte(), 0x00, 0, 0, 0, 0))
    private fun pmt() = packet(0x100, unitStart = true, payload = byteArrayOf(
        0, 0x02, 0xB0.toByte(), 23, 0, 1, 0xC1.toByte(), 0, 0, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00,
        0x0F, 0xE1.toByte(), 0x02, 0xF0.toByte(), 0x00, // AAC audio first
        0x1B, 0xE1.toByte(), 0x01, 0xF0.toByte(), 0x00, // H.264 video
        0, 0, 0, 0,
    ))

    /** [seconds] of a 25 fps stream with a keyframe every [gop] frames, starting at clock [startPcr]. */
    private fun stream(seconds: Int, gop: Int = 25, startPcr: Long = 0): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in 0 until seconds * 25) {
            if (frame % 10 == 0) { out.write(pat()); out.write(pmt()) }
            val pcr = startPcr + frame * 27_000_000L / 25
            out.write(packet(videoPid, unitStart = true, randomAccess = frame % gop == 0, pcr = pcr))
            out.write(packet(videoPid))
            // Audio frames are all marked as random access; slices must not be cut on them.
            out.write(packet(audioPid, unitStart = true, randomAccess = true))
        }
        return out.toByteArray()
    }

    private fun durations(playlist: String) = Regex("#EXTINF:([0-9.]+),").findAll(playlist).map { it.groupValues[1].toDouble() }.toList()

    @Test
    fun cutsOnVideoKeyframesAndTimesFromTheStreamClock() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, minSeconds = 2.0, maxSeconds = 5.0, clock = { 0L })
        val data = stream(20)
        // Arrives in awkward chunk sizes, as it would over the network.
        var i = 0; var n = 1
        while (i < data.size) { val len = minOf((n * 997) % 4096 + 1, data.size - i); seg.feed(data, i, len); i += len; n++ }
        seg.finish()
        val d = durations(store.playlist)
        // First slice is cut at the first keyframe after 0.6 s (1 s), then every 2 s (keyframes each second).
        assertEquals(1.0, d.first(), 0.05)
        d.drop(1).dropLast(1).forEach { assertEquals(2.0, it, 0.05) }
        assertEquals(19.96, d.sum(), 0.1)
        // Every slice begins with the programme tables and then a video keyframe.
        store.files.values.forEach { file ->
            val b = file.toByteArray()
            assertEquals(0, b.size % 188)
            assertEquals(0, ((b[1].toInt() and 0x1F) shl 8) or (b[2].toInt() and 0xFF)) // PAT first
        }
        assertTrue(store.playlist.startsWith("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n"))
        assertFalse(store.playlist.contains("ENDLIST"))
    }

    @Test
    fun forcesACutWhenKeyframesAreRare() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, minSeconds = 2.0, maxSeconds = 5.0, clock = { 0L })
        seg.feed(stream(30, gop = 25 * 12))
        seg.finish()
        val d = durations(store.playlist)
        assertTrue(d.all { it <= 5.1 })
        assertTrue(d.size >= 6)
    }

    @Test
    fun keepsOnlyTheWindowAndDeletesOldSlices() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 10.0, maxBytes = Long.MAX_VALUE, minSeconds = 2.0, maxSeconds = 5.0, clock = { 0L })
        seg.feed(stream(40))
        seg.finish()
        assertTrue(seg.recordedSeconds <= 10.0 + 0.01)
        val first = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(store.playlist)!!.groupValues[1].toLong()
        assertTrue(first > 0)
        assertEquals(store.files.keys.min(), first)
        assertEquals(durations(store.playlist).size, store.files.size)
    }

    @Test
    fun appDefaultsMakeShortSlicesAndAShortTarget() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, clock = { 0L })
        seg.feed(stream(12, gop = 12))
        seg.finish()
        assertEquals(3, seg.targetDuration)
        assertTrue(store.playlist.contains("#EXT-X-TARGETDURATION:3\n"))
        assertTrue(durations(store.playlist).all { it <= 2.5 })
    }

    @Test
    fun capsTheSpaceUsed() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 1e9, maxBytes = 200_000, clock = { 0L })
        seg.feed(stream(60))
        seg.finish()
        assertTrue(store.files.values.sumOf { it.size().toLong() } <= 200_000 + 60_000)
    }

    @Test
    fun marksReconnectsAsDiscontinuities() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, minSeconds = 2.0, maxSeconds = 5.0, clock = { 0L })
        seg.feed(stream(6))
        seg.discontinuity()
        seg.feed(stream(6, startPcr = 999_000_000_000L))
        seg.finish()
        assertEquals(1, Regex("#EXT-X-DISCONTINUITY\n").findAll(store.playlist).count())
        assertTrue(durations(store.playlist).all { it < 5.1 })
    }

    @Test
    fun findsItsPlaceAgainAfterDamage() {
        val store = MemoryStore()
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, minSeconds = 2.0, maxSeconds = 5.0, clock = { 0L })
        val good = stream(6)
        val damaged = ByteArray(good.size + 77)
        System.arraycopy(good, 0, damaged, 0, 188 * 50)
        for (k in 0 until 77) damaged[188 * 50 + k] = (k * 7).toByte()
        System.arraycopy(good, 188 * 50, damaged, 188 * 50 + 77, good.size - 188 * 50)
        seg.feed(damaged)
        seg.finish()
        store.files.values.forEach { file ->
            val b = file.toByteArray()
            for (p in b.indices step 188) assertEquals(0x47.toByte(), b[p])
        }
        assertEquals(5.96, durations(store.playlist).sum(), 0.1)
    }

    @Test
    fun timesByTheWallClockWhenTheStreamHasNoClock() {
        val store = MemoryStore()
        var now = 0L
        val seg = TsSegmenter(store, windowSeconds = 600.0, maxBytes = Long.MAX_VALUE, clock = { now })
        for (second in 0 until 10) {
            for (f in 0 until 25) {
                seg.feed(packet(videoPid, unitStart = true, randomAccess = f == 0))
                now += 40
            }
        }
        seg.finish()
        val d = durations(store.playlist)
        assertTrue(d.isNotEmpty())
        assertEquals(10.0, d.sum(), 0.2)
    }
}
