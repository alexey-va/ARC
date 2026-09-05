package ru.arc.helpcenter

import java.text.Normalizer
import java.math.BigDecimal
import java.util.Locale
import java.util.UUID
import ru.arc.network.NetworkPlayerName

enum class HelpCenterPage(vararg val aliases: String) {
    ROOT("root", "главная"),
    NOW("now", "сейчас"),
    MY("my", "мое", "моё", "про меня"),
    GUIDE("guide", "гайд", "start", "начало"),
    COMMANDS("commands", "команды"),
    TRAVEL("travel", "перемещения", "телепортация", "телепорт", "homes", "дома"),
    PRIVAT("privat", "приват", "lands", "земли"),
    ACTIVITIES("activities", "активности", "играть"),
    PLAYERS("players", "игроки", "друзья"),
    TECHNOLOGY("technology", "технологии", "предметы"),
    SETTINGS("settings", "настройки"),
    RECOVERY("recovery", "проблема", "что случилось"),
    FAVORITES("favorites", "избранное", "недавнее"),
    GOALS("goals", "заняться", "цели"),
    ITEM("item", "предмет", "в руке"),
    CONTEXT("context", "рядом", "контекст"),
    REQUESTS("requests", "запросы", "входящие"),
    ;

    companion object {
        fun from(value: String): HelpCenterPage? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { page -> page.aliases.any { it == normalized } }
        }
    }
}

enum class HelpCenterCategory(val configId: String) {
    START("start"),
    TRAVEL("travel"),
    PROTECTION("protection"),
    ACTIVITIES("activities"),
    TRADE("trade"),
    PROGRESS("progress"),
    SOCIAL("social"),
    TECHNOLOGY("technology"),
    SETTINGS("settings");

    companion object {
        val rootHubs: List<HelpCenterCategory> = listOf(ACTIVITIES, TRADE, PROGRESS, TECHNOLOGY, SETTINGS)
    }
}

enum class HelpCenterFeature(val pluginName: String?) {
    RANKS("ArcRanks"),
    EVENTS("ArcEvents"),
    DUELS("ArcDuels"),
    BATTLE_PASS("BattlePass"),
    GIVEAWAYS("ArcGiveaways"),
    DUNGEONS("EliteMobs"),
    FARMS("ArcFarms"),
    SLIMEFUN("Slimefun"),
    ITEMS("ItemsAdder"),
    ENCHANTMENTS("AdvancedEnchantments"),
    BUILDER("ArcBuilder"),
    MOUNTS(null),
    TRAILS("Trails"),
    LANDS("Lands"),
    VOTES("ArcVotes"),
    BANK("Bank"),
    PLAYER_PARTICLES("PlayerParticles"),
    HUSK_HOMES("HuskHomes"),
}

enum class HelpCenterRecommendationId {
    CREATE_HOME,
    CREATE_LAND,
    RANK_GOAL,
    BATTLE_PASS,
    EVENTS,
}

data class HelpCenterRecommendation(val id: HelpCenterRecommendationId)

data class HelpCenterPlayer(
    val id: UUID,
    val name: String,
    val server: String? = null,
)

data class HelpCenterPlayerPage(
    val items: List<HelpCenterPlayer>,
    val total: Int,
    val page: Int,
    val pages: Int,
)

enum class HelpCenterChatMode { LOCAL, GLOBAL }

data class HelpCenterSettingSnapshot(
    val chatMode: HelpCenterChatMode,
    val trailsEnabled: Boolean? = null,
    val particlesEnabled: Boolean? = null,
)

data class HelpCenterCommand(
    val id: String,
    val category: HelpCenterCategory,
    val command: String,
    val label: String,
    val description: String,
    val keywords: String,
    val requiredFeature: HelpCenterFeature? = null,
    val permission: String? = null,
    val opensInventory: Boolean = false,
)

sealed interface HelpCenterSearchAction {
    data class Execute(val command: String) : HelpCenterSearchAction

    data class OpenPage(val page: HelpCenterPage) : HelpCenterSearchAction

    data object CreateHome : HelpCenterSearchAction
}

data class HelpCenterSearchEntry(
    val id: String,
    val label: String,
    val description: String,
    val keywords: String,
    val action: HelpCenterSearchAction,
    val command: String? = null,
)

data class HelpCenterHome(
    val name: String,
    val server: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
)

data class HelpCenterHomes(
    val homes: List<HelpCenterHome>,
    val usedSlots: Int,
    val maxSlots: Int,
)

