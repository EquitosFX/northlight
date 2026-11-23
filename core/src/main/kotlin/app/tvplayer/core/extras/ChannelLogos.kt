package app.tvplayer.core.extras

import app.tvplayer.core.search.SearchText

/**
 * Logos for channels a playlist gives none, from the free iptv-org channel library on GitHub: by the
 * guide id when it's iptv-org's ("BBCOne.uk"), otherwise by name, with UK channels preferred.
 */
class ChannelLogos private constructor(private val byId: Map<String, String>, private val byName: Map<String, String>) {
    val size: Int get() = byId.size + byName.size

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
         * Builds the library from iptv-org's channel list, then its logo list, each read a piece at a
         * time. With [wanted] (channel names and guide ids), only channels matching one of them are
         * kept, so the tens of thousands of others never take up memory. A name shared by channels in
         * several countries goes to the UK one. Logos in use and not SVG (which Skyfall can't draw) are
         * preferred.
         */
        fun build(channels: java.io.Reader, logos: () -> java.io.Reader?, wanted: Collection<Pair<String, String?>>? = null): ChannelLogos {
            val wantedIds = wanted?.mapNotNullTo(HashSet()) { it.second?.trim()?.lowercase()?.takeIf { id -> id.isNotEmpty() } }
            val wantedNames = wanted?.mapNotNullTo(HashSet()) { nameKey(it.first).takeIf { key -> key.length >= 2 } }
            // Pass 1, the channels: which to keep, their names, and the older single "logo" field.
            val kept = HashMap<String, String?>()          // id as given → its own "logo", if any
            val candidates = HashMap<String, MutableList<Pair<Boolean, String>>>() // name key → (UK?, id)
            JsonScan(channels).objects(setOf("id", "name", "alt_names", "country", "closed", "logo")) { channel ->
                val id = channel["id"] as? String ?: return@objects
                if (channel["closed"] != null) return@objects
                @Suppress("UNCHECKED_CAST")
                val keys = (listOfNotNull(channel["name"] as? String) + ((channel["alt_names"] as? List<String>).orEmpty()))
                    .map(::nameKey).filter { it.length >= 2 }.distinct()
                val matchesName = wantedNames == null || keys.any { it in wantedNames }
                val matchesId = wantedIds == null || id.lowercase() in wantedIds
                if (!matchesName && !matchesId) return@objects
                kept[id] = (channel["logo"] as? String)?.takeIf { it.startsWith("https://") && !it.lowercase().endsWith(".svg") }
                if (matchesName) {
                    val country = channel["country"] as? String
                    val uk = country.equals("UK", ignoreCase = true) || country.equals("GB", ignoreCase = true)
                    for (key in keys) if (wantedNames == null || key in wantedNames) candidates.getOrPut(key) { ArrayList(2) } += uk to id
                }
            }
            // Pass 2, the logos: the best for each kept channel (in use, PNG or similar, whole-channel).
            val best = HashMap<String, Pair<Int, String>>()
            if (kept.isNotEmpty()) logos()?.let { reader ->
                JsonScan(reader).objects(setOf("channel", "feed", "url", "format", "in_use")) { entry ->
                    val channel = entry["channel"] as? String ?: return@objects
                    if (channel !in kept) return@objects
                    val url = (entry["url"] as? String)?.takeIf { it.startsWith("https://") } ?: return@objects
                    val format = (entry["format"] as? String)?.uppercase()
                    if (format == "SVG" || url.lowercase().substringBefore("?").endsWith(".svg")) return@objects
                    val score = (if (entry["in_use"] == "true") 2 else 0) + (if (entry["feed"] == null) 1 else 0)
                    val had = best[channel]
                    if (had == null || score > had.first) best[channel] = score to url
                }
            }
            val urlOf = HashMap<String, String>()
            for ((id, own) in kept) (best[id]?.second ?: own)?.let { urlOf[id] = it }
            val byId = HashMap<String, String>()
            for ((id, url) in urlOf) byId[id.lowercase()] = url
            val byName = HashMap<String, String>()
            for ((key, list) in candidates) {
                val pick = list.firstOrNull { it.first && it.second in urlOf } ?: list.firstOrNull { it.second in urlOf } ?: continue
                byName[key] = urlOf.getValue(pick.second)
            }
            return ChannelLogos(byId, byName)
        }

        fun build(channelsJson: String, logosJson: String?, wanted: Collection<Pair<String, String?>>? = null): ChannelLogos =
            build(channelsJson.reader(), { logosJson?.reader() }, wanted)

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
