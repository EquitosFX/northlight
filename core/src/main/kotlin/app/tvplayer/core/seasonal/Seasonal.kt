package app.tvplayer.core.seasonal

import java.time.LocalDate
import java.time.Month

/** The Christmas row on Home: when it shows, and how Christmas films and series are recognised. */
object Christmas {
    /** TMDB's "christmas" keyword. */
    const val TMDB_KEYWORD = 207317

    /** From the 1st of November to Twelfth Night. */
    fun inSeason(date: LocalDate): Boolean =
        date.month == Month.NOVEMBER || date.month == Month.DECEMBER || (date.month == Month.JANUARY && date.dayOfMonth <= 6)

    private val WORDS = Regex(
        """\b(christmas|xmas|x-mas|santa|santa's|grinch|noel|nativity|mistletoe|yuletide|scrooge|""" +
            """christmas carol|jingle|nutcracker|reindeer|rudolph|sleigh|snowman|elf)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a title's name alone says it's a Christmas one ("Elf", "A Christmas Carol", "Santa Clause 2"). */
    fun looksFestive(name: String): Boolean = WORDS.containsMatchIn(name)

    /** TMDB's most popular Christmas films ("movie") or series ("tv"). */
    fun discoverUrl(type: String, page: Int): String =
        "https://api.themoviedb.org/3/discover/$type?language=en-GB&page=$page&sort_by=popularity.desc" +
            "&include_adult=false&vote_count.gte=20&with_keywords=$TMDB_KEYWORD"
}