data class HelpCenterProfile(
    val playerName: String,
    val server: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val balance: String?,
    val rank: String?,
    val homes: HelpCenterHomes?,
    val lands: Int?,
    val chatMode: HelpCenterChatMode = HelpCenterChatMode.LOCAL,
    val onlinePlayers: Int = 0,
)

object HelpCenterPlanner {
    fun recommendations(
        profile: HelpCenterProfile,
        features: Set<HelpCenterFeature>,
        limit: Int,
    ): List<HelpCenterRecommendation> {
        require(limit in 1..8) { "Help center recommendation limit must be in 1..8" }
        return buildList {
            if (profile.homes?.usedSlots == 0) add(HelpCenterRecommendation(HelpCenterRecommendationId.CREATE_HOME))
            if (profile.lands == 0 && HelpCenterFeature.LANDS in features) {
                add(HelpCenterRecommendation(HelpCenterRecommendationId.CREATE_LAND))
            }
            if (profile.rank != null && HelpCenterFeature.RANKS in features) {
                add(HelpCenterRecommendation(HelpCenterRecommendationId.RANK_GOAL))
            }
            if (HelpCenterFeature.BATTLE_PASS in features) {
                add(HelpCenterRecommendation(HelpCenterRecommendationId.BATTLE_PASS))
            }
            if (HelpCenterFeature.EVENTS in features) add(HelpCenterRecommendation(HelpCenterRecommendationId.EVENTS))
        }.take(limit)
    }

    fun players(
        viewerId: UUID,
        onlinePlayers: Collection<HelpCenterPlayer>,
        query: String,
        limit: Int,
    ): List<HelpCenterPlayer> {
        require(limit in 1..32) { "Help center player limit must be in 1..32" }
        return playerPage(viewerId, onlinePlayers, query, 0, pageSize = limit).items
    }

    fun playerPage(
        viewerId: UUID,
        onlinePlayers: Collection<HelpCenterPlayer>,
        query: String,
        requestedPage: Int,
        server: String? = null,
        pageSize: Int = 12,
    ): HelpCenterPlayerPage {
        require(pageSize in 1..32) { "Help center player page size must be in 1..32" }
        val needle = query.trim()
        val matches = onlinePlayers.asSequence()
            .filter { it.id != viewerId }
            .filter { server == null || it.server == server }
            .filter { needle.isBlank() || it.name.contains(needle, ignoreCase = true) }
            .distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .toList()
        val pages = maxOf(1, (matches.size + pageSize - 1) / pageSize)
        val page = requestedPage.coerceIn(0, pages - 1)
        return HelpCenterPlayerPage(matches.drop(page * pageSize).take(pageSize), matches.size, page, pages)
    }

