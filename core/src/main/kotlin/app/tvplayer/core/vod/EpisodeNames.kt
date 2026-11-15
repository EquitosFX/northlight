package app.tvplayer.core.vod

/** An episode recognised from its name in a playlist, such as "Hollow Fen S01 E03 The Flood". */
data class EpisodeName(val show: String, val season: Int, val episode: Int, val title: String?)

/**
 * M3U playlists list a series as one entry per episode. This recognises those names so the episodes
 * can be gathered under one show, by season and number.
 */
object EpisodeNames {
    // "S01 E03", "S1E3", "s01.e03", "S01 - E03"
    private val seasonEpisode = Regex("""(?i)(?:^|[\s._\-\[(|])s(\d{1,2})\s*[._\-]?\s*e(\d{1,4})(?=$|[^0-9])""")
    // "1x03"
    private val crossed = Regex("""(?i)(?:^|[\s._\-\[(|])(\d{1,2})x(\d{2,3})(?=$|[^0-9])""")

    fun parse(name: String): EpisodeName? {
        val match = seasonEpisode.find(name) ?: crossed.find(name) ?: return null
        val show = clean(name.substring(0, match.range.first))
        if (show.isEmpty()) return null
        val rest = clean(name.substring(match.range.last + 1))
        return EpisodeName(
            show = show,
            season = match.groupValues[1].toInt(),
            episode = match.groupValues[2].toInt(),
            title = rest.takeIf { it.isNotEmpty() && !it.all { c -> c.isDigit() } },
        )
    }

    /** The key episodes of one show share: case, punctuation and spacing ignored. */
    fun showKey(name: String): String =
        (parse(name)?.show ?: name).lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    private fun clean(text: String): String {
        var t = text.replace('.', ' ').replace('_', ' ')
        // Separators and brackets left open by cutting out "S01 E03"; brackets that belong to the name stay.
        repeat(3) {
            t = t.trim { it.isWhitespace() || it in "-–|:" }
            if (t.endsWith("(") || t.endsWith("[")) t = t.dropLast(1)
            if (t.startsWith(")") || t.startsWith("]")) t = t.drop(1)
        }
        return t.replace(Regex("""\s{2,}"""), " ")
    }
}
