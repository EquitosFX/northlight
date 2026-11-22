package app.tvplayer.core.extras

import app.tvplayer.core.search.SearchText

/**
 * Logos for channels a playlist gives none, from the free iptv-org channel library on GitHub: by the
 * guide id when it's iptv-org's ("BBCOne.uk"), otherwise by name, with UK channels preferred.
 */
class ChannelLogos private constructor(private val byId: Map<String, String>, private val byName: Map<String, String>) {
    val size: Int get() = byId.size

    /** A logo for a channel, if the library has one. */
    fun find(name: String, guideId: String?): String? {
        guideId?.trim()?.lowercase()?.let { id -> byId[id]?.let { return it } }
        val key = nameKey(name)
        if (key.length < 2) return null
        return byName[key]
    }

    /** One line per logo ("id\tname key\turl"), to keep between runs. */
    fun encode(): String = buildString {
        for ((id, url) in byId) append(id).append('\t').append(url).append('\n')
        append("--\n")
        for ((key, url) in byName) append(key).append('\t').append(url).append('\n')
    }

    companion object {
        const val CHANNELS_URL = "https://iptv-org.github.io/api/channels.json"
        const val LOGOS_URL = "https://iptv-org.github.io/api/logos.json"

        val EMPTY = ChannelLogos(emptyMap(), emptyMap())

        fun decode(text: String): ChannelLogos {
            val byId = HashMap<String, String>()
            val byName = HashMap<String, String>()
            var names = false
            for (line in text.lineSequence()) {
                if (line == "--") { names = true; continue }
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                (if (names) byName else byId)[line.substring(0, tab)] = line.substring(tab + 1)
            }
            return ChannelLogos(byId, byName)
        }

        /**
         * Builds the library from iptv-org's channel list and logo list, read a piece at a time. A name
         * shared by channels in several countries goes to the UK one. Logos in use and not SVG (which
         * Skyfall can't draw) are preferred.
         */
        fun build(channels: java.io.Reader, logos: java.io.Reader?): ChannelLogos {
            // logos.json: several per channel; the best for each (in use, PNG or similar, whole-channel).
            val logoFor = HashMap<String, Pair<Int, String>>()
            logos?.let { reader ->
                JsonScan(reader).objects(setOf("channel", "feed", "url", "format", "in_use")) { entry ->
                    val channel = entry["channel"] as? String ?: return@objects
                    val url = (entry["url"] as? String)?.takeIf { it.startsWith("https://") } ?: return@objects
                    val format = (entry["format"] as? String)?.uppercase()
                    if (format == "SVG" || url.lowercase().substringBefore("?").endsWith(".svg")) return@objects
                    val score = (if (entry["in_use"] == "true") 2 else 0) + (if (entry["feed"] == null) 1 else 0)
                    val had = logoFor[channel]
                    if (had == null || score > had.first) logoFor[channel] = score to url
                }
            }
            val byId = HashMap<String, String>()
            val byName = HashMap<String, String>()
            val nameIsUk = HashMap<String, Boolean>()
            JsonScan(channels).objects(setOf("id", "name", "alt_names", "country", "closed", "logo")) { channel ->
                val id = channel["id"] as? String ?: return@objects
                if (channel["closed"] != null) return@objects
                val url = logoFor[id]?.second
                    ?: (channel["logo"] as? String)?.takeIf { it.startsWith("https://") && !it.lowercase().endsWith(".svg") }
                    ?: return@objects
                byId[id.lowercase()] = url
                val country = channel["country"] as? String
                val uk = country.equals("UK", ignoreCase = true) || country.equals("GB", ignoreCase = true)
                @Suppress("UNCHECKED_CAST")
                val names = listOfNotNull(channel["name"] as? String) + ((channel["alt_names"] as? List<String>).orEmpty())
                for (name in names) {
                    val key = nameKey(name)
                    if (key.length < 2) continue
                    val had = nameIsUk[key]
                    if (had == null || (!had && uk)) {
                        byName[key] = url
                        nameIsUk[key] = uk
                    }
                }
            }
            return ChannelLogos(byId, byName)
        }

        fun build(channelsJson: String, logosJson: String?): ChannelLogos =
            build(channelsJson.reader(), logosJson?.reader())

        private val countryPrefix = Regex("""^\s*[\[(|]?\s*(uk|gb|us|usa|ie|irl|ca|au|nz|de|fr|es|it|nl|pt|pl|tr|ar|in)\s*[\])|:\-]+\s*""", RegexOption.IGNORE_CASE)
        private val noise = Regex(
            """\b(uhd|fhd|hd|sd|4k|8k|hevc|h\.?265|h\.?264|1080p|1080i|720p|2160p|50fps|60fps|fps|backup|raw|vip|multi)\b|\+\s*1\b|[\[(][^\])]*[\])]""",
            RegexOption.IGNORE_CASE,
        )

        /** A channel name as matched: no country tag in front, no quality words or brackets. */
        fun nameKey(name: String): String {
            var text = name
            repeat(2) { text = countryPrefix.replace(text, "") }
            text = noise.replace(text, " ")
            return SearchText.condense(text)
        }
    }
}
