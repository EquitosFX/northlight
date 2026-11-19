package app.tvplayer.core.vod

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * When each film and series in a playlist was added, for the "Recently added" category. The date is
 * the provider's own when it gives one (Xtream Codes does: when a film was added, when a series last
 * had episodes added); otherwise the day Skyfall first saw it.
 *
 * Kept compactly (a 64-bit key from each title's address, and a date in seconds), sorted by key, so a
 * playlist with a hundred thousand titles takes under two megabytes.
 */
class AddedDates private constructor(private val keys: LongArray, private val dates: LongArray) {

    val size: Int get() = keys.size

    /** When the title at [url] was added, in seconds since 1970, or null if it isn't known. */
    fun dateOf(url: String): Long? {
        val at = keys.binarySearch(keyOf(url))
        return if (at >= 0) dates[at] else null
    }

    fun write(output: OutputStream) {
        val out = DataOutputStream(output.buffered())
        out.writeInt(FORMAT)
        out.writeInt(keys.size)
        for (i in keys.indices) {
            out.writeLong(keys[i])
            out.writeLong(dates[i])
        }
        out.flush()
    }

    companion object {
        private const val FORMAT = 1

        val EMPTY = AddedDates(LongArray(0), LongArray(0))

        /** A 64-bit fingerprint of a title's address (FNV-1a). */
        fun keyOf(url: String): Long {
            var hash = -0x340d631b7bdddcdbL
            for (ch in url) {
                hash = hash xor ch.code.toLong()
                hash *= 0x100000001b3L
            }
            return hash
        }

        fun read(input: InputStream): AddedDates {
            val data = DataInputStream(input.buffered())
            if (data.readInt() != FORMAT) return EMPTY
            val count = data.readInt()
            if (count < 0 || count > 10_000_000) return EMPTY
            val keys = LongArray(count)
            val dates = LongArray(count)
            for (i in 0 until count) {
                keys[i] = data.readLong()
                dates[i] = data.readLong()
            }
            return AddedDates(keys, dates)
        }

        /**
         * The dates for a playlist just read: [found] has each title's address and the provider's date
         * (null when it gave none). Titles without one keep the date from [previous], or get [nowSeconds]
         * if they're new. Titles no longer in the playlist are dropped.
         */
        fun merge(previous: AddedDates?, found: List<Pair<String, Long?>>, nowSeconds: Long): AddedDates {
            val byKey = HashMap<Long, Long>(found.size * 2)
            for ((url, provided) in found) {
                val key = keyOf(url)
                // A date in the future, or before streaming existed, is a mistake: treat it as not given.
                val usable = provided?.takeIf { it in EARLIEST..(nowSeconds + DAY) }
                val date = usable ?: previous?.dateOf(url) ?: nowSeconds
                val already = byKey[key]
                if (already == null || date > already) byKey[key] = date
            }
            val sorted = byKey.keys.toLongArray().also { it.sort() }
            return AddedDates(sorted, LongArray(sorted.size) { byKey.getValue(sorted[it]) })
        }

        /** 1 January 2005. */
        private const val EARLIEST = 1_104_537_600L
        private const val DAY = 86_400L
    }
}
