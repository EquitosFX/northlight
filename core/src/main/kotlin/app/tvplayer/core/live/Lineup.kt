package app.tvplayer.core.live

/** One playlist row, as the player needs it. [name] is the name with quality markers already removed. */
data class LineupRow(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val variantKey: String,
    val quality: Int,
    val isBackup: Boolean,
    val logo: String? = null,
    val group: String? = null,
    val number: Int? = null,
    /** The TV guide's id for this channel, when it has been matched to one. */
    val guideId: String? = null,
    /** Days of past programmes the provider keeps for this channel (catch-up); null or 0 for none. */
    val catchupDays: Int? = null,
)

/** A channel as the viewer sees it: one entry however many quality copies the provider lists. */
data class LineupChannel(
    /** Stable across playlist refreshes (row ids are not), so it is what "last channel" remembers. */
    val key: String,
    val name: String,
    val logo: String?,
    val group: String?,
    val number: Int,
    /** Row ids of every copy, best first, backups last. Playback falls through them in order. */
    val variantIds: List<Long>,
    val playlistId: Long = 0,
    val guideId: String? = null,
    val catchupDays: Int? = null,
)

/** The live channels across all playlists, in the order the providers list them. */
class Lineup private constructor(val channels: List<LineupChannel>) {

    val isEmpty: Boolean get() = channels.isEmpty()

    /** Group names in the order they first appear. */
    val groups: List<String> = channels.mapNotNull { it.group }.distinct()

    private val byGroup: Map<String, List<LineupChannel>> =
        channels.filter { it.group != null }.groupBy { it.group!! }

    /** The channels in [group], or every channel when [group] is null. */
    fun inGroup(group: String?): List<LineupChannel> = if (group == null) channels else byGroup[group].orEmpty()

    fun findById(rowId: Long): LineupChannel? = channels.firstOrNull { rowId in it.variantIds }

    fun findByKey(key: String): LineupChannel? = channels.firstOrNull { it.key == key }

    companion object {
        val EMPTY = Lineup(emptyList())

        /** [rows] must already be in display order. Copies are merged only within the same playlist. */
        fun build(rows: List<LineupRow>): Lineup {
            val merged = LinkedHashMap<String, MutableList<LineupRow>>()
            for (row in rows) {
                merged.getOrPut("${row.playlistId}|${row.variantKey}") { ArrayList(2) }.add(row)
            }
            var position = 0
            val channels = merged.map { (key, members) ->
                position++
                val first = members.first()
                val ordered = members.sortedWith(
                    compareBy<LineupRow> { it.isBackup }.thenByDescending { it.quality },
                )
                LineupChannel(
                    key = key,
                    name = first.name,
                    logo = members.firstNotNullOfOrNull { it.logo?.takeIf { logo -> logo.isNotBlank() } },
                    group = first.group,
                    number = members.firstNotNullOfOrNull { it.number } ?: position,
                    variantIds = ordered.map { it.id },
                    playlistId = first.playlistId,
                    guideId = members.firstNotNullOfOrNull { it.guideId },
                    catchupDays = members.mapNotNull { it.catchupDays }.maxOrNull(),
                )
            }
            return Lineup(channels)
        }

        /** Moves [delta] places from [index] in a list of [size], wrapping at both ends. Returns -1 for an empty list. */
        fun step(size: Int, index: Int, delta: Int): Int = if (size <= 0) -1 else Math.floorMod(index + delta, size)
    }
}

/** How long to wait before each reconnect attempt when a stream drops. */
object Reconnect {
    const val MAX_ATTEMPTS = 3

    /** 1, 2 then 4 seconds; never more than 8. */
    fun delayMillis(attempt: Int): Long = (1000L shl (attempt - 1).coerceIn(0, 3)).coerceAtMost(8000L)
}
