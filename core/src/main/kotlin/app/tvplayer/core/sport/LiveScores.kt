package app.tvplayer.core.sport

import app.tvplayer.core.search.SearchText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** A match's score, as ESPN's scoreboard gives it. */
data class LiveScore(
    val home: Team,
    val away: Team,
    /** Kick-off (or start), in milliseconds. */
    val start: Long,
    val state: State,
    /** ESPN's short word for where it's got to: "63'", "HT", "FT", "Q3 4:12". */
    val detail: String?,
) {
    data class Team(val names: List<String>, val abbreviation: String?, val score: String?)

    enum class State { BEFORE, PLAYING, FINISHED }

    /** "CHE 2–1 ARS", home first. */
    val line: String
        get() = "${home.abbreviation ?: home.names.first()} ${home.score ?: "0"}–${away.score ?: "0"} ${away.abbreviation ?: away.names.first()}"
}

/**
 * Live scores from ESPN's free scoreboard feeds, and which of the guide's sport programmes they belong
 * to: both teams named in the programme's title, and the match starting while the programme is on (so a
 * replay later in the day, or on a catch-up channel, never shows the score).
 */
object LiveScores {
    private val json = Json { ignoreUnknownKeys = true }

    /** The scoreboards to read for each sport (ESPN's own names for them). */
    val feeds: Map<SportKind, List<String>> = mapOf(
        SportKind.FOOTBALL to listOf(
            "soccer/eng.1", "soccer/eng.2", "soccer/eng.3", "soccer/eng.4", "soccer/eng.fa", "soccer/eng.league_cup",
            "soccer/sco.1", "soccer/uefa.champions", "soccer/uefa.europa", "soccer/uefa.europa.conf",
            "soccer/esp.1", "soccer/ger.1", "soccer/ita.1", "soccer/fra.1", "soccer/uefa.nations", "soccer/fifa.world",
            "soccer/fifa.friendly",
        ),
        // Rugby union: Six Nations, Premiership, URC, Champions Cup, Challenge Cup, Top 14, Rugby Championship, tests.
        SportKind.RUGBY to listOf(
            "rugby/180659", "rugby/267979", "rugby/270557", "rugby/271937", "rugby/272073", "rugby/270559", "rugby/244293",
            "rugby/289234",
        ),
        SportKind.AMERICAN_FOOTBALL to listOf("football/nfl", "football/college-football"),
        SportKind.BASKETBALL to listOf("basketball/nba"),
        SportKind.ICE_HOCKEY to listOf("hockey/nhl"),
        SportKind.BASEBALL to listOf("baseball/mlb"),
    )

    fun url(feed: String) = "https://site.api.espn.com/apis/site/v2/sports/$feed/scoreboard"

    /** The matches on a scoreboard; nothing if it can't be read. */
    fun parse(text: String): List<LiveScore> = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        (root["events"] as? JsonArray).orEmpty().mapNotNull { element -> event(element as? JsonObject ?: return@mapNotNull null) }
    }.getOrDefault(emptyList())

    private fun event(event: JsonObject): LiveScore? {
        val competition = (event["competitions"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val competitors = (competition["competitors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val home = competitors.firstOrNull { it.text("homeAway") == "home" } ?: competitors.getOrNull(0) ?: return null
        val away = competitors.firstOrNull { it.text("homeAway") == "away" } ?: competitors.getOrNull(1) ?: return null
        val status = (event["status"] as? JsonObject) ?: (competition["status"] as? JsonObject)
        val type = status?.get("type") as? JsonObject
        val state = when (type?.text("state")) {
            "in" -> LiveScore.State.PLAYING
            "post" -> LiveScore.State.FINISHED
            else -> LiveScore.State.BEFORE
        }
        val start = (event.text("date") ?: competition.text("date"))?.let(::instant) ?: return null
        return LiveScore(
            home = team(home) ?: return null,
            away = team(away) ?: return null,
            start = start,
            state = state,
            detail = type?.text("shortDetail") ?: status?.text("displayClock") ?: type?.text("detail"),
        )
    }

    private fun team(competitor: JsonObject): LiveScore.Team? {
        val team = competitor["team"] as? JsonObject ?: return null
        val names = listOfNotNull(
            team.text("displayName"),
            team.text("shortDisplayName"),
            team.text("name"),
            team.text("location"),
        ).filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return null
        val score = competitor["score"].let { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.text("displayValue")
                else -> null
            }
        }
        return LiveScore.Team(names, team.text("abbreviation"), score)
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** ESPN writes "2026-11-21T15:00Z" (no seconds). */
    private fun instant(text: String): Long? = try {
        Instant.parse(if (Regex("T\\d{2}:\\d{2}Z$").containsMatchIn(text)) text.replace("Z", ":00Z") else text).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    /** Words a programme uses for something other than the match being played now. */
    private val notLive = Regex("""\b(highlights|replay|repeat|classic|rewind|review|best of|reloaded)\b|\(r\)""", RegexOption.IGNORE_CASE)

    /**
     * The score for a programme: one that names both teams in its [title] and whose match starts while
     * the programme is on (a programme starting up to two hours before, for the build-up, counts). Never
     * for highlights, replays and the like.
     */
    fun forProgramme(title: String, start: Long, stop: Long, scores: List<LiveScore>): LiveScore? {
        if (notLive.containsMatchIn(title)) return null
        val condensed = SearchText.condense(title)
        return scores.firstOrNull { score ->
            score.start in (start - EARLY_MILLIS)..stop && named(condensed, score.home) && named(condensed, score.away)
        }
    }

    private const val EARLY_MILLIS = 30 * 60_000L

    /** Whether a team is named in a (condensed) title: by any of its names, or a shorter form of one. */
    private fun named(title: String, team: LiveScore.Team): Boolean = forms(team).any { it.length >= 4 && title.contains(it) }

    private val suffixes = listOf("fc", "afc", "cf", "sc")

    /** A team's names as titles write them: "Manchester United" is also "Man Utd", "Man United". */
    internal fun forms(team: LiveScore.Team): Set<String> {
        val out = LinkedHashSet<String>()
        for (name in team.names) {
            val condensed = SearchText.condense(name)
            out += condensed
            for (suffix in suffixes) if (condensed.endsWith(suffix) && condensed.length > suffix.length + 3) out += condensed.removeSuffix(suffix)
            out += condensed.replace("united", "utd")
            out += condensed.replace("manchester", "man")
            out += condensed.replace("manchester", "man").replace("united", "utd")
            out += condensed.replace("nottingham", "nottm")
            out += condensed.replace("sheffield", "sheff")
            out += condensed.replace("wolverhamptonwanderers", "wolves")
            out += condensed.replace("tottenhamhotspur", "spurs")
            out += condensed.replace("hotspur", "")
            out += condensed.replace("athletic", "")
            out += condensed.replace("wanderers", "")
        }
        return out
    }
}

