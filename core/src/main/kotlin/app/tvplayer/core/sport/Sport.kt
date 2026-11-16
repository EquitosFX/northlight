package app.tvplayer.core.sport

/** The sports the hub sorts programmes into. */
enum class SportKind(val label: String) {
    FOOTBALL("Football"),
    RUGBY("Rugby"),
    CRICKET("Cricket"),
    MOTORSPORT("Motorsport"),
    GOLF("Golf"),
    TENNIS("Tennis"),
    FIGHTING("Boxing and UFC"),
    DARTS("Darts"),
    SNOOKER("Snooker"),
    AMERICAN_FOOTBALL("NFL"),
    BASKETBALL("Basketball"),
    ICE_HOCKEY("Ice hockey"),
    BASEBALL("Baseball"),
    RACING("Horse racing"),
    CYCLING("Cycling"),
    OTHER("More sport"),
}

/**
 * Finds sport in the TV guide: from the programme's category, words in its title (league and
 * competition names, "v" between two teams) and whether it's on a sports channel.
 */
object SportFinder {
    // Checked in this order, so "Premier League Darts" is darts and "Super League" rugby before football.
    private val keywords: List<Pair<SportKind, List<String>>> = listOf(
        SportKind.DARTS to listOf("darts", "pdc", "world matchplay", "grand slam of darts"),
        SportKind.SNOOKER to listOf("snooker", "crucible", "pool"),
        SportKind.AMERICAN_FOOTBALL to listOf("nfl", "american football", "super bowl", "college football", "ncaa football"),
        SportKind.RUGBY to listOf("rugby", "six nations", "super league", "nrl", "urc", "champions cup", "gallagher premiership", "challenge cup"),
        SportKind.CRICKET to listOf("cricket", "test match", "ipl", "the hundred", "t20", "ashes", "odi", "county championship", "big bash"),
        SportKind.MOTORSPORT to listOf("formula 1", "formula one", "f1", "grand prix", "motogp", "nascar", "indycar", "formula e", "btcc", "wrc", "superbikes", "le mans"),
        SportKind.GOLF to listOf("golf", "pga", "dp world tour", "ryder cup", "the open", "masters", "lpga", "liv golf", "solheim"),
        SportKind.TENNIS to listOf("tennis", "atp", "wta", "wimbledon", "roland garros", "australian open", "us open", "davis cup"),
        SportKind.FIGHTING to listOf("boxing", "ufc", "mma", "wwe", "fight night", "bellator", "pfl"),
        SportKind.BASKETBALL to listOf("nba", "basketball", "euroleague", "wnba"),
        SportKind.ICE_HOCKEY to listOf("nhl", "ice hockey", "hockey"),
        SportKind.BASEBALL to listOf("mlb", "baseball", "world series"),
        SportKind.RACING to listOf("horse racing", "racing tv", "cheltenham", "grand national", "royal ascot", "epsom", "the derby", "itv racing"),
        SportKind.CYCLING to listOf("cycling", "tour de france", "giro", "vuelta"),
        SportKind.FOOTBALL to listOf(
            "football", "premier league", "champions league", "europa league", "conference league", "efl", "fa cup",
            "carabao", "la liga", "laliga", "serie a", "bundesliga", "ligue 1", "spfl", "scottish premiership", "soccer",
            "championship", "world cup", "euro 20", "mls", "eredivisie", "womens super league", "wsl", "nations league",
            "copa", "saudi pro league", "match of the day", "goals",
        ),
    )

    private val sportsChannel = Regex(
        """\b(sports?|tnt sports|eurosport|dazn|premier sports|viaplay sports|racing|golf|f1|nba tv|nfl|mutv|lfc tv|chelsea tv|bein|espn|sky sports)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val versus = Regex("""\S\s+(v|vs|vs\.|versus)\s+\S""", RegexOption.IGNORE_CASE)
    private val liveMark = Regex("""(^|[\s:(\[-])live\b""", RegexOption.IGNORE_CASE)
    private val notSport = Regex("""\b(news|update|preview|magazine|weather)\b""", RegexOption.IGNORE_CASE)

    /** True for a sports channel (by its name or group). */
    fun isSportsChannel(name: String, group: String? = null): Boolean =
        sportsChannel.containsMatchIn(name) || (group != null && Regex("""\bsports?\b""", RegexOption.IGNORE_CASE).containsMatchIn(group))

    /**
     * The sport [title] is, or null if it doesn't look like sport. [category] is the guide's genre;
     * [onSportsChannel] says whether it's shown on a sports channel.
     */
    fun classify(title: String, category: String?, onSportsChannel: Boolean): SportKind? {
        val text = (title + " " + category.orEmpty()).lowercase()
        val kind = keywords.firstOrNull { (_, words) -> words.any { word -> containsWord(text, word) } }?.first
        val sportCategory = category?.contains("sport", ignoreCase = true) == true
        val match = versus.containsMatchIn(title)
        return when {
            kind != null && (sportCategory || onSportsChannel || match || isLive(title)) -> kind
            kind != null && kind != SportKind.FOOTBALL && kind != SportKind.SNOOKER -> kind
            // Bulletins and magazine shows on sports channels aren't events.
            notSport.containsMatchIn(title) -> null
            sportCategory || (onSportsChannel && (match || isLive(title))) -> kind ?: SportKind.OTHER
            else -> null
        }
    }

    fun isLive(title: String): Boolean = liveMark.containsMatchIn(title)

    fun isMatch(title: String): Boolean = versus.containsMatchIn(title)

    /** The title without a leading "Live:" or "LIVE", for display. */
    fun cleanTitle(title: String): String =
        title.replace(Regex("""^\s*(live|LIVE)\s*[:\-–]?\s*"""), "").trim().ifEmpty { title }

    private fun containsWord(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIndex = i + word.length
            val after = if (afterIndex >= text.length) ' ' else text[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetter()) return true
            from = i + 1
        }
    }
}