    fun search(entries: List<HelpCenterSearchEntry>, query: String, limit: Int): List<HelpCenterSearchEntry> {
        require(limit in 1..32) { "Help center search limit must be in 1..32" }
        val needle = normalize(query).removePrefix("/")
        if (needle.isBlank()) return entries.take(limit)
        val allQueryTokens = HelpCenterLexicon.terms(needle)
        val queryTokens = allQueryTokens.filterNot { it.surface in STOP_WORDS }.ifEmpty { allQueryTokens }
        val scored = entries
            .asSequence()
            .map { entry -> entry to score(entry, needle, queryTokens) }
            .filter { (_, score) -> score != Int.MAX_VALUE }
            .sortedWith(compareBy<Pair<HelpCenterSearchEntry, Int>> { it.second }.thenBy { it.first.id })
            .toList()
        val best = scored.firstOrNull()?.second ?: return emptyList()
        return scored
            .asSequence()
            .filter { (_, score) ->
                if (best < TOKEN_SCORE) score < TOKEN_SCORE else score <= best + FUZZY_WINDOW
            }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(entry: HelpCenterSearchEntry, needle: String, queryTokens: List<HelpCenterLexicon.Term>): Int {
        val command = entry.command?.let(::normalize)?.removePrefix("/")
        val label = normalize(entry.label)
        val description = normalize(entry.description)
        val keywords = normalize(entry.keywords)
        val haystack = "$label $description $keywords"
        if (command == needle) return 0
        if (label == needle) return 1
        if (command?.startsWith(needle) == true) return 2
        if (haystack.contains(needle)) return 3

        val fields = listOf(command.orEmpty(), label, description, keywords).map(HelpCenterLexicon::terms)
        var matched = 0
        var total = 0
        for (queryToken in queryTokens) {
            val best = fields.withIndex().minOf { (fieldIndex, fieldTokens) ->
                val tokenMatch = fieldTokens.minOfOrNull { candidate -> tokenCost(queryToken, candidate) }
                    ?: Int.MAX_VALUE
                if (tokenMatch == Int.MAX_VALUE) Int.MAX_VALUE else tokenMatch + fieldIndex * FIELD_WEIGHT
            }
            if (best != Int.MAX_VALUE) {
                matched++
                total += best
            }
        }
        val required = if (queryTokens.size == 1) 1 else (queryTokens.size + 1) / 2
        if (matched < required) return Int.MAX_VALUE
        return TOKEN_SCORE + (queryTokens.size - matched) * MISSING_TOKEN_PENALTY + total
    }

    private fun tokenCost(query: HelpCenterLexicon.Term, candidate: HelpCenterLexicon.Term): Int {
        if (query.surface == candidate.surface) return 0
        if (query.surface == HelpCenterLexicon.phoneticCyrillic(candidate.surface)) return 1
        if (query.root.length >= 3 && query.root == candidate.root) return 1
        if (
            minOf(query.surface.length, candidate.surface.length) >= 3 &&
            (query.surface.startsWith(candidate.surface) || candidate.surface.startsWith(query.surface))
        ) return 2
        if (commonPrefix(query.root, candidate.root) >= 4) return 3
        val trigramSimilarity = maxOf(
            HelpCenterLexicon.trigramSimilarity(query.surface, candidate.surface),
            HelpCenterLexicon.trigramSimilarity(query.root, candidate.root),
            HelpCenterLexicon.trigramSimilarity(query.surface, HelpCenterLexicon.phoneticCyrillic(candidate.surface)),
        )
        if (trigramSimilarity >= TRIGRAM_THRESHOLD) return 4
        val allowedDistance = when {
            query.surface.length >= 8 -> 2
            query.surface.length >= 4 -> 1
            else -> 0
        }
        if (
            allowedDistance > 0 &&
            editDistance(query.surface, candidate.surface, allowedDistance) <= allowedDistance
        ) return 5
        return Int.MAX_VALUE
    }

    private fun commonPrefix(first: String, second: String): Int =
        first.zip(second).takeWhile { (left, right) -> left == right }.size

    private fun editDistance(first: String, second: String, limit: Int): Int {
        if (kotlin.math.abs(first.length - second.length) > limit) return limit + 1
        var previous = IntArray(second.length + 1) { it }
        for (leftIndex in first.indices) {
            val current = IntArray(second.length + 1)
            current[0] = leftIndex + 1
            for (rightIndex in second.indices) {
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (first[leftIndex] == second[rightIndex]) 0 else 1,
                )
            }
            previous = current
        }
        return previous[second.length]
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()

    private val STOP_WORDS = setOf(
        "как", "где", "что", "мне", "я", "хочу", "нужно", "надо", "можно", "пожалуйста",
        "команда", "команды", "для", "это", "свой", "свою", "свои", "свое", "мой", "моя",
        "мои", "моей", "мою", "мое", "у", "в", "на", "из", "к", "по",
    )
    private const val FIELD_WEIGHT = 3
    private const val TOKEN_SCORE = 20
    private const val MISSING_TOKEN_PENALTY = 10
    private const val FUZZY_WINDOW = 2
    private const val TRIGRAM_THRESHOLD = 0.50
}

/**
 * Small deterministic Russian lexicon for player help search. It deliberately
 * avoids network calls and heavyweight NLP dependencies: Unicode cleanup,
 * Russian suffix reduction and the configured intent vocabulary form the
 * semantic index.
 */
internal object HelpCenterLexicon {
    data class Term(val surface: String, val root: String)

    fun terms(value: String): List<Term> = normalize(value)
        .replace(NON_WORD, " ")
        .split(WHITESPACE)
        .filter(String::isNotBlank)
        .map { Term(it, root(it)) }

    fun root(value: String): String {
        val word = normalize(value).replace(NON_WORD, "")
        if (word.length <= 3 || word.any { it !in 'а'..'я' }) return word
        val suffix = SUFFIXES.firstOrNull { candidate ->
            word.endsWith(candidate) && word.length - candidate.length >= MIN_ROOT_LENGTH
        }
        return (suffix?.let { word.dropLast(it.length) } ?: word)
            .removeSuffix("ь")
            .removeSuffix("й")
            .let { if (it.endsWith("нн")) it.dropLast(1) else it }
    }

    fun trigramSimilarity(first: String, second: String): Double {
        val left = trigrams(normalize(first))
        val right = trigrams(normalize(second))
        if (left.isEmpty() || right.isEmpty()) return if (left == right) 1.0 else 0.0
        return (2.0 * left.intersect(right).size) / (left.size + right.size)
    }

