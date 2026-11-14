package app.tvplayer.core.channels

enum class Quality(val rank: Int, val label: String) {
    UHD(4, "4K"),
    FHD(3, "FHD"),
    HD(2, "HD"),
    SD(1, "SD"),
    UNKNOWN(0, ""),
}

/** What a channel name says once quality and backup markers are taken out of it. */
data class NameInfo(
    val baseName: String,
    /** Lower-case letters, digits and '+' only; two names with the same key are the same channel. */
    val key: String,
    val quality: Quality,
    val isBackup: Boolean,
)

/** Copies of one channel at different qualities, best first, with backups last. */
data class VariantGroup<T>(val baseName: String, val variants: List<T>)

/**
 * Providers list the same channel several times ("North One SD", "North One FHD", "North One HD Backup").
 * This reads those markers so the app can show one channel with a quality switcher.
 */
object ChannelVariants {

    private val qualityWords = mapOf(
        "4k" to Quality.UHD, "uhd" to Quality.UHD, "2160p" to Quality.UHD,
        "fhd" to Quality.FHD, "1080p" to Quality.FHD, "1080i" to Quality.FHD,
        "hd" to Quality.HD, "720p" to Quality.HD,
        "sd" to Quality.SD, "576p" to Quality.SD, "576i" to Quality.SD, "480p" to Quality.SD,
    )
    private val noiseWords = setOf("hevc", "h265", "h.265", "h264", "h.264", "50fps", "60fps", "raw")
    private val backupWord = Regex("""(backup|bkp|bkup)\d*""")
    private val separators = Regex("""[\s()\[\]|_]+""")

    fun analyse(name: String): NameInfo {
        var quality = Quality.UNKNOWN
        var backup = false
        val kept = ArrayList<String>()

        for (word in name.trim().split(separators)) {
            val bare = word.trim(':', '-', ',', '.', '*').lowercase()
            if (bare.isEmpty()) continue // punctuation on its own
            val wordQuality = qualityWords[bare]
            when {
                wordQuality != null -> if (wordQuality.rank > quality.rank) quality = wordQuality
                bare in noiseWords -> Unit
                backupWord.matches(bare) -> backup = true
                else -> kept.add(word)
            }
        }

        // A name made only of markers ("HD") stays as it was written.
        val base = if (kept.isEmpty()) name.trim() else kept.joinToString(" ")
        return NameInfo(baseName = base, key = keyOf(base), quality = quality, isBackup = backup)
    }

    /** '+' is kept so that time-shifted channels ("North One +1") stay separate from the main one. */
    fun keyOf(text: String): String = text.lowercase().filter { it.isLetterOrDigit() || it == '+' }

    /** Groups [items] that are the same channel, keeping the order in which channels first appear. */
    fun <T> group(items: List<T>, nameOf: (T) -> String): List<VariantGroup<T>> {
        val groups = LinkedHashMap<String, MutableList<Pair<T, NameInfo>>>()
        for (item in items) {
            val info = analyse(nameOf(item))
            groups.getOrPut(info.key) { ArrayList() }.add(item to info)
        }
        return groups.values.map { members ->
            val ordered = members.sortedWith(
                compareBy<Pair<T, NameInfo>> { it.second.isBackup }.thenByDescending { it.second.quality.rank },
            )
            VariantGroup(baseName = ordered.first().second.baseName, variants = ordered.map { it.first })
        }
    }
}
