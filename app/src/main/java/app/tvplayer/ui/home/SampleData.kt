package app.tvplayer.ui.home

/**
 * Invented placeholder content so the Home screen can be seen before the data layer exists.
 * Phase 2 replaces this with the user's own playlists, guide data and artwork.
 */
data class HeroItem(
    val overline: String,
    val title: String,
    val tagline: String,
    val meta: String,
    val artColor: Long,
)

data class Tile(
    val title: String,
    val caption: String,
    val color: Long,
    val badge: String? = null,
    val progress: Float? = null,
)

object SampleData {

    val hero = HeroItem(
        overline = "NEW SERIES",
        title = "The Harbour Line",
        tagline = "A ferry town, a missing skipper and the tide table nobody checked.",
        meta = "Drama · 8 episodes · 4K",
        artColor = 0xFFB9503A,
    )

    val forYou = listOf(
        Tile("Arena Sport 1", "Cup Replay", 0xFF2F7D50, badge = "Now playing", progress = 0.62f),
        Tile("The Paper Round", "All episodes", 0xFF3E5C8A),
        Tile("Low Tide", "Top pick for you", 0xFF8A4B2E),
        Tile("Signal Fires", "New this week", 0xFF5A3B78),
        Tile("Kitchen Table Quiz", "Continue · S2 E4", 0xFF2E6F78, progress = 0.35f),
        Tile("Reef at Night", "Because you watched Low Tide", 0xFF4B5D67),
    )

    val onNow = listOf(
        Tile("The Morning Table", "101 · North One", 0xFFB93A36, progress = 0.55f),
        Tile("News Hour", "102 · North Two", 0xFF2F6A9E, progress = 0.2f),
        Tile("Metro Morning", "103 · Metro News", 0xFF4B5D67, progress = 0.4f),
        Tile("The Long Ferry", "105 · Cinema Box", 0xFF8A4B2E, progress = 0.4f),
        Tile("Kitchen Table Quiz", "106 · Comedy Yard", 0xFF8F5E14, progress = 0.7f),
        Tile("Puddle Club", "107 · Tiny Planet", 0xFF5A3B78, progress = 0.15f),
    )

    val shortcuts = listOf("Live TV", "Series", "Films", "Kids", "Sport")
}