    fun phoneticCyrillic(value: String): String {
        val normalized = normalize(value)
        if (normalized.any { it !in 'a'..'z' }) return normalized
        return buildString(normalized.length) {
            normalized.forEach { char -> append(LATIN_PHONETICS[char] ?: char.toString()) }
        }
    }

    private fun trigrams(value: String): Set<String> =
        if (value.length < 3) emptySet() else value.windowed(3).toSet()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}:_/-]+")
    private val WHITESPACE = Regex("\\s+")
    private const val MIN_ROOT_LENGTH = 3
    private val LATIN_PHONETICS = mapOf(
        'a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф",
        'g' to "г", 'h' to "х", 'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л",
        'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к", 'r' to "р",
        's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс",
        'y' to "й", 'z' to "з",
    )

    // Longest first. The list covers productive Russian noun, adjective and
    // verb endings used in natural help queries; configured keywords handle
    // irregular semantic families such as "земля" and "приват".
    private val SUFFIXES = listOf(
        "иями", "ями", "ами", "ивши", "ывши", "ившись", "ывшись", "ейше",
        "ого", "ему", "ыми", "ими", "его", "ому", "ее", "ие", "ые", "ое",
        "ей", "ий", "ый", "ой", "ем", "им", "ым", "ом", "их", "ых", "ую",
        "юю", "ая", "яя", "ою", "ею", "ила", "ыла", "ена", "ейте", "уйте",
        "ите", "или", "ыли", "ило", "ыло", "ено", "овать", "евать", "ировать",
        "иться", "аться", "яться", "иться", "ить", "ыть", "ать", "ять", "ешь",
        "ете", "ишь", "ите", "уют", "ует", "ены", "ено", "ить", "ишь", "ую",
        "ю", "ла", "на", "ли", "й", "л", "ем", "н", "ло", "но", "ет", "ны",
        "ть", "тья", "ья", "ия", "ьях", "иях", "ью", "ию", "ью", "ев", "ов",
        "ам", "ям", "ах", "ях", "ы", "и", "ь", "й", "у", "ю", "а", "я",
    ).distinct().sortedByDescending(String::length)
}

object HelpCenterCommands {
    private val homeName = Regex("[\\p{L}\\p{N}_-]{1,32}")
    private val executable = Regex("[a-z0-9:_-]+(?: [a-z0-9:_-]+)*")

    fun home(name: String): String = "home ${safeHome(name)}"

    fun createHome(name: String): String = "sethome ${safeHome(name)}"

    fun deleteHome(name: String): String = "delhome ${safeHome(name)}"

    fun relocateHome(name: String): String = "edithome ${safeHome(name)} relocate"

    fun teleportRequest(playerName: String): String = "tpa ${safePlayer(playerName)}"

    fun teleportHere(playerName: String): String = "tpahere ${safePlayer(playerName)}"

    fun duel(playerName: String): String = "duel ${safePlayer(playerName)}"

    fun message(playerName: String, rawMessage: String): String {
        require(rawMessage.none(Character::isISOControl)) { "Private message contains control characters" }
        val message = rawMessage.trim().replace(WHITESPACE, " ")
        require(message.isNotEmpty() && message.length <= 128) { "Private message must contain 1..128 characters" }
        return "msg ${safePlayer(playerName)} $message"
    }

    fun pay(playerName: String, rawAmount: String): String {
        val normalized = rawAmount.trim().replace(',', '.')
        require(AMOUNT.matches(normalized)) { "Payment amount must be a positive decimal with at most two digits" }
        val amount = BigDecimal(normalized).stripTrailingZeros()
        require(amount > BigDecimal.ZERO && amount <= MAX_PAYMENT) { "Payment amount is outside the allowed range" }
        return "pay ${safePlayer(playerName)} ${amount.toPlainString()}"
    }

    fun execute(command: String): String {
        require(executable.matches(command)) { "Unsafe help center command" }
        return command
    }

    private fun safeHome(name: String): String {
        require(homeName.matches(name)) { "Unsafe home name" }
        return name
    }

    private fun safePlayer(name: String): String = NetworkPlayerName.of(name).value

    private val WHITESPACE = Regex("\\s+")
    private val AMOUNT = Regex("[0-9]{1,12}(?:\\.[0-9]{1,2})?")
    private val MAX_PAYMENT = BigDecimal("999999999999.99")
}

/** Current incoming requests, never plugin availability or outgoing invitations. */
data class HelpCenterPendingRequests(val teleport: Boolean = false, val duel: Boolean = false)
