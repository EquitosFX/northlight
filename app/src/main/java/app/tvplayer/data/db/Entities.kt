package app.tvplayer.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A source the user has added: an Xtream Codes account or an M3U link. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** The server address (Xtream Codes) or the playlist link (M3U). */
    val address: String,
    val username: String? = null,
    val password: String? = null,
    /** Guide addresses this source came with, one per line. */
    val guideUrls: String = "",
    val addedAt: Long,
    val refreshedAt: Long? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val accountStatus: String? = null,
    val expiresAt: Long? = null,
    val guideRefreshedAt: Long? = null,
    val programmeCount: Int = 0,
) {
    companion object {
        const val TYPE_XTREAM = "xtream"
        const val TYPE_M3U = "m3u"
    }
}

/**
 * One live channel, film or series from a playlist.
 * Removing a playlist removes its rows here too.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlistId", "kind", "groupName"),
        Index("playlistId", "variantKey"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val kind: String,
    val name: String,
    /** The name with quality and backup markers removed ("North One HD" becomes "North One"). */
    val baseName: String,
    /** Rows with the same key in one playlist are copies of the same channel. */
    val variantKey: String,
    val quality: Int = 0,
    val isBackup: Boolean = false,
    val url: String,
    /** The provider's own id for this item, when it has one (Xtream Codes). */
    val remoteId: Long? = null,
    val logo: String? = null,
    val groupName: String? = null,
    val number: Int? = null,
    /** The guide id the playlist gave this channel (tvg-id). */
    val guideId: String? = null,
    /** The guide channel actually matched to this one when the guide was last imported. */
    val epgId: String? = null,
    val catchupDays: Int? = null,
    val catchupType: String? = null,
    val catchupSource: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Order within the playlist, as the provider listed it. */
    val position: Int,
) {
    companion object {
        const val KIND_LIVE = "live"
        const val KIND_MOVIE = "movie"
        const val KIND_SERIES = "series"
    }
}

/** One programme from a playlist's TV guide. Times are UTC milliseconds. */
@Entity(
    tableName = "programmes",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId", "channelGuideId", "start")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelGuideId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subTitle: String? = null,
    val description: String? = null,
    val category: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * How far the viewer got through a film or episode. Keyed by the stream address, which stays the
 * same when a playlist is refreshed (row ids do not). Holds enough to play it again from Home.
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val url: String,
    val title: String,
    val poster: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** A favourite live channel, keyed like the channel line-up ("playlistId|variantKey") so it survives refreshes. */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val key: String,
    val addedAt: Long,
)
