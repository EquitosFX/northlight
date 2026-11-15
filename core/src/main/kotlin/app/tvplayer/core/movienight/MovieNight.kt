package app.tvplayer.core.movienight

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import java.time.LocalDate

/** One answer to a quiz question. */
data class Choice(val id: String, val label: String)

data class Question(val id: String, val text: String, val choices: List<Choice>)

/** What the answers ask TMDB for. Genre ids are TMDB's, separately for films and series. */
data class Criteria(
    val films: Boolean,
    val series: Boolean,
    val filmGenres: List<Int>,
    val seriesGenres: List<Int>,
    /** Liked but not required: titles with these rank higher. */
    val filmBonus: List<Int>,
    val seriesBonus: List<Int>,
    val filmExclude: List<Int>,
    val seriesExclude: List<Int>,
    val fromDate: String?,
    val toDate: String?,
    val filmMaxRuntime: Int?,
    val seriesMaxRuntime: Int?,
    val seriesMinRuntime: Int?,
    val minRating: Double?,
    val minVotes: Int,
    val maxVotes: Int?,
    val sortBy: String,
    val englishOnly: Boolean,
    val familyFriendly: Boolean,
)

/** A title TMDB suggested. */
data class Found(
    val id: Long,
    val type: String,
    val title: String,
    val year: Int?,
    val rating: Double?,
    val votes: Int,
    val popularity: Double,
    val genreIds: List<Int>,
    val overview: String?,
    val poster: String?,
)

/** What the shortlist compares. */
data class PickFacts(
    val title: String,
    val isSeries: Boolean,
    val year: Int?,
    val rating: Double?,
    val runtimeMinutes: Int?,
    val seasons: Int?,
    val popularity: Double,
    val genres: List<String>,
)

/**
 * Movie Night: ten quick questions turned into a TMDB "discover" search, whose results are matched
 * against the viewer's own playlists, then a shortlist that says how its picks differ.
 */
object MovieNight {
    private const val BASE = "https://api.themoviedb.org/3"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val questions: List<Question> = listOf(
        Question("kind", "What are you in the mood for?", listOf(Choice("film", "A film"), Choice("series", "A series"), Choice("either", "Either"))),
        Question(
            "company", "Who's watching?",
            listOf(Choice("solo", "Just me"), Choice("couple", "The two of us"), Choice("friends", "Friends"), Choice("family", "Family, with children")),
        ),
        Question(
            "mood", "How do you want to feel?",
            listOf(
                Choice("laugh", "Laughing"), Choice("edge", "On the edge of my seat"), Choice("moved", "Moved"),
                Choice("scared", "Scared"), Choice("amazed", "Amazed"), Choice("curious", "Curious"),
            ),
        ),
        Question(
            "flavour", "Any of these appeal?",
            listOf(
                Choice("action", "Action"), Choice("crime", "Crime"), Choice("romance", "Romance"), Choice("mystery", "Mystery"),
                Choice("animation", "Animation"), Choice("history", "Real events and history"), Choice("none", "No preference"),
            ),
        ),
        Question("tone", "Light or dark?", listOf(Choice("light", "Light and easy"), Choice("middle", "Somewhere in between"), Choice("dark", "Dark and gritty"))),
        Question(
            "length", "How much time have you got?",
            listOf(
                Choice("short", "Not long (a short film or half-hour episodes)"),
                Choice("normal", "A normal evening"),
                Choice("any", "As long as it takes"),
            ),
        ),
        Question(
            "era", "From when?",
            listOf(
                Choice("new", "The last few years"), Choice("2010s", "The 2010s"), Choice("2000s", "The 2000s"),
                Choice("classic", "Classics, before 2000"), Choice("any", "Any time"),
            ),
        ),
        Question("rating", "How well reviewed?", listOf(Choice("great", "Only the best (7.5+)"), Choice("good", "Good (6.5+)"), Choice("any", "Don't mind"))),
        Question(
            "popularity", "Crowd-pleaser or hidden gem?",
            listOf(Choice("popular", "Something everyone's seen"), Choice("gem", "A hidden gem"), Choice("any", "Either")),
        ),
        Question("language", "Subtitles OK?", listOf(Choice("any", "Yes, any language"), Choice("english", "English-language only"))),
    )

