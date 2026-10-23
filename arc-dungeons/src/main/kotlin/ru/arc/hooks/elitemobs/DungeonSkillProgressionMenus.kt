package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

internal enum class DungeonProgressionAvailability { READY, LOADING, DISABLED }

internal data class DungeonProgressionView(
    val availability: DungeonProgressionAvailability,
    val skills: List<DungeonProgressionSkill> = emptyList(),
    val books: List<DungeonProgressionBook> = emptyList(),
)

internal data class DungeonProgressionBook(
    val id: String,
    val name: String,
    val earnedAt: Long,
    val gains: List<DungeonProgressionBookGain>,
)

internal data class DungeonProgressionBookGain(
    val skillId: String,
    val name: String,
    val xp: Long,
    val levelBefore: Int,
    val levelAfter: Int,
)

internal enum class DungeonProgressionBookResult { APPLIED, LOADING, MISSING, PENDING, FAILED }

/** A render snapshot of native skill progress, never a second XP account. */
internal data class DungeonProgressionSkill(
    val skill: DungeonSkillInfo,
    val maxLevel: Int?,
    val gainDescription: String,
    val passive: DungeonProgressionPassive? = null,
    val nextUnlock: DungeonProgressionUnlock? = null,
)

/** Percent values are percentage points: 3.5 means +3.5%, not 350%. */
internal data class DungeonProgressionPassive(
    val label: String,
    val percent: Double,
    val nextLevel: Int? = null,
    val nextPercent: Double? = null,
)

internal data class DungeonProgressionUnlock(
    val name: String,
    val requiredLevel: Int,
    val description: String,
)

