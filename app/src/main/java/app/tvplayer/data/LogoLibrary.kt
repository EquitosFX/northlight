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
 * The full library (tens of thousands of channels) is downloaded once a week and only opened when the
 * playlists have channels without logos that haven't been looked up yet. What's found for this
 * viewer's channels is kept in a small file, read when the channel list is built.
 */
class LogoLibrary(private val http: OkHttpClient?, private val dir: File) {
    private val libraryFile = File(dir, "channel-logo-library.txt")
    private val foundFile = File(dir, "channel-logos-found.txt")

    /** Channel ("name|guide id") → logo, or "" when the library has none. */
    @Volatile private var found: Map<String, String>? = null

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
     * Looks up channels without a logo ([missing]: name and guide id) that haven't been looked up
     * since the library was last downloaded. True when a logo was found for any of them.
     */
    suspend fun update(missing: List<Pair<String, String?>>): Boolean = withContext(Dispatchers.IO) {
        if (missing.isEmpty()) return@withContext false
        val known = found()
        val stale = !libraryFile.exists() || System.currentTimeMillis() - libraryFile.lastModified() > WEEK_MILLIS
        val unknown = missing.filter { key(it.first, it.second) !in known }
        if (unknown.isEmpty() && !stale) return@withContext false
        val started = System.currentTimeMillis()
        val library = (if (stale) download() else null)
            ?: runCatching { if (libraryFile.exists()) ChannelLogos.decode(libraryFile.readText()) else null }.getOrNull()
            ?: return@withContext false
        val opened = System.currentTimeMillis() - started
        // After a fresh download every channel is looked up again; otherwise only the new ones.
        val lookUp = if (stale) missing else unknown
        val next = HashMap(known)
        var logos = 0
        for ((name, guideId) in lookUp) {
            val logo = library.find(name, guideId)
            if (logo != null) logos++
            next[key(name, guideId)] = logo.orEmpty()
        }
        runCatching {
            val temp = File(dir, foundFile.name + ".new")
            temp.writeText(next.entries.joinToString("\n") { "${it.key}\t${it.value}" })
            temp.renameTo(foundFile)
        }
        val changed = next != known
        found = next
        app.tvplayer.ui.components.Timing.note(
            "Missing channel logos",
            "$logos of ${lookUp.size} found · library ${if (stale) "downloaded" else "opened"} in $opened ms, " +
                "looked up in ${System.currentTimeMillis() - started - opened} ms",
        )
        changed && next.values.any { it.isNotEmpty() }
    }

    private fun download(): ChannelLogos? {
        val client = http ?: return null
        // Both files are read as they arrive, a piece at a time: logos first, then the channels.
        val built = runCatching {
            client.newCall(Request.Builder().url(ChannelLogos.LOGOS_URL).build()).execute().use { logos ->
                client.newCall(Request.Builder().url(ChannelLogos.CHANNELS_URL).build()).execute().use { channels ->
                    if (!channels.isSuccessful) return null
                    ChannelLogos.build(
                        channels.body!!.charStream(),
                        if (logos.isSuccessful) logos.body?.charStream() else null,
                    )
                }
            }
        }.getOrNull() ?: return null
        if (built.size == 0) return null
        runCatching {
            val temp = File(dir, libraryFile.name + ".new")
            temp.writeText(built.encode())
            temp.renameTo(libraryFile)
        }
        return built
    }

    private fun key(name: String, guideId: String?) = name.replace('\t', ' ').replace('\n', ' ') + "|" + guideId.orEmpty().replace('\t', ' ')

    private companion object {
        const val WEEK_MILLIS = 7 * 86_400_000L
    }
}