    // TMDB genre ids.
    private object G {
        const val ACTION = 28; const val ADVENTURE = 12; const val ANIMATION = 16; const val COMEDY = 35; const val CRIME = 80
        const val DOCUMENTARY = 99; const val DRAMA = 18; const val FAMILY = 10751; const val FANTASY = 14; const val HISTORY = 36
        const val HORROR = 27; const val MYSTERY = 9648; const val ROMANCE = 10749; const val SCIFI = 878; const val THRILLER = 53
        const val WAR = 10752
        const val TV_ACTION = 10759; const val TV_KIDS = 10762; const val TV_SCIFI = 10765; const val TV_WAR = 10768
    }

    private val filmGenreNames = mapOf(
        28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 14 to "Fantasy", 36 to "History", 27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance",
        878 to "Science fiction", 53 to "Thriller", 10752 to "War", 37 to "Western", 10770 to "TV film",
    )
    private val seriesGenreNames = mapOf(
        10759 to "Action and adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime", 99 to "Documentary", 18 to "Drama",
        10751 to "Family", 10762 to "Kids", 9648 to "Mystery", 10763 to "News", 10764 to "Reality", 10765 to "Sci-fi and fantasy",
        10766 to "Soap", 10767 to "Talk", 10768 to "War and politics", 37 to "Western",
    )

    fun genreNames(type: String, ids: List<Int>): List<String> =
        ids.mapNotNull { (if (type == "tv") seriesGenreNames else filmGenreNames)[it] }

    fun criteria(answers: Map<String, String>, today: LocalDate = LocalDate.now()): Criteria {
        val kind = answers["kind"] ?: "either"
        val family = answers["company"] == "family"
        val (filmMood, seriesMood) = when (answers["mood"]) {
            "laugh" -> listOf(G.COMEDY) to listOf(G.COMEDY)
            "edge" -> listOf(G.THRILLER, G.ACTION) to listOf(G.CRIME, G.TV_ACTION, G.MYSTERY)
            "moved" -> listOf(G.DRAMA, G.ROMANCE) to listOf(G.DRAMA)
            "scared" -> listOf(G.HORROR) to listOf(G.MYSTERY, G.TV_SCIFI)
            "amazed" -> listOf(G.SCIFI, G.FANTASY, G.ADVENTURE) to listOf(G.TV_SCIFI, G.TV_ACTION)
            "curious" -> listOf(G.DOCUMENTARY, G.MYSTERY, G.HISTORY) to listOf(G.DOCUMENTARY, G.MYSTERY, G.CRIME)
            else -> emptyList<Int>() to emptyList()
        }
        val (filmFlavour, seriesFlavour) = when (answers["flavour"]) {
            "action" -> listOf(G.ACTION, G.ADVENTURE) to listOf(G.TV_ACTION)
            "crime" -> listOf(G.CRIME) to listOf(G.CRIME)
            "romance" -> listOf(G.ROMANCE) to listOf(G.DRAMA)
            "mystery" -> listOf(G.MYSTERY) to listOf(G.MYSTERY)
            "animation" -> listOf(G.ANIMATION) to listOf(G.ANIMATION)
            "history" -> listOf(G.HISTORY, G.WAR) to listOf(G.TV_WAR, G.DOCUMENTARY)
            else -> emptyList<Int>() to emptyList()
        }
        val tone = answers["tone"]
        val filmExclude = buildList {
            if (family) addAll(listOf(G.HORROR, G.THRILLER, G.WAR))
            if (tone == "light") addAll(listOf(G.HORROR, G.WAR))
            if (tone == "dark") addAll(listOf(G.FAMILY, G.ANIMATION))
        }.distinct().filterNot { it in filmMood }
        val seriesExclude = buildList {
            if (family) addAll(listOf(G.CRIME, G.TV_WAR))
            if (tone == "dark") addAll(listOf(G.FAMILY, G.TV_KIDS, G.ANIMATION))
        }.distinct().filterNot { it in seriesMood }
        val filmBonus = (filmFlavour + if (family) listOf(G.FAMILY, G.ANIMATION) else emptyList()).distinct()
        val seriesBonus = (seriesFlavour + if (family) listOf(G.FAMILY, G.TV_KIDS, G.ANIMATION) else emptyList()).distinct()

        val year = today.year
        val (from, to) = when (answers["era"]) {
            "new" -> "${year - 3}-01-01" to null
            "2010s" -> "2010-01-01" to "2019-12-31"
            "2000s" -> "2000-01-01" to "2009-12-31"
            "classic" -> null to "1999-12-31"
            else -> null to null
        }
        val (filmMax, seriesMax, seriesMin) = when (answers["length"]) {
            "short" -> Triple(105, 35, null)
            "normal" -> Triple(135, 65, null)
            else -> Triple(null, null, null)
        }
        val minRating = when (answers["rating"]) {
            "great" -> 7.5
            "good" -> 6.5
            else -> null
        }
        val (minVotes, maxVotes, sort) = when (answers["popularity"]) {
            "popular" -> Triple(1_000, null, "popularity.desc")
            "gem" -> Triple(60, 1_500, "vote_average.desc")
            else -> Triple(150, null, "popularity.desc")
        }
        return Criteria(
            films = kind != "series",
            series = kind != "film",
            filmGenres = filmMood,
            seriesGenres = seriesMood,
            filmBonus = filmBonus,
            seriesBonus = seriesBonus,
            filmExclude = filmExclude,
            seriesExclude = seriesExclude,
            fromDate = from,
            toDate = to,
            filmMaxRuntime = filmMax,
            seriesMaxRuntime = seriesMax,
            seriesMinRuntime = seriesMin,
            minRating = minRating,
            minVotes = minVotes,
            maxVotes = maxVotes,
            sortBy = sort,
            englishOnly = answers["language"] == "english",
            familyFriendly = family,
        )
    }

