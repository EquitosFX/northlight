package app.tvplayer.ui.home

import app.tvplayer.player.VodItem

/** The large panel at the top of Home. */
data class HomeHero(
    val overline: String,
    val title: String,
    val tagline: String?,
    val meta: String?,
    val logo: String?,
    val primaryLabel: String,
    val onPrimary: () -> Unit,
    val secondaryLabel: String? = null,
    val onSecondary: () -> Unit = {},
)

/** One tile on a Home or Recents rail. Selecting it plays whichever of [vod], [channelKey] or [channelId] is set. */
data class Tile(
    val title: String,
    val caption: String,
    val color: Long,
    val badge: String? = null,
    val progress: Float? = null,
    val channelId: Long? = null,
    /** A live channel by its stable key ("playlistId|variantKey"). */
    val channelKey: String? = null,
    val logo: String? = null,
    val vod: VodItem? = null,
)

/** A titled row of tiles. */
data class HomeRail(val title: String, val tiles: List<Tile>)
