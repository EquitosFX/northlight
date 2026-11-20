package app.tvplayer.core.search

import java.text.Normalizer

/**
 * Matches search text against names the way people type and speak them: spaces, hyphens and other
 * punctuation don't matter ("spiderman" finds "Spider-Man", "ghost busters" finds "Ghostbusters"),
 * nor do capitals or accents ("amelie" finds "Amélie"), and "&" is "and".
 */
object SearchText {
    private val marks = Regex("\\p{Mn}+")

    /** Lower case, without accents, "&" as "and", and only letters and digits. */
    fun condense(text: String): String {
        val plain = Normalizer.normalize(text.lowercase().replace("&", "and"), Normalizer.Form.NFD).replace(marks, "")
        val out = StringBuilder(plain.length)
        for (ch in plain) if (ch.isLetterOrDigit()) out.append(ch)
        return out.toString()
    }

    /** The separate words of [text], each condensed. */
    fun words(text: String): List<String> =
        text.lowercase().replace("&", " and ").split(Regex("[^\\p{L}\\p{N}'’.]+")).map(::condense).filter { it.isNotEmpty() }

    /** What's being searched for, worked out once. */
    class Query(text: String) {
        val condensed = condense(text)
        val words = words(text)
        val usable: Boolean get() = condensed.length >= 2
    }

    /**
     * How well a name (already [condense]d) matches: 3 if it starts with the search, 2 if it contains it
     * anywhere, 1 if it contains every word of the search in some order, 0 if not at all.
     */
    fun rank(condensedName: String, query: Query): Int = when {
        query.condensed.isEmpty() -> 0
        condensedName.startsWith(query.condensed) -> 3
        condensedName.contains(query.condensed) -> 2
        query.words.size > 1 && query.words.all { condensedName.contains(it) } -> 1
        else -> 0
    }
}
