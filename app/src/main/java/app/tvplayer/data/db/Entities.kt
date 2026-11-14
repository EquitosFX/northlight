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
    val guideId: String? = null,
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