/** Native navigation and presentation; the supplied owner reads and changes native XP. */
internal class DungeonSkillProgressionMenus(
    private val dungeon: EMDungeonQol,
    private val read: (Player) -> DungeonProgressionView,
    private val preview: (Player, String) -> DungeonProgressionBook?,
    private val consume: (Player, String, (DungeonProgressionBookResult) -> Unit) -> Unit,
    private val openPerks: (Player, String, Int) -> Unit,
    private val returnTo: (Player) -> Unit,
    private val show: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit,
    private val showConfirmation: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit) -> Unit = { player, screen, reopen, dismiss ->
        val close = PaperDialogButton(PaperDialogActionId.of("close"),
            dungeon.text("progression.close", "<white>Закрыть"), width = 200, closeDialogBeforeAction = true) { }
        DungeonsMenus.openDialog(player, screen, close, reopen, dismiss)
    },
) {
    private data class PendingBook(val bookId: String)
    private val pending = mutableMapOf<UUID, PendingBook>()

    fun open(player: Player, requestedPage: Int = 0, feedback: Component? = null) {
        val view = read(player)
        val pages = ((view.skills.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val skills = view.skills.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val body = mutableListOf<PaperDialogBody>()
        feedback?.let { body += body(it) }
        when (view.availability) {
            DungeonProgressionAvailability.LOADING -> body += body(text("loading", "<#e8dfd2>Данные навыков загружаются. Обновите страницу через несколько секунд."))
            DungeonProgressionAvailability.DISABLED -> body += body(text("disabled", "<#e8dfd2>Боевые навыки на этом сервере недоступны."))
            DungeonProgressionAvailability.READY -> {
                if (skills.isEmpty()) body += body(text("empty", "<#e8dfd2>Список навыков пока недоступен. Попробуйте обновить страницу."))
                else body += table(skills.map { entry ->
                    text("overview-name", "<white><name><newline><#c4abff>Уровень <level>",
                        "name" to skillName(entry), "level" to literal(entry.skill.level).color(skillColor(entry.skill.id))) to
                        xp(entry).append(Component.newline()).append(nextGoal(entry))
                }, DialogTables.Frame.EPIC, separators = true, width = 400, columns = DialogTables.Columns.VALUE_WIDE)
            }
        }
        val buttons = if (view.availability == DungeonProgressionAvailability.DISABLED) emptyList() else buildList {
            if (view.availability == DungeonProgressionAvailability.READY) skills.forEach { entry ->
                add(PaperDialogButton(
                    PaperDialogActionId.of("progression_${entry.skill.id.lowercase(Locale.ROOT)}"),
                    text("skill-label", "<#c4abff><name> · ур. <level> ›", "name" to skillName(entry), "level" to literal(entry.skill.level).color(skillColor(entry.skill.id))),
                    skillTooltip(entry), width = OVERVIEW_BUTTON_WIDTH,
                    onClick = { detail(player, entry.skill.id, page) },
                ))
            }
            if (pages > 1 && view.availability == DungeonProgressionAvailability.READY && skills.size % OVERVIEW_COLUMNS == OVERVIEW_COLUMNS - 1) {
                add(refresh { open(player, page) }.copy(width = OVERVIEW_BUTTON_WIDTH))
            }
            if (view.availability == DungeonProgressionAvailability.READY) add(action("books", "library", "<#c4a7e7>Книги прохождений · <count> ›",
                "Личная библиотека: опыт за завершённые инстансы") { books(player, overviewPage = page) }.copy(
                    label = text("library", "<#c4a7e7>Книги прохождений · <count> ›", "count" to literal(view.books.size)), width = OVERVIEW_BUTTON_WIDTH))
            if (pages > 1 && view.availability == DungeonProgressionAvailability.READY) {
                add(action("previous", "previous", "<#92bed8>‹ Предыдущая страница", "Предыдущие навыки") {
                    open(player, (page - 1 + pages) % pages)
                }.copy(width = OVERVIEW_BUTTON_WIDTH))
                add(action("next", "next", "<#92bed8>Следующая страница ›", "Следующие навыки") {
                    open(player, (page + 1) % pages)
                }.copy(width = OVERVIEW_BUTTON_WIDTH))
            } else add(refresh { open(player, page) }.copy(width = OVERVIEW_BUTTON_WIDTH))
        }
        show(player, PaperDialogScreen(
            id = "dungeon.progression", title = if (pages > 1) text("title-page", "<#c4abff>Развитие навыков · <page> / <pages>",
                "page" to literal(page + 1), "pages" to literal(pages)) else text("title", "<#c4abff>Развитие навыков"),
            body = body, buttons = buttons, exitButton = back { returnTo(player) }, columns = OVERVIEW_COLUMNS,
        )) { open(player, page) }
    }

    fun detail(player: Player, skillId: String, overviewPage: Int = 0, feedback: Component? = null) {
        val view = read(player)
        val entry = view.skills.firstOrNull { it.skill.id == skillId }
        if (view.availability != DungeonProgressionAvailability.READY || entry == null) {
            open(player, overviewPage, feedback ?: text("changed", "<#e8dfd2>Данные навыка изменились. Обзор обновлён."))
            return
        }
        val capped = capped(entry)
        val rows = buildList {
            add(label("level", "Уровень") to level(entry))
            add(label("xp", "Опыт уровня") to xp(entry))
            entry.passive?.let { passive ->
                add(literal(passive.label) to text("passive-value", "<#c4abff>+<percent>% <#e8dfd2>только в данжах", "percent" to percent(passive.percent)))
                if (passive.nextLevel != null && passive.nextPercent != null) {
                    add(label("next-passive", "Следующее усиление") to text("next-passive-value", "<#e8dfd2>Ур. <level> · <#c4abff>+<percent>%",
                        "level" to literal(passive.nextLevel), "percent" to percent(passive.nextPercent)))
                }
            }
            entry.nextUnlock?.let { unlock ->
                add(label("next-unlock", "Откроется способность") to text("next-unlock-value", "<#e8dfd2><name><newline><#c4abff>На уровне <level>",
                    "name" to literal(unlock.name), "level" to literal(unlock.requiredLevel)))
            }
        }
        val body = mutableListOf<PaperDialogBody>()
        feedback?.let { body += body(it) }
        body += body(literal(entry.gainDescription))
        body += DialogTables.filledBody(rows.map { (name, value) -> listOf(name, value) },
            null, 400, listOf(2, 3))
        if (entry.nextUnlock != null) body += body(text("unlock-explanation", "<#e8dfd2><name>: <description>",
            "name" to literal(entry.nextUnlock.name), "description" to literal(entry.nextUnlock.description)))
        body += body(when {
            capped -> text("capped", "<#e8dfd2>Максимальный уровень достигнут. Книги опыта для этого навыка не расходуются.")
            view.books.none { book -> book.gains.any { it.skillId == skillId } } -> text("no-books", "<#e8dfd2>Для этого навыка пока нет книг прохождений. Книги сохраняют опыт навыков, которые развивались в инстансе.")
            else -> text("books-available", "<#e8dfd2>Книг для этого навыка: <count>. В каждой — опыт всех освоенных в походе веток.",
                "count" to literal(view.books.count { book -> book.gains.any { it.skillId == skillId } }))
        })
        val buttons = buildList {
            if (!capped && view.books.any { book -> book.gains.any { it.skillId == skillId } }) add(action("books", "books", "<#c4a7e7>Применить книгу ›", "Посмотреть книги прохождений, содержащие опыт этого навыка") {
                books(player, skillId, overviewPage)
            })
            add(action("perks", "perks", "<#c4abff>Способности и перки ›", "Посмотреть, выбрать или выключить способности этого навыка") {
                openPerks(player, skillId, overviewPage)
            })
        }
        show(player, PaperDialogScreen(
            id = "dungeon.progression.${skillId.lowercase(Locale.ROOT)}",
            title = text("detail-title", "<#c4abff><name> · развитие", "name" to skillName(entry)),
            body = body, buttons = buttons, exitButton = back { open(player, overviewPage) }, columns = 2,
        )) { detail(player, skillId, overviewPage) }
    }

    fun books(player: Player, skillId: String? = null, overviewPage: Int = 0, requestedPage: Int = 0, feedback: Component? = null) {
        val view = read(player)
        if (view.availability != DungeonProgressionAvailability.READY) {
            open(player, overviewPage, feedback)
            return
        }
        val skill = view.skills.firstOrNull { it.skill.id == skillId }
        val available = view.books.filter { book -> skillId == null || book.gains.any { it.skillId == skillId } }
        val pages = ((available.size + BOOKS_PER_PAGE - 1) / BOOKS_PER_PAGE).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pages - 1)
        val listed = available.drop(page * BOOKS_PER_PAGE).take(BOOKS_PER_PAGE)
        val body = mutableListOf<PaperDialogBody>()
        feedback?.let { body += body(it) }
        body += body(text("library-intro", "<#e8dfd2>Конспект даёт 10% опыта, заработанного каждым навыком за завершённый инстанс. Книги хранятся здесь, вне инвентаря."))
        if (listed.isEmpty()) body += body(text("library-empty", "<#e8dfd2>Книг пока нет. Завершите инстанс с боевым опытом, чтобы получить конспект с 10% заработанного опыта."))
        else body += table(listed.map { book ->
            literal(book.name) to text("book-summary", "<#c4abff>+<xp> опыта<newline><#e8dfd2>Навыков: <count> · <date>",
                "xp" to number(book.gains.sumOf { it.xp }), "count" to literal(book.gains.size), "date" to literal(DATE.format(Instant.ofEpochMilli(book.earnedAt))))
        }, DialogTables.Frame.EPIC, separators = true)
        if (pages > 1) body += body(text("library-page", "<#e8dfd2>Страница <page> из <pages>", "page" to literal(page + 1), "pages" to literal(pages)))
        val buttons = buildList {
            listed.forEachIndexed { index, book ->
                add(PaperDialogButton(PaperDialogActionId.of("book_$index"),
                    text("book-label", "<#c4a7e7><name> ›", "name" to literal(book.name)),
                    text("book-tooltip", "<newline><#e8dfd2>Опыт за завершённый поход.<newline><newline><gains><newline><newline><#c4abff>Проверить начисление перед применением<newline>",
                        "gains" to gainsSummary(book)), width = BUTTON_WIDTH,
                    onClick = { confirm(player, book.id, skillId, overviewPage, page) }))
            }
            if (skillId != null) add(action("all_books", "all-books", "<#e5ba73>Все книги прохождений ›", "Показать книги для всех навыков") { books(player, overviewPage = overviewPage) })
            if (pages > 1) {
                if (size % 2 == 1) add(refresh { books(player, skillId, overviewPage, page) })
                add(action("previous", "previous", "<#92bed8>‹ Предыдущая страница", "Предыдущие книги") { books(player, skillId, overviewPage, (page - 1 + pages) % pages) })
                add(action("next", "next", "<#92bed8>Следующая страница ›", "Следующие книги") { books(player, skillId, overviewPage, (page + 1) % pages) })
            } else add(refresh { books(player, skillId, overviewPage, page) })
        }
        show(player, PaperDialogScreen(
            id = "dungeon.progression.books${skillId?.let { ".${it.lowercase(Locale.ROOT)}" }.orEmpty()}",
            title = if (skill != null) text("library-filtered-title", "<#c4abff>Книги прохождений · <name>", "name" to literal(skill.skill.name))
                else text("library-title", "<#c4abff>Книги прохождений"), body = body, buttons = buttons,
            exitButton = back { if (skillId == null) open(player, overviewPage) else detail(player, skillId, overviewPage) }, columns = 2,
        )) { books(player, skillId, overviewPage, page) }
    }

    private fun confirm(player: Player, bookId: String, skillId: String?, overviewPage: Int, bookPage: Int, feedback: Component? = null, requestedGainPage: Int = 0) {
        val book = preview(player, bookId) ?: run {
            unavailableBook(player, skillId, overviewPage, bookPage)
            return
        }
        val attempt = pending[player.uniqueId]?.takeIf { it.bookId == bookId }
        val pages = ((book.gains.size + GAINS_PER_PAGE - 1) / GAINS_PER_PAGE).coerceAtLeast(1)
        val page = requestedGainPage.coerceIn(0, pages - 1)
        val listed = book.gains.drop(page * GAINS_PER_PAGE).take(GAINS_PER_PAGE)
        val body = mutableListOf<PaperDialogBody>()
        feedback?.let { body += body(it) }
        body += body(text("confirm-intro", "<#e8dfd2><name><newline>Вся книга будет применена один раз. Опыт получат все перечисленные навыки.", "name" to literal(book.name)))
        body += table(listed.map { gain ->
            literal(gain.name) to text("book-gain", "<#c4abff>+<xp> опыта<newline><#e8dfd2>Уровень <before> → <after>",
                "xp" to number(gain.xp), "before" to literal(gain.levelBefore), "after" to literal(gain.levelAfter))
        }, DialogTables.Frame.LEGENDARY, separators = true)
        if (pages > 1) body += body(text("book-gain-page", "<#e8dfd2>Начисления: <from>–<to> из <total> навыков. Применится вся книга.",
            "from" to literal(page * GAINS_PER_PAGE + 1), "to" to literal(page * GAINS_PER_PAGE + listed.size), "total" to literal(book.gains.size)))
        if (attempt != null) body += body(text("applying", "<#e8dfd2>Применяем книгу. Дождитесь подтверждения начисления."))
        val apply = action("apply_book", "apply-book", "<#9bd48d>Применить конспект", "Начислить опыт по всем навыкам книги и израсходовать конспект") {
            if (pending.containsKey(player.uniqueId)) return@action
            val current = preview(player, bookId)
            if (current == null) unavailableBook(player, skillId, overviewPage, bookPage)
            else if (current != book) confirm(player, bookId, skillId, overviewPage, bookPage,
                text("preview-changed", "<#e8dfd2>Прогресс навыков изменился. Проверьте обновлённое начисление и подтвердите ещё раз."), page)
            else {
                val operation = PendingBook(bookId)
                pending[player.uniqueId] = operation
                confirm(player, bookId, skillId, overviewPage, bookPage, requestedGainPage = page)
                consume(player, bookId) { result ->
                    if (pending[player.uniqueId] !== operation) return@consume
                    pending.remove(player.uniqueId)
                    if (!player.isOnline) return@consume
                    val message = if (result == DungeonProgressionBookResult.APPLIED) text("applied", "<#9bd48d>Конспект применён: +<xp> опыта. Прогресс навыков обновлён.",
                        "xp" to number(book.gains.sumOf { it.xp })) else resultText(result)
                    if (result == DungeonProgressionBookResult.APPLIED && skillId != null) detail(player, skillId, overviewPage, message)
                    else books(player, skillId, overviewPage, bookPage, message)
                }
            }
        }
        val buttons = if (attempt != null) emptyList() else buildList {
            if (pages > 1) {
                add(action("previous", "previous", "<#92bed8>‹ Предыдущая страница", "Предыдущие начисления") {
                    confirm(player, bookId, skillId, overviewPage, bookPage, requestedGainPage = (page - 1 + pages) % pages)
                })
                add(action("next", "next", "<#92bed8>Следующая страница ›", "Следующие начисления") {
                    confirm(player, bookId, skillId, overviewPage, bookPage, requestedGainPage = (page + 1) % pages)
                })
            }
            add(apply)
        }
        showConfirmation(player, PaperDialogScreen(
            id = "dungeon.progression.book.confirm", title = text("confirm-title", "<#c4abff>Применение конспекта"),
            body = body, buttons = buttons, exitButton = back { books(player, skillId, overviewPage, bookPage) }, columns = 1,
        ), { confirm(player, bookId, skillId, overviewPage, bookPage, requestedGainPage = page) }, {
            if (attempt != null && pending[player.uniqueId] === attempt) pending.remove(player.uniqueId)
        })
    }

    private fun unavailableBook(player: Player, skillId: String?, overviewPage: Int, bookPage: Int) {
        if (read(player).availability == DungeonProgressionAvailability.READY)
            books(player, skillId, overviewPage, bookPage, resultText(DungeonProgressionBookResult.MISSING))
        else open(player, overviewPage)
    }

    private fun resultText(result: DungeonProgressionBookResult) = when (result) {
        DungeonProgressionBookResult.APPLIED -> text("applied-short", "<#9bd48d>Конспект применён. Прогресс навыков обновлён.")
        DungeonProgressionBookResult.LOADING -> text("book-loading", "<#e8dfd2>Данные героя ещё загружаются. Обновите библиотеку через несколько секунд.")
        DungeonProgressionBookResult.MISSING -> text("book-missing", "<#e8dfd2>Этой книги больше нет в библиотеке. Список обновлён.")
        DungeonProgressionBookResult.PENDING -> text("book-pending", "<#e8dfd2>Книга уже применяется. Дождитесь завершения операции.")
        DungeonProgressionBookResult.FAILED -> text("book-failed", "<#e8dfd2>Сохранение ещё не подтверждено. Повторно книга не списывается; обновите окно позже.")
    }

    private fun gainsSummary(book: DungeonProgressionBook) = book.gains.map { gain ->
        text("book-gain-summary", "<#e8dfd2><name>: <#c4abff>+<xp> опыта", "name" to literal(gain.name), "xp" to number(gain.xp))
    }.reduceOrNull { result, line -> result.append(Component.newline()).append(line) } ?: Component.empty()

    private fun skillTooltip(entry: DungeonProgressionSkill): Component {
        val blocks = buildList {
            add(literal(entry.gainDescription))
            add(text("tooltip-progress", "<#e8dfd2>Уровень <level><newline><xp>", "level" to level(entry), "xp" to xp(entry)))
            entry.passive?.let { add(text("tooltip-passive", "<#e8dfd2>Только в данжах: <name> <#c4abff>+<percent>%",
                "name" to literal(it.label), "percent" to percent(it.percent))) }
            add(nextGoal(entry))
            add(text("tooltip-open", "<#c4abff>Открыть прогресс, способности и книги прохождений"))
        }
        return Component.newline().append(blocks.reduce { result, block ->
            result.append(Component.newline()).append(Component.newline()).append(block)
        }).append(Component.newline()).colorIfAbsent(BODY_COLOR).decoration(TextDecoration.ITALIC, false)
    }

    private fun nextGoal(entry: DungeonProgressionSkill): Component {
        val unlock = entry.nextUnlock
        val passive = entry.passive
        return when {
            capped(entry) -> text("goal-complete", "<#9bd48d>Максимальный уровень")
            passive?.nextLevel != null && passive.nextPercent != null && (unlock == null || passive.nextLevel <= unlock.requiredLevel) ->
                text("goal-passive", "<#e8dfd2>Ур. <level> · <name> +<percent>%", "level" to literal(passive.nextLevel),
                    "name" to literal(passive.label), "percent" to percent(passive.nextPercent))
            unlock != null -> text("goal-unlock", "<#e8dfd2>Ур. <level> · <name>",
                "level" to literal(unlock.requiredLevel), "name" to literal(unlock.name))
            else -> text("goal-none", "<#e8dfd2>Все ступени открыты")
        }
    }

    private fun capped(entry: DungeonProgressionSkill) = entry.maxLevel?.let { entry.skill.level >= it } == true
    private fun level(entry: DungeonProgressionSkill) = if (entry.maxLevel == null)
        text("level-open", "<#c4abff><level>", "level" to literal(entry.skill.level))
    else text("level-value", "<#c4abff><level> / <max>", "level" to literal(entry.skill.level), "max" to literal(entry.maxLevel))

    private fun xp(entry: DungeonProgressionSkill) = if (capped(entry))
        text("xp-complete", "<#9bd48d>Навык полностью развит")
    else text("xp-progress", "<#e8dfd2><current> / <required> опыта",
        "current" to number(entry.skill.xp), "required" to number(entry.skill.nextXp))

    private fun table(rows: List<Pair<Component, Component>>, frame: DialogTables.Frame, separators: Boolean,
        width: Int = 320, columns: DialogTables.Columns = DialogTables.Columns.BALANCED) =
        DialogTables.body(rows.map { (name, value) -> name.colorIfAbsent(BODY_COLOR) to value.colorIfAbsent(BODY_COLOR) },
            headers = null, frame = frame, width = width, columns = columns,
            spec = DialogTables.Spec(rowSeparators = separators))

    private fun body(component: Component) = PaperDialogBody(component.colorIfAbsent(BODY_COLOR).decoration(TextDecoration.ITALIC, false), 468)
    private fun label(key: String, fallback: String) = text("fields.$key", "<#e8dfd2>$fallback")
    private fun skillName(entry: DungeonProgressionSkill) = literal(entry.skill.name).color(skillColor(entry.skill.id))
    private fun skillColor(skillId: String) = TextColor.color(when (skillId) {
        "SWORDS" -> 0xffb277
        "AXES" -> 0xff8f80
        "ARMOR" -> 0x86dcf1
        "BOWS" -> 0x9bd48d
        "CROSSBOWS" -> 0xc8dc88
        "TRIDENTS" -> 0x85dfc4
        "HOES" -> 0xb7a0eb
        "MACES" -> 0xe5ba73
        "SPEARS" -> 0x92bed8
        "STAVES" -> 0xf3a2c9
        "WANDS" -> 0xd8adff
        else -> 0xc4abff
    })
    private fun literal(value: Any) = Component.text(plainDungeonQuestText(value.toString())).decoration(TextDecoration.ITALIC, false)
    private fun number(value: Long) = literal(String.format(Locale.ROOT, "%,d", value).replace(',', ' '))
    private fun percent(value: Double) = literal(BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ','))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>) =
        dungeon.text("progression.$key", fallback, *values).colorIfAbsent(BODY_COLOR).decoration(TextDecoration.ITALIC, false)
    private fun back(action: () -> Unit) = PaperDialogButton(PaperDialogActionId.of("back"), text("back", "<white>‹ Назад"), width = 200, onClick = { action() })
    private fun refresh(onRefresh: () -> Unit) = action("refresh", "refresh", "<white>Обновить", "Получить текущие уровни и книги прохождений", onRefresh)
    private fun action(id: String, key: String, fallback: String, hint: String, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), text(key, fallback), text("$key-tooltip", "<#e8dfd2>$hint"), width = BUTTON_WIDTH, onClick = { action() },
    )

    companion object {
        private const val PAGE_SIZE = 6
        private const val OVERVIEW_COLUMNS = 3
        private const val OVERVIEW_BUTTON_WIDTH = 150
        private const val BOOKS_PER_PAGE = 3
        private const val GAINS_PER_PAGE = 4
        private const val BUTTON_WIDTH = 230
        private val BODY_COLOR = TextColor.color(0xe8dfd2)
        private val DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())
    }
}