    /**
     * Looser versions of [c], tried in turn when the playlists hold too few matches: first the
     * running time and era go, then the review bar and popularity, finally the mood.
     */
    fun relaxations(c: Criteria): List<Criteria> = listOf(
        c,
        c.copy(filmMaxRuntime = null, seriesMaxRuntime = null, seriesMinRuntime = null),
        c.copy(filmMaxRuntime = null, seriesMaxRuntime = null, seriesMinRuntime = null, fromDate = null, toDate = null),
        c.copy(
            filmMaxRuntime = null, seriesMaxRuntime = null, seriesMinRuntime = null, fromDate = null, toDate = null,
            minRating = null, minVotes = 50, maxVotes = null, sortBy = "popularity.desc",
        ),
        c.copy(
            filmMaxRuntime = null, seriesMaxRuntime = null, seriesMinRuntime = null, fromDate = null, toDate = null,
            minRating = null, minVotes = 50, maxVotes = null, sortBy = "popularity.desc", filmGenres = emptyList(), seriesGenres = emptyList(),
        ),
    ).distinct()

    /** TMDB's discover address for films ("movie") or series ("tv"). Mood genres are any-of. */
    fun discoverUrl(type: String, c: Criteria, page: Int): String {
        val tv = type == "tv"
        val params = linkedMapOf<String, String>()
        params["language"] = "en-GB"
        params["page"] = page.toString()
        params["sort_by"] = c.sortBy
        params["include_adult"] = "false"
        params["vote_count.gte"] = c.minVotes.toString()
        c.maxVotes?.let { params["vote_count.lte"] = it.toString() }
        c.minRating?.let { params["vote_average.gte"] = it.toString() }
        val genres = if (tv) c.seriesGenres else c.filmGenres
        if (genres.isNotEmpty()) params["with_genres"] = genres.joinToString("|")
        val exclude = if (tv) c.seriesExclude else c.filmExclude
        if (exclude.isNotEmpty()) params["without_genres"] = exclude.joinToString(",")
        val dateKey = if (tv) "first_air_date" else "primary_release_date"
        c.fromDate?.let { params["$dateKey.gte"] = it }
        c.toDate?.let { params["$dateKey.lte"] = it }
        if (tv) {
            c.seriesMaxRuntime?.let { params["with_runtime.lte"] = it.toString() }
            c.seriesMinRuntime?.let { params["with_runtime.gte"] = it.toString() }
        } else {
            c.filmMaxRuntime?.let { params["with_runtime.lte"] = it.toString() }
            if (c.familyFriendly) {
                params["certification_country"] = "GB"
                params["certification.lte"] = "PG"
            }
        }
        if (c.englishOnly) params["with_original_language"] = "en"
        return "$BASE/discover/$type?" + params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun parseDiscover(text: String, type: String): List<Found> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            Found(
                id = str("id")?.toLongOrNull() ?: return@mapNotNull null,
                type = type,
                title = str("title") ?: str("name") ?: return@mapNotNull null,
                year = (str("release_date") ?: str("first_air_date"))?.take(4)?.toIntOrNull(),
                rating = str("vote_average")?.toDoubleOrNull(),
                votes = str("vote_count")?.toDoubleOrNull()?.toInt() ?: 0,
                popularity = str("popularity")?.toDoubleOrNull() ?: 0.0,
                genreIds = (item["genre_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty(),
                overview = str("overview"),
                poster = str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
            )
        }
    }

    /** How strongly [found] fits: liked genres first, then TMDB's own order. */
    fun bonus(found: Found, c: Criteria): Int {
        val liked = if (found.type == "tv") c.seriesBonus else c.filmBonus
        return found.genreIds.count { it in liked }
    }

    /**
     * Short notes on what sets each pick apart from the rest of the shortlist: the best reviewed,
     * the shortest, the newest, a genre only it has. Every pick gets at least one note.
     */
    fun differences(picks: List<PickFacts>): List<List<String>> {
        val notes = List(picks.size) { ArrayList<String>() }
        if (picks.size < 2) {
            picks.forEachIndexed { i, p -> notes[i] += describe(p) }
            return notes
        }
        fun <T : Comparable<T>> mark(value: (PickFacts) -> T?, highest: Boolean, text: (PickFacts) -> String) {
            val known = picks.withIndex().filter { value(it.value) != null }
            if (known.size < 2) return
            val best = if (highest) known.maxByOrNull { value(it.value)!! } else known.minByOrNull { value(it.value)!! }
            val tied = known.count { value(it.value) == value(best!!.value) }
            if (best != null && tied == 1) notes[best.index] += text(best.value)
        }
        mark({ it.rating }, true) { "Best reviewed of these (★ %.1f)".format(it.rating) }
        mark({ it.popularity }, true) { "The most popular pick" }
        mark({ it.year }, true) { "The newest (${it.year})" }
        mark({ it.year }, false) { "The oldest (${it.year})" }
        val films = picks.filter { !it.isSeries && it.runtimeMinutes != null }
        if (films.size >= 2) {
            mark({ p -> p.runtimeMinutes.takeIf { !p.isSeries } }, false) { "The shortest film, at ${hours(it.runtimeMinutes!!)}" }
            mark({ p -> p.runtimeMinutes.takeIf { !p.isSeries } }, true) { "The longest film, at ${hours(it.runtimeMinutes!!)}" }
        }
        picks.forEachIndexed { i, p ->
            val unique = p.genres.firstOrNull { g -> picks.count { other -> g in other.genres } == 1 }
            if (unique != null) notes[i] += "The only ${unique.lowercase()} pick"
            if (p.isSeries && p.seasons != null) notes[i] += if (p.seasons == 1) "A single season" else "${p.seasons} seasons to get into"
        }
        return notes.mapIndexed { i, list -> (list.ifEmpty { listOf(describe(picks[i])) }).take(3) }
    }

    private fun describe(p: PickFacts): String = listOfNotNull(p.genres.firstOrNull(), p.year?.toString()).joinToString(", ").ifEmpty { "A good fit" }

    fun hours(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
}
