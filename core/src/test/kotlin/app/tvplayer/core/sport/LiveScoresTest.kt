package app.tvplayer.core.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LiveScoresTest {
    private val board = """
        {"leagues":[{"name":"English Premier League"}],"events":[
          {"id":"1","date":"2026-11-21T15:00Z","name":"Arsenal at Manchester United",
           "status":{"displayClock":"63'","type":{"state":"in","completed":false,"detail":"63'","shortDetail":"63'"}},
           "competitions":[{"competitors":[
             {"homeAway":"home","score":"2","team":{"displayName":"Manchester United","shortDisplayName":"Man United","abbreviation":"MUN","name":"Manchester United","location":"Manchester United"}},
             {"homeAway":"away","score":"1","team":{"displayName":"Arsenal","shortDisplayName":"Arsenal","abbreviation":"ARS"}}]}]},
          {"id":"2","date":"2026-11-21T17:30Z",
           "competitions":[{"status":{"type":{"state":"pre","shortDetail":"5:30 PM"}},"competitors":[
             {"homeAway":"home","score":"0","team":{"displayName":"Tottenham Hotspur","shortDisplayName":"Tottenham","abbreviation":"TOT"}},
             {"homeAway":"away","score":"0","team":{"displayName":"Wolverhampton Wanderers","shortDisplayName":"Wolves","abbreviation":"WOL"}}]}]}
        ]}
    """.trimIndent()

    private val kickoff = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    @Test fun readsTheScoreboard() {
        val scores = LiveScores.parse(board)
        assertEquals(2, scores.size)
        val first = scores[0]
        assertEquals(LiveScore.State.PLAYING, first.state)
        assertEquals("63'", first.detail)
        assertEquals("MUN 2–1 ARS", first.line)
        assertEquals(kickoff, first.start)
        assertEquals(LiveScore.State.BEFORE, scores[1].state)
        assertEquals(emptyList<LiveScore>(), LiveScores.parse("not json"))
    }

    @Test fun findsTheMatchForTheProgrammeShowingIt() {
        val scores = LiveScores.parse(board)
        // The way UK guides write it, with the build-up before kick-off.
        assertNotNull(LiveScores.forProgramme("Live Premier League: Man Utd v Arsenal", kickoff - hour, kickoff + 2 * hour, scores))
        assertNotNull(LiveScores.forProgramme("Spurs v Wolves", kickoff + 2 * hour, kickoff + 5 * hour, scores))
        assertNotNull(LiveScores.forProgramme("Tottenham v Wolverhampton", kickoff + 2 * hour, kickoff + 5 * hour, scores))
    }

    @Test fun neverForReplaysOrOtherTimes() {
        val scores = LiveScores.parse(board)
        // Shown again in the evening (a catch-up or replay channel): the match started before it.
        assertNull(LiveScores.forProgramme("Man Utd v Arsenal", kickoff + 6 * hour, kickoff + 8 * hour, scores))
        assertNull(LiveScores.forProgramme("Highlights: Man Utd v Arsenal", kickoff, kickoff + 2 * hour, scores))
        assertNull(LiveScores.forProgramme("Classic Match: Man Utd v Arsenal", kickoff, kickoff + 2 * hour, scores))
        // Only one of the teams named: another match.
        assertNull(LiveScores.forProgramme("Man Utd v Chelsea", kickoff, kickoff + 2 * hour, scores))
    }
}
