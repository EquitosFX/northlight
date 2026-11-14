package app.tvplayer.core.guide

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.StringReader
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** A channel as the TV guide file describes it. */
data class GuideChannel(val id: String, val displayNames: List<String>, val icon: String?)

/** One programme in the guide. Times are UTC milliseconds since 1970. */
data class GuideProgramme(
    val channelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val icon: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Streaming XMLTV guide parser.
 *
 * Guide files are often hundreds of megabytes, so channels and programmes are handed to the
 * callbacks one at a time and nothing is kept here. Gzipped files are detected and unpacked.
 */
object XmltvParser {

    fun parse(
        input: InputStream,
        onChannel: (GuideChannel) -> Unit,
        onProgramme: (GuideProgramme) -> Unit,
    ) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        // Never fetch anything a guide file points to. Not every parser knows every switch, so each is optional.
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) {
            runCatching { factory.setFeature(feature, false) }
        }
        openMaybeGzip(input).use { stream ->
            factory.newSAXParser().parse(InputSource(stream), Handler(onChannel, onProgramme))
        }
    }

    /** Returns [input] unpacked if it is gzip-compressed, otherwise as it is. */
    fun openMaybeGzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        return if (first == 0x1f && second == 0x8b) GZIPInputStream(buffered) else buffered
    }

    /** Reads XMLTV times such as `20261114093000 +0100`. With no offset given, UTC is assumed. */
    internal fun parseTime(text: String?): Long? {
        val value = text?.trim().orEmpty()
        val digits = value.takeWhile { it.isDigit() }
        if (digits.length < 12) return null
        val offset = parseOffset(value.substring(digits.length).trim()) ?: ZoneOffset.UTC
        return try {
            LocalDateTime.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt(),
                digits.substring(8, 10).toInt(),
                digits.substring(10, 12).toInt(),
                if (digits.length >= 14) digits.substring(12, 14).toInt() else 0,
            ).toEpochSecond(offset) * 1000
        } catch (e: DateTimeException) {
            null
        }
    }

    private fun parseOffset(text: String): ZoneOffset? {
        if (text.isEmpty()) return null
        val sign = when (text[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val digits = text.substring(1).filter { it.isDigit() }
        if (digits.length < 4) return null
        return try {
            ZoneOffset.ofHoursMinutes(sign * digits.substring(0, 2).toInt(), sign * digits.substring(2, 4).toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    private val onScreenEpisode = Regex("""[Ss](\d+)\s*[Ee](\d+)""")

    /** Returns season and episode (both counted from 1) from an `episode-num` value. */
    internal fun parseEpisode(system: String?, text: String): Pair<Int?, Int?> {
        if (system == "xmltv_ns") {
            // "season.episode.part", each counted from 0 and optionally followed by "/total".
            val parts = text.split('.')
            val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            val episode = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
            return season to episode
        }
        val match = onScreenEpisode.find(text) ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
    }

    private val textElements = setOf("display-name", "title", "sub-title", "desc", "category", "episode-num")

    private class Handler(
        private val onChannel: (GuideChannel) -> Unit,
        private val onProgramme: (GuideProgramme) -> Unit,
    ) : DefaultHandler() {

        private val text = StringBuilder()
        private var capturing = false

        private var inChannel = false
        private var channelId: String? = null
        private val displayNames = ArrayList<String>()
        private var channelIcon: String? = null

        private var inProgramme = false
        private var progChannel: String? = null
        private var progStart: Long? = null
        private var progStop: Long? = null
        private var title: String? = null
        private var subTitle: String? = null
        private var description: String? = null
        private val categories = ArrayList<String>()
        private var progIcon: String? = null
        private var season: Int? = null
        private var episode: Int? = null
        private var episodeSystem: String? = null

        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            InputSource(StringReader("")) // ignore the DTD most guide files name

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            when (qName) {
                "channel" -> {
                    inChannel = true
                    channelId = attributes.getValue("id")
                    displayNames.clear()
                    channelIcon = null
                }
                "programme" -> {
                    inProgramme = true
                    progChannel = attributes.getValue("channel")
                    progStart = parseTime(attributes.getValue("start"))
                    progStop = parseTime(attributes.getValue("stop"))
                    title = null
                    subTitle = null
                    description = null
                    categories.clear()
                    progIcon = null
                    season = null
                    episode = null
                }
                "icon" -> {
                    val src = attributes.getValue("src")?.takeIf { it.isNotBlank() }
                    if (inProgramme) progIcon = progIcon ?: src else if (inChannel) channelIcon = channelIcon ?: src
                }
                "episode-num" -> episodeSystem = attributes.getValue("system")
            }
            if (qName in textElements) {
                text.setLength(0)
                capturing = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (capturing) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            if (qName in textElements) {
                capturing = false
                val value = text.toString().trim()
                if (value.isNotEmpty()) {
                    when (qName) {
                        "display-name" -> if (inChannel) displayNames.add(value)
                        // Guides may repeat these once per language; the first one is kept.
                        "title" -> title = title ?: value
                        "sub-title" -> subTitle = subTitle ?: value
                        "desc" -> description = description ?: value
                        "category" -> categories.add(value)
                        "episode-num" -> {
                            val (s, e) = parseEpisode(episodeSystem, value)
                            season = season ?: s
                            episode = episode ?: e
                        }
                    }
                }
            }
            when (qName) {
                "channel" -> {
                    inChannel = false
                    val id = channelId
                    if (!id.isNullOrBlank()) onChannel(GuideChannel(id, displayNames.toList(), channelIcon))
                }
                "programme" -> {
                    inProgramme = false
                    val channel = progChannel
                    val start = progStart
                    // A programme with no channel or start time cannot be placed in the guide, so it is dropped.
                    if (!channel.isNullOrBlank() && start != null) {
                        onProgramme(
                            GuideProgramme(
                                channelId = channel,
                                startMillis = start,
                                stopMillis = progStop ?: start,
                                title = title ?: "",
                                subTitle = subTitle,
                                description = description,
                                categories = categories.toList(),
                                icon = progIcon,
                                season = season,
                                episode = episode,
                            ),
                        )
                    }
                }
            }
        }
    }
}
