package ru.arc.helpcenter

import java.text.Normalizer
import java.util.Locale

enum class HelpCenterHomeAction { TELEPORT, RELOCATE, DELETE }

enum class HelpCenterPlayerAction { TELEPORT_TO, TELEPORT_HERE, MESSAGE, PAY, DUEL, INVITE }

sealed interface HelpCenterResolvedQuery {
    data class Home(val home: HelpCenterHome, val action: HelpCenterHomeAction) : HelpCenterResolvedQuery

    data class Player(
        val player: HelpCenterPlayer,
        val action: HelpCenterPlayerAction,
        val value: String? = null,
    ) : HelpCenterResolvedQuery

    data class Page(val page: HelpCenterPage, val catalogId: String? = null) : HelpCenterResolvedQuery
}

/** Closed, deterministic parameter resolver that never promotes arbitrary query text into a command. */
object HelpCenterSmartQuery {
    fun resolve(
        rawQuery: String,
        homes: Collection<HelpCenterHome>,
        players: Collection<HelpCenterPlayer>,
    ): HelpCenterResolvedQuery? {
        val query = normalize(rawQuery)
        if (query.isBlank()) return null
        directPage(query)?.let { return it }

        val homeAction = when {
            query.hasAny("удалить", "стереть", "delhome") -> HelpCenterHomeAction.DELETE
            query.hasAny("перенести", "передвинуть", "перезаписать", "relocate", "edithome") -> HelpCenterHomeAction.RELOCATE
            query.hasAny("дом", "home", "телепорт") -> HelpCenterHomeAction.TELEPORT
            else -> null
        }
        if (homeAction != null && query.hasAny("дом", "home", "delhome", "edithome")) {
            exactEntity(query, homes) { it.name }?.let { return HelpCenterResolvedQuery.Home(it, homeAction) }
        }

        val playerAction = when {
            query.hasAny("перевести", "заплатить", "оплатить", "pay") -> HelpCenterPlayerAction.PAY
            query.hasAny("позвать", "пригласить", "invite") && query.hasAny("приват", "земл", "поселен", "land") ->
                HelpCenterPlayerAction.INVITE
            query.hasAny("позвать к себе", "сюда", "tpahere") -> HelpCenterPlayerAction.TELEPORT_HERE
            query.hasAny("телепорт", "переместиться", "tpa") -> HelpCenterPlayerAction.TELEPORT_TO
            query.hasAny("написать", "сообщение", "msg") -> HelpCenterPlayerAction.MESSAGE
            query.hasAny("дуэль", "дуел", "сразиться", "duel") -> HelpCenterPlayerAction.DUEL
            else -> null
        }
        if (playerAction != null) {
            val target = exactEntity(query, players) { it.name } ?: return null
            val value = if (playerAction == HelpCenterPlayerAction.PAY) payment(query) ?: return null else null
            return HelpCenterResolvedQuery.Player(target, playerAction, value)
        }
        return null
    }

    private fun directPage(query: String): HelpCenterResolvedQuery.Page? = when {
        query.hasAny("данжи", "данж", "подземель") -> HelpCenterResolvedQuery.Page(HelpCenterPage.ACTIVITIES, "dungeons")
        query.hasAny("избранное", "любимые команды") -> HelpCenterResolvedQuery.Page(HelpCenterPage.FAVORITES)
        query.hasAny("что делать", "чем заняться", "занятие") -> HelpCenterResolvedQuery.Page(HelpCenterPage.GOALS)
        query.hasAny("предмет в руке", "этот предмет", "рецепт предмета") -> HelpCenterResolvedQuery.Page(HelpCenterPage.ITEM)
        else -> null
    }

    private fun payment(query: String): String? = AMOUNT.findAll(query)
        .map(MatchResult::value)
        .firstOrNull { raw -> runCatching { HelpCenterCommands.pay("Player", raw) }.isSuccess }

    private fun <T> exactEntity(query: String, values: Collection<T>, name: (T) -> String): T? {
        val words = query.split(NON_WORD).filter(String::isNotBlank).toSet()
        val matches = values.filter { normalize(name(it)) in words }
        return matches.singleOrNull()
    }

    private fun String.hasAny(vararg needles: String): Boolean = needles.any(::contains)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .trim()

    private val NON_WORD = Regex("[^\\p{L}\\p{N}_-]+")
    private val AMOUNT = Regex("(?<![\\p{L}\\p{N}_-])[0-9]{1,12}(?:[.,][0-9]{1,3})?(?![\\p{L}\\p{N}_-])")
}
