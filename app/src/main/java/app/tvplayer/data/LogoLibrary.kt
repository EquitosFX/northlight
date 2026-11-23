package app.tvplayer.data

import app.tvplayer.core.extras.ChannelLogos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Logos for channels a playlist gives none, from iptv-org's free channel library.
 *
 * The library lists tens of thousands of channels, so it's read as it downloads and only the channels
 * this viewer's playlists are missing logos for are kept (a few hundred lines in a small file). It's
 * downloaded again when new logo-less channels turn up (at most twice a day) or once a week.
 */
class LogoLibrary(private val http: OkHttpClient?, private val dir: File) {
    private val foundFile = File(dir, "channel-logos-found.txt")

    /** Channel ("name|guide id") → logo, or "" when the library has none. */
    @Volatile private var found: Map<String, String>? = null

    /** When a download was last tried (so a failing one isn't retried every few minutes). */
    @Volatile private var triedAt = 0L

    /** True when the library hasn't been read this week. */
    fun isStale(): Boolean = !foundFile.exists() || System.currentTimeMillis() - foundFile.lastModified() > WEEK_MILLIS

    /** The logo found for a channel, if any. */
    fun logoFor(name: String, guideId: String?): String? = found()[key(name, guideId)]?.takeIf { it.isNotEmpty() }

    private fun found(): Map<String, String> = found ?: synchronized(this) {
        found ?: runCatching {
            if (!foundFile.exists()) return@runCatching emptyMap()
            foundFile.readLines().mapNotNull { line ->
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }.toMap()
        }.getOrDefault(emptyMap()).also { found = it }
    }

    /**
     * Looks up channels without a logo ([missing]: name and guide id). True when the logos found changed.
     */
    suspend fun update(missing: List<Pair<String, String?>>): Boolean = withContext(Dispatchers.IO) {
        if (missing.isEmpty()) return@withContext false
        val known = found()
        val unknown = missing.count { key(it.first, it.second) !in known }
        val now = System.currentTimeMillis()
        val due = isStale() || (unknown > 0 && now - foundFile.lastModified() > HALF_DAY_MILLIS)
        if (!due || now - triedAt < HOUR_MILLIS) return@withContext false
        triedAt = now
        val runtime = Runtime.getRuntime()
        val memoryBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.currentTimeMillis()
        val library = download(missing) ?: return@withContext false
        val next = HashMap<String, String>(missing.size * 2)
        var logos = 0
        for ((name, guideId) in missing) {
            val logo = library.find(name, guideId)
            if (logo != null) logos++
            next[key(name, guideId)] = logo.orEmpty()
        }
        runCatching {
            val temp = File(dir, foundFile.name + ".new")
            temp.writeText(next.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            temp.renameTo(foundFile)
        }
        val memoryAfter = runtime.totalMemory() - runtime.freeMemory()
        val changed = next != known
        found = next
        app.tvplayer.ui.components.Timing.note(
            "Missing channel logos",
            "$logos of ${missing.size} found in ${System.currentTimeMillis() - started} ms · " +
                "memory ${memoryBefore / 1_048_576} → ${memoryAfter / 1_048_576} MB",
        )
        changed
    }

    /** The library, keeping only channels matching [wanted]; channels first, then their logos. */
    private fun download(wanted: List<Pair<String, String?>>): ChannelLogos? {
        val client = http ?: return null
        return runCatching {
            client.newCall(Request.Builder().url(ChannelLogos.CHANNELS_URL).build()).execute().use { channels ->
                if (!channels.isSuccessful) return null
                var logos: okhttp3.Response? = null
                try {
                    ChannelLogos.build(
                        channels = channels.body!!.charStream(),
                        logos = {
                            client.newCall(Request.Builder().url(ChannelLogos.LOGOS_URL).build()).execute()
                                .also { logos = it }.takeIf { it.isSuccessful }?.body?.charStream()
                        },
                        wanted = wanted,
                    )
                } finally {
                    logos?.close()
                }
            }
        }.getOrNull()
    }

    private fun key(name: String, guideId: String?) = name.replace('\t', ' ').replace('\n', ' ') + "|" + guideId.orEmpty().replace('\t', ' ')

    private companion object {
        const val WEEK_MILLIS = 7 * 86_400_000L
        const val HALF_DAY_MILLIS = 12 * 3_600_000L
        const val HOUR_MILLIS = 3_600_000L
    }
}
