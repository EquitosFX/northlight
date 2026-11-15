package app.tvplayer.core.live

import java.io.OutputStream
import java.util.Locale

/** Where the [TsSegmenter] keeps its slices and playlist (files on the device, or memory in tests). */
interface SegmentStore {
    fun open(sequence: Long): OutputStream
    fun delete(sequence: Long)
    fun writePlaylist(text: String)
    fun segmentName(sequence: Long): String = "seg$sequence.ts"
}

/**
 * Turns a live MPEG-TS stream into a rolling HLS recording, so live TV can be paused, rewound and
 * fast-forwarded, then caught up to live.
 *
 * Bytes are fed in as they arrive. They're split into 188-byte packets (re-finding the sync byte if
 * the stream is damaged), cut into slices of a few seconds that start on a video keyframe where the
 * stream marks one, and timed from the stream's own clock (PCR). Old slices are deleted once the
 * recording is longer than [windowSeconds] or bigger than [maxBytes]. Each slice starts with the
 * latest programme tables, so playback can start at any slice.
 */
class TsSegmenter(
    private val store: SegmentStore,
    private val windowSeconds: Double,
    private val maxBytes: Long,
    private val minSeconds: Double = 0.5,
    private val maxSeconds: Double = 1.0,
    /** The first slice is cut early so a channel starts quickly. */
    private val firstSeconds: Double = 0.6,
    /** Milliseconds now; used to time slices when a stream carries no clock. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Slice(val sequence: Long, val seconds: Double, val bytes: Long, val discontinuity: Boolean, val startedAt: Long)

    private val slices = ArrayDeque<Slice>()
    private var nextSequence = 0L
    private var discontinuitySequence = 0L
    private var totalBytes = 0L
    private var totalSeconds = 0.0

    // The slice being written.
    private var out: OutputStream? = null
    private var outSequence = 0L
    private var outBytes = 0L
    private var outStartPcr = -1L
    private var outLastPcr = -1L
    private var outStartedAt = 0L
    private var outDiscontinuity = false
    private var pendingDiscontinuity = false

    // Stream structure.
    private var pcrPid = -1
    private var pmtPid = -1
    private var videoPid = -1
    private var patPacket: ByteArray? = null
    private var pmtPacket: ByteArray? = null

    // Bytes left over from the last feed, less than one packet.
    private val partial = ByteArray(PACKET)
    private var partialLength = 0

    /**
     * The playlist's target duration: the longest slice, rounded. The player re-reads the playlist this
     * often, so slices are kept short (a second by default) to play close to live without running dry.
     */
    val targetDuration: Int = kotlin.math.round(maxSeconds).toInt().coerceAtLeast(1)

    /** How many slices have been written and kept so far. */
    val sliceCount: Int get() = slices.size

    /** Seconds of recording kept. */
    val recordedSeconds: Double get() = totalSeconds

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var i = offset
        val end = offset + length
        // Finish a packet started in the last feed.
        if (partialLength > 0) {
            val need = PACKET - partialLength
            val take = minOf(need, end - i)
            System.arraycopy(data, i, partial, partialLength, take)
            partialLength += take
            i += take
            if (partialLength < PACKET) return
            // A packet held over always starts on a sync byte; a damaged one is dropped and the
            // scan below finds the next good packet.
            if (i >= end || data[i] == SYNC) packet(partial, 0)
            partialLength = 0
        }
        while (i < end) {
            if (data[i] != SYNC) {
                i++
                continue
            }
            if (end - i < PACKET) {
                System.arraycopy(data, i, partial, 0, end - i)
                partialLength = end - i
                return
            }
            // A real packet boundary has the next sync byte one packet on (when we can see it).
            if (i + PACKET < end && data[i + PACKET] != SYNC) {
                i++
                continue
            }
            packet(data, i)
            i += PACKET
        }
    }

    /** The source was reconnected: the next slice is marked as a fresh start for the player. */
    fun discontinuity() {
        partialLength = 0
        closeSlice()
        pendingDiscontinuity = true
        pcrPid = -1
        outLastPcr = -1
    }

    /** Ends the recording's current slice so everything received so far can be played. */
    fun finish() {
        closeSlice()
    }

    private fun packet(buf: ByteArray, at: Int) {
        val pid = ((buf[at + 1].toInt() and 0x1F) shl 8) or (buf[at + 2].toInt() and 0xFF)
        val unitStart = buf[at + 1].toInt() and 0x40 != 0
        val control = (buf[at + 3].toInt() shr 4) and 0x3
        var payloadAt = at + 4
        var randomAccess = false
        var pcr = -1L
        if (control and 0x2 != 0) {
            val afLength = buf[at + 4].toInt() and 0xFF
            if (afLength > 0 && afLength <= 183) {
                val flags = buf[at + 5].toInt() and 0xFF
                randomAccess = flags and 0x40 != 0
                if (flags and 0x10 != 0 && afLength >= 7) pcr = readPcr(buf, at + 6)
            }
            payloadAt = at + 5 + afLength
        }
        val hasPayload = control and 0x1 != 0 && payloadAt < at + PACKET

        // Programme tables: remember them, and learn which stream is the video.
        if (pid == 0 && unitStart && hasPayload) {
            patPacket = buf.copyOfRange(at, at + PACKET)
            parsePat(buf, payloadAt, at + PACKET)
        } else if (pid == pmtPid && unitStart && hasPayload) {
            pmtPacket = buf.copyOfRange(at, at + PACKET)
            parsePmt(buf, payloadAt, at + PACKET)
        }

        if (pcr >= 0 && pcrPid < 0) pcrPid = pid
        val clockPcr = if (pid == pcrPid) pcr else -1L

        // Decide whether this packet starts a new slice.
        val current = out
        if (current == null) {
            openSlice()
        } else {
            val seconds = elapsedSeconds(clockPcr)
            val target = if (slices.isEmpty()) firstSeconds else minSeconds
            val keyframe = randomAccess && (videoPid < 0 || pid == videoPid) && unitStart
            if ((keyframe && seconds >= target) || seconds >= maxSeconds) {
                closeSlice()
                openSlice()
            }
        }
        if (clockPcr >= 0) {
            if (outStartPcr < 0) outStartPcr = clockPcr
            // A clock that jumps backwards or far forwards is a new start (the source changed).
            if (outLastPcr >= 0 && (clockPcr < outLastPcr || clockPcr - outLastPcr > PCR_HZ * 10)) {
                outStartPcr = clockPcr
            }
            outLastPcr = clockPcr
        }
        out?.write(buf, at, PACKET)
        outBytes += PACKET
    }

    private fun elapsedSeconds(pcrNow: Long): Double {
        if (outStartPcr >= 0 && outLastPcr >= outStartPcr) {
            val last = if (pcrNow >= outLastPcr && pcrNow - outLastPcr < PCR_HZ * 10) pcrNow else outLastPcr
            return (last - outStartPcr).toDouble() / PCR_HZ
        }
        return (clock() - outStartedAt) / 1000.0
    }

    private fun openSlice() {
        outSequence = nextSequence++
        val stream = store.open(outSequence)
        out = stream
        outBytes = 0
        // A slice starts where the last one ended, so no time falls between slices.
        val carry = if (pendingDiscontinuity) -1L else outLastPcr
        outStartPcr = carry
        outLastPcr = carry
        outStartedAt = clock()
        outDiscontinuity = pendingDiscontinuity
        pendingDiscontinuity = false
        // Start each slice with the programme tables so playback can begin here.
        patPacket?.let { stream.write(it); outBytes += PACKET }
        pmtPacket?.let { stream.write(it); outBytes += PACKET }
    }

    private fun closeSlice() {
        val stream = out ?: return
        out = null
        stream.flush()
        stream.close()
        val tablesOnly = outBytes <= PACKET * 2L
        if (tablesOnly) {
            store.delete(outSequence)
            if (outDiscontinuity) pendingDiscontinuity = true
            return
        }
        var seconds = if (outStartPcr >= 0 && outLastPcr > outStartPcr) {
            (outLastPcr - outStartPcr).toDouble() / PCR_HZ
        } else {
            (clock() - outStartedAt) / 1000.0
        }
        seconds = seconds.coerceIn(0.04, maxSeconds + 1.0)
        slices.addLast(Slice(outSequence, seconds, outBytes, outDiscontinuity, outStartedAt))
        totalBytes += outBytes
        totalSeconds += seconds
        trim()
        store.writePlaylist(playlist())
    }

    private fun trim() {
        while (slices.size > MIN_KEPT && (totalSeconds > windowSeconds || totalBytes > maxBytes)) {
            val old = slices.removeFirst()
            totalBytes -= old.bytes
            totalSeconds -= old.seconds
            // The player counts discontinuities from the start of the playlist.
            if (slices.firstOrNull()?.discontinuity == true) discontinuitySequence++
            store.delete(old.sequence)
        }
    }

    /** The HLS playlist for the slices kept. */
    fun playlist(): String = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n")
        append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
        append("#EXT-X-MEDIA-SEQUENCE:").append(slices.firstOrNull()?.sequence ?: 0).append('\n')
        append("#EXT-X-DISCONTINUITY-SEQUENCE:").append(discontinuitySequence).append('\n')
        slices.forEachIndexed { index, slice ->
            if (slice.discontinuity && index > 0) append("#EXT-X-DISCONTINUITY\n")
            append("#EXTINF:").append(String.format(Locale.US, "%.3f", slice.seconds)).append(",\n")
            append(store.segmentName(slice.sequence)).append('\n')
        }
    }

    private fun parsePat(buf: ByteArray, from: Int, end: Int) {
        var p = from + 1 + (buf[from].toInt() and 0xFF) // pointer field
        if (p + 8 > end || buf[p].toInt() != 0x00) return
        val sectionLength = ((buf[p + 1].toInt() and 0x0F) shl 8) or (buf[p + 2].toInt() and 0xFF)
        val last = minOf(p + 3 + sectionLength - 4, end)
        p += 8
        while (p + 4 <= last) {
            val program = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
            val pid = ((buf[p + 2].toInt() and 0x1F) shl 8) or (buf[p + 3].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = pid
                return
            }
            p += 4
        }
    }

    private fun parsePmt(buf: ByteArray, from: Int, end: Int) {
        var p = from + 1 + (buf[from].toInt() and 0xFF)
        if (p + 12 > end || buf[p].toInt() != 0x02) return
        val sectionLength = ((buf[p + 1].toInt() and 0x0F) shl 8) or (buf[p + 2].toInt() and 0xFF)
        val last = minOf(p + 3 + sectionLength - 4, end)
        val infoLength = ((buf[p + 10].toInt() and 0x0F) shl 8) or (buf[p + 11].toInt() and 0xFF)
        p += 12 + infoLength
        while (p + 5 <= last) {
            val type = buf[p].toInt() and 0xFF
            val pid = ((buf[p + 1].toInt() and 0x1F) shl 8) or (buf[p + 2].toInt() and 0xFF)
            val esInfo = ((buf[p + 3].toInt() and 0x0F) shl 8) or (buf[p + 4].toInt() and 0xFF)
            if (type in VIDEO_TYPES) {
                videoPid = pid
                return
            }
            p += 5 + esInfo
        }
    }

    private fun readPcr(buf: ByteArray, at: Int): Long {
        val b = { k: Int -> (buf[at + k].toLong() and 0xFF) }
        val base = (b(0) shl 25) or (b(1) shl 17) or (b(2) shl 9) or (b(3) shl 1) or (b(4) shr 7)
        val ext = ((b(4) and 0x01) shl 8) or b(5)
        return base * 300 + ext
    }

    companion object {
        const val PACKET = 188
        const val SYNC: Byte = 0x47
        const val PCR_HZ = 27_000_000L
        const val MIN_KEPT = 3
        /** MPEG-2, MPEG-1, H.264 and H.265 video. */
        val VIDEO_TYPES = setOf(0x01, 0x02, 0x1B, 0x24)
    }
}
