package app.tvplayer.core.guide

import app.tvplayer.core.channels.ChannelVariants

enum class MatchMethod { TVG_ID, TVG_NAME, NAME }

data class GuideMatch(val guideId: String, val method: MatchMethod)

/** The parts of a playlist channel that can identify it in a guide file. */
data class ChannelRef(val name: String, val tvgId: String? = null, val tvgName: String? = null)

/**
 * Works out which guide channel belongs to each playlist channel.
 *
 * Tried in order, most reliable first: the playlist's guide id, then its guide name, then the
 * channel name itself with country prefixes ("UK: ") and quality markers ("FHD") ignored.
 * Anything left unmatched is for the user to link by hand.
 */
object EpgMatcher {

    private val countryPrefix = Regex("""^\s*[A-Za-z]{2,3}\s*[:|]\s*""")

    /** Returns one result per entry of [channels], in the same order; null where nothing matched. */
    fun match(channels: List<ChannelRef>, guide: List<GuideChannel>): List<GuideMatch?> {
        val byId = HashMap<String, String>()
        val byDisplayName = HashMap<String, String>()
        val byKey = HashMap<String, String>()
        for (channel in guide) {
            byId.putIfAbsent(channel.id.trim().lowercase(), channel.id)
            for (displayName in channel.displayNames) {
                byDisplayName.putIfAbsent(displayName.trim().lowercase(), channel.id)
                val key = looseKey(displayName)
                if (key.isNotEmpty()) byKey.putIfAbsent(key, channel.id)
            }
        }

        return channels.map { channel ->
            val tvgId = channel.tvgId?.trim()?.lowercase().orEmpty()
            val tvgName = channel.tvgName?.trim().orEmpty()
            val name = channel.name.trim()

            byId[tvgId]?.takeIf { tvgId.isNotEmpty() }?.let { return@map GuideMatch(it, MatchMethod.TVG_ID) }
            if (tvgName.isNotEmpty()) {
                (byDisplayName[tvgName.lowercase()] ?: byKey[looseKey(tvgName)])
                    ?.let { return@map GuideMatch(it, MatchMethod.TVG_NAME) }
            }
            if (name.isNotEmpty()) {
                (byDisplayName[name.lowercase()] ?: byKey[looseKey(name)])
                    ?.let { return@map GuideMatch(it, MatchMethod.NAME) }
            }
            null
        }
    }

    private fun looseKey(name: String): String =
        ChannelVariants.analyse(name.replaceFirst(countryPrefix, "")).key
}
