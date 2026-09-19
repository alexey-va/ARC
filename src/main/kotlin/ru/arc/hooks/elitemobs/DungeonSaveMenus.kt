package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.util.TextUtil
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class DungeonSaveMenus(
    private val dungeon: EMDungeonQol,
    private val crystals: (Player) -> String? = ::readDungeonCrystals,
    private val readQuests: (Player) -> List<DungeonQuestInfo>? = ::readDungeonQuests,
    private val changeTracking: (Player, UUID, Boolean) -> DungeonQuestTrackingChange = ::changeDungeonQuestTracking,
    private val abandonQuest: (Player, UUID) -> DungeonQuestAbandonChange = ::abandonDungeonQuest,
    private val classService: DungeonClassService = NativeDungeonClassService,
    private val classGrants: DungeonClassGrantService = NativeDungeonClassGrantService,
    private val canGrantClasses: (Player) -> Boolean = { it.hasPermission("arc.dungeon.admin.classgrant") },
    private val show: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit = { player, screen, reopen ->
        val close = PaperDialogButton(PaperDialogActionId.of("close"), dungeon.text("saves.dialog.close-label", "<#e8dfd2>Закрыть"), width = 200, closeDialogBeforeAction = true) { }
        // A root Close is already the footer, so never move a second Close into the grid.
        val prepared = if (screen.exitButton?.id?.value == "close" && !MenuEscapeBehavior.goesBack(player)) screen.copy(exitButton = null) else screen
        ArcMenus.openDialog(player, prepared, close, reopen = reopen)
    },
) {
    private val questsPerPage = 6
    private val nameInput = PaperDialogInputId.of("name")
    private val partyPlayerInput = PaperDialogInputId.of("party_player")
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

    internal fun panel(player: Player, feedback: Component? = null) {
        val view = dungeon.panelView(player) ?: run { unavailable(player); return }
        val classView = classService.view(player)
        val visit = view.visit
        val blocked = dungeon.saveBlockReason(player, view.saves)
        val state = when {
            visit.waiting -> text("panel.waiting", "<#d7b486>Сбор группы · готовьтесь к старту")
            visit.canResume -> text("panel.ongoing", "<#9bd48d>Прохождение идёт")
            else -> text("panel.finished", "<#e8dfd2>Прохождение остановлено · можно выйти")
        }
        val body = mutableListOf(
            PaperDialogBody(text("panel.intro", "<#ffb277><name>", "name" to dungeonDisplayName(visit)), 468),
        )
        if (visit.instanced) body += PaperDialogBody(state, 468)
        val stats = buildList {
            visit.stats?.level?.takeIf { it > 0 }?.let { add(tableLabel("level", "Уровень") to Component.text(it)) }
            visit.stats?.difficulty?.let { add(tableLabel("difficulty", "Сложность") to dungeonDifficulty(dungeon, it)) }
            visit.stats?.playerCount?.let { add(tableLabel("party", "Участники") to Component.text(it)) }
            if (classView.availability != DungeonClassAvailability.DISABLED) {
                add(tableLabel("class", "Класс") to classSummary(classView))
            }
            add(tableLabel("crystals", "Ваши кристаллы") to (crystals(player)?.let {
                text("table.crystals-value", "<white>💎</white> <#c7a0e8><value>", "value" to Component.text(it))
            } ?: text("table.unavailable-value", "Сейчас недоступно")))
            view.saves?.let { addAll(saveRows(it)) }
        }
        if (stats.isNotEmpty()) body += table(stats, DialogTables.Frame.ARTIFACT)
        body += PaperDialogBody(availability(blocked), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.panel", title = text("panel.title", "<#ffb277>Панель данжа"), body = body,
            buttons = listOfNotNull(
                if (visit.waiting) action("start", "panel.start-label", "<#9bd48d>Начать поход", "panel.start-tooltip", "Запустить прохождение для собранной группы", close = true) {
                    dungeon.panelAction(player, view, "start")
                } else null,
                action("quests", "quests.label", "<#c4a7e7>Задания ›", "quests.tooltip", "Принятые задания EliteMobs и их текущий прогресс") { quests(player) },
                classButton(player),
                partyButton(player),
                action("shop", "panel.shop-label", "<#f4d87a>Припасы ›", "panel.shop-tooltip", "Припасы, кейсы и бусты опыта за кристаллы") { shop(player) },
                lostLoot(player),
                action("saves", "panel.saves-label", "<#c4a7e7>Сохранения ›", "panel.saves-tooltip", "Ваши ручные точки, автосохранения и место прошлого выхода") { open(player) },
                action("entry", "panel.entry-label", "<#92bed8>К началу данжа ›", "panel.entry-tooltip", "Обычный портал к началу данжа", close = view.saves?.entry != null) {
                    if (view.saves?.entry != null) dungeon.travel(player, view.saves, "entry") else panel(player, text("panel.entry-unavailable", "<#e8dfd2>Безопасный переход ко входу сейчас недоступен. Для выхода используйте кнопку внизу."))
                }.let { if (view.saves?.entry != null) it else it.copy(label = text("panel.entry-disabled", "<#e8dfd2>[Недоступно] К началу данжа")) },
                action("guide", "panel.guide-label", "<#86dcf1>Гайд ›", "panel.guide-tooltip", "Читальная справка о данжах") {
                    if (!HelpCenterModule.openDungeonsGuide(player) { panel(player) }) {
                        panel(player, text("panel.guide-unavailable", "<#d7b486>Гайд сейчас недоступен. Закройте панель и попробуйте позже."))
                    }
                },
                action("about", "panel.about-label", "<#86dcf1>О данже ›", "panel.about-tooltip", "Описание и подсказка этого данжа") { about(player) },
                scoreboardButton(player),
                action("quit", "panel.quit-label", "<#d7b486>Выйти из данжа", "panel.quit-tooltip", "Покинуть данж штатным способом", close = true) { dungeon.panelAction(player, view, "quit") }.let { if (visit.instanced) it else it.copy(tooltip = text("panel.quit-open-tooltip", "Вызвать выход ко спавну. Если появился портал, войдите в него.")) },
            ),
            exitButton = if (MenuEscapeBehavior.goesBack(player)) back {} else close(), columns = 2,
        )) { panel(player) }
    }

    internal fun quests(player: Player, requestedPage: Int = 0, feedback: Component? = null) {
        val entries = readQuests(player)
        val pageCount = ((entries.orEmpty().size + questsPerPage - 1) / questsPerPage).coerceAtLeast(1)
        val page = requestedPage.coerceIn(0, pageCount - 1)
        val listed = entries.orEmpty().drop(page * questsPerPage).take(questsPerPage)
        val body = mutableListOf(PaperDialogBody(text("quests.intro", "<#f2eee8>✔ — отслеживается, ○ — не отслеживается. Готовность к сдаче указана отдельно. Выберите задание для управления."), 468))
        if (listed.isEmpty()) {
            body += PaperDialogBody(if (entries == null) text("quests.unavailable", "<#d7b486>Данные заданий ещё загружаются. Попробуйте обновить страницу.")
                else text("quests.empty", "<#f2eee8>Принятых заданий пока нет. Поговорите с персонажами, которые предлагают задания."), 468)
        } else {
            val summary = Component.empty().children(listed.flatMapIndexed { index, quest ->
                val row = questName(quest)
                    .append(Component.newline()).append(questStatus(quest))
                if (index == 0) listOf(row) else listOf(Component.newline(), row)
            })
            body += PaperDialogBody(summary, 468)
            if (pageCount > 1) body += PaperDialogBody(text("quests.page", "<#f2eee8>Страница <current> из <total>",
                "current" to Component.text(page + 1), "total" to Component.text(pageCount)), 468)
        }
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        val questButtons = listed.mapIndexed { index, quest ->
            val label = questName(quest, navigation = true)
            pointButton("quest_$index", label, text("quests.open-tooltip", "Открыть прогресс и управление заданием")) { quest(player, quest.id) }
        }
        show(player, PaperDialogScreen(
            id = "dungeon.quests", title = text("quests.title", "<#c4a7e7>Мои задания"), body = body,
            buttons = questButtons + listOfNotNull(
                if (page > 0) action("previous", "quests.previous", "<#92bed8>‹ Предыдущая страница", "quests.previous-tooltip", "Показать предыдущее задание") { quests(player, page - 1) } else null,
                if (page + 1 < pageCount) action("next", "quests.next", "<#92bed8>Следующая страница ›", "quests.next-tooltip", "Показать следующие задания") { quests(player, page + 1) } else null,
                action("refresh", "quests.refresh", "<#92bed8>Обновить", "quests.refresh-tooltip", "Прочитать текущий прогресс из EliteMobs") { quests(player, page) },
            ), exitButton = back { panel(player) }, columns = 2,
        )) { quests(player, page) }
    }

    private fun quest(player: Player, questId: UUID, feedback: Component? = null) {
        val entry = readQuests(player)?.firstOrNull { it.id == questId } ?: run {
            quests(player, feedback = text("quests.changed", "<#d7b486>Задание изменилось или было завершено. Список обновлён."))
            return
        }
        val body = mutableListOf(
            PaperDialogBody(plain(readableQuestText(TextUtil.legacy(entry.name))), 468),
            PaperDialogBody(questStatus(entry), 468),
        )
        if (entry.complete) body += PaperDialogBody(text("quests.complete", "<#9bd48d>Цели выполнены — задание готово к сдаче."), 468)
        if (entry.lines.isEmpty()) body += PaperDialogBody(text("quests.no-progress", "<#f2eee8>Текущих целей нет."), 468)
        else {
            val checklist = Component.empty().children(entry.lines.flatMapIndexed { index, goal ->
                val row = dungeonQuestChecklistLine(goal, entry.lineStates.getOrElse(index) { DungeonQuestGoalState.ACTIVE })
                if (index == 0) listOf(row) else listOf(Component.newline(), row)
            })
            body += PaperDialogBody(checklist, 320)
        }
        if (!entry.trackable) body += PaperDialogBody(text("quests.not-trackable", "<#d7b486>Это задание нельзя выбрать для отслеживания."), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        val buttons = buildList {
            if (entry.trackable) add(action(
                "track",
                if (entry.tracked) "quests.untrack" else "quests.track",
                if (entry.tracked) "<#f2eee8>Перестать отслеживать" else "<#9bd48d>Отслеживать",
                if (entry.tracked) "quests.untrack-tooltip" else "quests.track-tooltip",
                if (entry.tracked) "Убрать задание с табло и компаса" else "Переключить табло и компас на это задание",
            ) {
                val result = changeTracking(player, questId, !entry.tracked)
                if (result == DungeonQuestTrackingChange.LOADING || result == DungeonQuestTrackingChange.MISSING) {
                    quests(player, feedback = trackingMessage(result))
                } else {
                    quest(player, questId, trackingMessage(result))
                }
            })
            add(action("abandon", "quests.abandon", "<#ff6b61>Отказаться от задания", "quests.abandon-tooltip", "Открыть подтверждение отказа") {
                confirmAbandon(player, questId)
            })
        }
        show(player, PaperDialogScreen(
            id = "dungeon.quest", title = text("quests.detail-title", "<#c4a7e7>Задание"), body = body,
            buttons = buttons, exitButton = back { quests(player) }, columns = 2,
        )) { quest(player, questId) }
    }

    private fun confirmAbandon(player: Player, questId: UUID) {
        val entry = readQuests(player)?.firstOrNull { it.id == questId } ?: run {
            quests(player, feedback = text("quests.changed", "<#d7b486>Задание изменилось или было завершено. Список обновлён."))
            return
        }
        show(player, PaperDialogScreen(
            id = "dungeon.quest.abandon", title = text("quests.abandon-title", "<#ff8b82>Отказаться от задания?"),
            body = listOf(
                PaperDialogBody(plain(readableQuestText(TextUtil.legacy(entry.name))), 468),
                PaperDialogBody(text("quests.abandon-warning", "<#ffb277>Прогресс будет потерян. Повторно взять задание можно будет только когда оно снова доступно по его правилам. Это действие нельзя отменить."), 468),
            ),
            buttons = listOf(action("confirm_abandon", "quests.confirm-abandon", "<#ff6b61>Отказаться", "quests.confirm-abandon-tooltip", "Удалить принятое задание и его прогресс") {
                when (val result = abandonQuest(player, questId)) {
                    DungeonQuestAbandonChange.ABANDONED -> quests(player, feedback = abandonMessage(result))
                    DungeonQuestAbandonChange.LOADING, DungeonQuestAbandonChange.MISSING -> quests(player, feedback = abandonMessage(result))
                    DungeonQuestAbandonChange.FAILED -> quest(player, questId, abandonMessage(result))
                }
            }),
            exitButton = back { quest(player, questId) }, columns = 1,
        )) { confirmAbandon(player, questId) }
    }

    private fun questName(quest: DungeonQuestInfo, navigation: Boolean = false): Component {
        val surface = if (navigation) "open" else "list-name"
        val state = if (quest.tracked) "tracked" else "untracked"
        val label = if (quest.tracked) "<#9bd48d>✔ <name>" else "<white>○ <name>"
        return text("quests.$surface-$state", label + if (navigation) " <#c4a7e7>›" else "",
            "name" to Component.text(plainDungeonQuestText(quest.name)))
    }

    private fun questStatus(quest: DungeonQuestInfo): Component = when {
        quest.complete && quest.tracked -> text("quests.status-complete-tracked", "<#9bd48d>Готово к сдаче · <#9bd48d>Отслеживается")
        quest.complete -> text("quests.status-complete-untracked", "<#9bd48d>Готово к сдаче · <#f2eee8>Не отслеживается")
        quest.tracked -> text("quests.status-in-progress-tracked", "<#f2eee8>В процессе · <#9bd48d>Отслеживается")
        else -> text("quests.status-in-progress-untracked", "<#f2eee8>В процессе · <#f2eee8>Не отслеживается")
    }

    private fun trackingMessage(result: DungeonQuestTrackingChange): Component = when (result) {
        DungeonQuestTrackingChange.TRACKED -> text("quests.tracking-changed", "<#9bd48d>✔ Теперь отслеживается это задание.")
        DungeonQuestTrackingChange.UNTRACKED -> text("quests.tracking-stopped", "<#f2eee8>Отслеживание выключено.")
        DungeonQuestTrackingChange.LOADING -> text("quests.unavailable", "<#d7b486>Данные заданий ещё загружаются. Попробуйте обновить страницу.")
        DungeonQuestTrackingChange.MISSING -> text("quests.changed", "<#d7b486>Задание изменилось или было завершено. Список обновлён.")
        DungeonQuestTrackingChange.UNTRACKABLE -> text("quests.not-trackable", "<#d7b486>Это задание нельзя выбрать для отслеживания.")
        DungeonQuestTrackingChange.FAILED -> text("quests.tracking-failed", "<#d7b486>Не удалось изменить отслеживание. Попробуйте ещё раз.")
    }

    private fun abandonMessage(result: DungeonQuestAbandonChange): Component = when (result) {
        DungeonQuestAbandonChange.ABANDONED -> text("quests.abandon-success", "<#9bd48d>✔ Вы отказались от задания.")
        DungeonQuestAbandonChange.LOADING -> text("quests.unavailable", "<#d7b486>Данные заданий ещё загружаются. Попробуйте обновить страницу.")
        DungeonQuestAbandonChange.MISSING -> text("quests.changed", "<#d7b486>Задание изменилось или было завершено. Список обновлён.")
        DungeonQuestAbandonChange.FAILED -> text("quests.abandon-failed", "<#d7b486>Не удалось отказаться от задания. Оно осталось в списке.")
    }

    internal fun open(player: Player, feedback: Component? = null) {
        val view = dungeon.view(player) ?: run {
            val panel = dungeon.panelView(player)
            if (panel == null) unavailable(player) else {
                show(player, PaperDialogScreen(id = "dungeon.saves.unavailable", title = text("saves.dialog.title", "<#c4a7e7>Сохранения"),
                    body = listOf(PaperDialogBody(availability(dungeon.saveBlockReason(player, null)), 468)),
                    buttons = listOf(autosaveSettingsButton(player), guide(player) { open(player) }), exitButton = back { panel(player) })) { open(player) }
            }
            return
        }
        val blocked = dungeon.saveBlockReason(player, view)
        val body = mutableListOf(
            PaperDialogBody(text("saves.dialog.body", "<#e8dfd2>Ваши места в этом данже. Сохраняется только позиция: добыча и монстры не откатываются."), 468),
            PaperDialogBody(dungeon.autosaveDescription(player), 468),
            table(saveRows(view), DialogTables.Frame.ARTIFACT),
            PaperDialogBody(availability(blocked), 468),
        )
        if (view.points.none { it.kind == DungeonSaveKind.MANUAL }) body += PaperDialogBody(text("saves.dialog.empty", "<#e8dfd2>Ручных точек пока нет. Сохраните удобное место для возвращения."), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.saves", title = text("saves.dialog.title", "<#c4a7e7>Сохранения"), body = body,
            buttons = listOf(
                action("save", "saves.dialog.save-label", "<#c4a7e7>Сохранить здесь ›", "saves.dialog.save-tooltip", "Запомнить текущую позицию") {
                    if (blocked == null) saveForm(player, view) else open(player)
                }.let { if (blocked == null) it else it.copy(label = text("saves.dialog.save-disabled", "<#e8dfd2>[Недоступно] Сохранить здесь"), tooltip = blocked) },
                autosaveSettingsButton(player),
            ) + listOfNotNull(view.exit?.let { locationButton(player, "exit", "saves.dialog.exit-label", "<#92bed8>Место прошлого выхода ›", "saves.dialog.exit-tooltip", it, view) }) +
                view.points.mapIndexed { index, point -> pointButton("point_$index", pointLabel(point), pointTooltip(point)) { detail(player, point, view) } },
            exitButton = back { panel(player) }, columns = 2,
        )) { open(player) }
    }

    private fun unavailable(player: Player, feedback: Component? = null) {
        val destination = dungeon.lastReturn(player)
        val body = mutableListOf(
            PaperDialogBody(text("panel.outside", "<#e8dfd2>Подготовка к походу<newline><#e8dfd2>Почитайте гайд или выберите данж. После входа здесь появится панель прохождения.<newline><#e8dfd2>Используйте кнопки «К порталам» и «Выбрать данж». В данже меню открывается через <#d7b486>Shift + F<#e8dfd2>."), 468),
            crystalBalance(player),
        )
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
        id = "dungeon.panel.unavailable", title = text("panel.title", "<#ffb277>Панель данжа"),
        body = body,
        buttons = listOf(
            action("return", "panel.return-label", "<#92bed8>Вернуться в данж ›", "panel.return-tooltip", "Вернуться в последний обычный данж на место выхода", close = destination != null) {
                if (destination != null) dungeon.returnToLast(player, destination)
                else panel(player)
            }.let { if (destination != null) it else it.copy(label = text("panel.return-disabled", "<#e8dfd2>[Недоступно] Вернуться в данж"),
                tooltip = text("panel.return-unavailable", "<#e8dfd2>Нет доступного места выхода из обычного данжа. Сначала посетите данж и выйдите из него.")) },
            lostLoot(player),
            guide(player) { unavailable(player) },
            action("portals", "panel.portals-label", "<#92bed8>К порталам ›", "panel.portals-tooltip", "Перейти к порталам данжей в гильдии", close = true) { dungeon.action(player, "tp") },
            action("list", "panel.list-label", "<#ffb277>Выбрать данж ›", "panel.list-tooltip", "Открыть список данжей EliteMobs", close = true) { dungeon.action(player, "list") },
            classButton(player),
            partyButton(player),
            scoreboardButton(player),
        ), exitButton = if (MenuEscapeBehavior.goesBack(player)) back {} else close(), columns = 2,
        )) { unavailable(player) }
    }

    private fun crystalBalance(player: Player) = PaperDialogBody(crystals(player)?.let {
        text("panel.crystals", "<#e8dfd2>Ваши кристаллы: <#c7a0e8>💎 <value>", "value" to Component.text(it))
    } ?: text("panel.crystals-unavailable", "<#e8dfd2>Кристаллы: баланс сейчас недоступен"), 468)

    private fun lostLoot(player: Player) = action("lost_loot", "lost-loot.label", "<#c4a7e7>Потерянная добыча ›", "lost-loot.tooltip", "Забрать или продать сохранённый лут со всех данжей", close = true) {
        ru.arc.eliteloot.LostLootModule.open(player)
    }

    private fun classButton(player: Player) = action(
        "classes", "classes.label", "<#c4abff>Классы ›",
        "classes.tooltip", "Класс, прогресс, способности и ветки развития",
    ) { classes(player) }

    internal fun classes(player: Player, feedback: Component? = null) {
        val view = classService.view(player)
        val body = mutableListOf(PaperDialogBody(text(
            "classes.body",
            "<#e8dfd2>Сначала выберите базовый класс. Внутри вы увидите кнопку выбора и следующие ступени его развития.",
        ), 468))
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        when (view.availability) {
            DungeonClassAvailability.DISABLED -> body += PaperDialogBody(text(
                "classes.disabled", "<#ffffff>[Недоступно] Система классов EliteMobs выключена на этом сервере.",
            ), 468)
            DungeonClassAvailability.LOADING -> body += PaperDialogBody(text(
                "classes.loading", "<#e8dfd2>Профиль класса загружается. Откройте страницу ещё раз через несколько секунд.",
            ), 468)
            DungeonClassAvailability.READY -> {
                val active = view.activeForm
                val rows = mutableListOf(
                    text("classes.current-label", "<#e8dfd2>Активный класс") to (active?.let {
                        text("classes.current-value", "<#ffffff><name>", "name" to Component.text(it.name))
                    } ?: text("classes.none", "<#ffffff>Не выбран")),
                    text("classes.change-label", "<#e8dfd2>Смена класса") to classChangeState(view),
                )
                active?.let {
                    rows.add(1, text("classes.level-label", "<#e8dfd2>Уровень") to Component.text("${it.level} / ${it.cap}"))
                    rows.add(2, text("classes.path-label", "<#e8dfd2>Ветка") to Component.text(it.path.joinToString(" › ")))
                }
                body += classTable(rows, DialogTables.Frame.ARTIFACT)
                if (active != null) body += abilitiesTable(active)
            }
        }
        val buttons = if (view.availability != DungeonClassAvailability.READY) emptyList() else buildList {
            for (rootId in view.roots) view.forms[rootId]?.let { root ->
                val activeTree = view.activeForm?.rootId == root.id
                add(PaperDialogButton(
                    PaperDialogActionId.of("class_${root.id}"),
                    text(
                        if (activeTree) "classes.root-active" else if (root.unlocked) "classes.root" else "classes.root-locked",
                        if (activeTree) "<#9bd48d>✔ <name> ›"
                        else if (root.unlocked) "<#c4abff><name> ›"
                        else "<#ffffff>[Недоступно] <name> ›",
                        "name" to Component.text(root.name),
                    ),
                    tooltip = text(
                        if (root.unlocked) "classes.root-tooltip" else "classes.root-locked-tooltip",
                        if (root.unlocked) "<#e8dfd2><role><newline><newline><#e8dfd2>Открыть ветку класса"
                        else "<#e8dfd2><role><newline><newline><#e8dfd2>Показать требования и способности",
                        "role" to text("classes.catalog.${root.id}.role", root.weapons.joinToString(" / ")),
                    ),
                    width = 230,
                    onClick = { classDetail(player, root.id) },
                ))
            }
            view.activeForm?.let {
                if (view.canChange) add(action(
                    "class_clear", "classes.clear-label", "<#ff6b61>Отключить класс",
                    "classes.clear-tooltip", "Играть без класса; накопленный прогресс сохранится",
                ) { classes(player, classChangeMessage(classService.clear(player), null, clear = true)) })
                else add(disabledClassAction(
                    "class_clear", "classes.clear-disabled", "<#ffffff>[Недоступно] Отключить класс", classLockReason(view),
                ) { classes(player) })
            }
        }
        show(player, PaperDialogScreen(
            id = "dungeon.classes", title = text("classes.title", "<#c4abff>Классы"),
            body = body, buttons = buttons,
            exitButton = back { if (dungeon.panelView(player) == null) unavailable(player) else panel(player) }, columns = 2,
        )) { classes(player) }
    }

    private fun classDetail(player: Player, formId: String, feedback: Component? = null) {
        val view = classService.view(player)
        val form = view.forms[formId]
        if (view.availability != DungeonClassAvailability.READY || form == null) {
            classes(player, text("classes.changed", "<#ffffff>Список классов изменился. Страница обновлена."))
            return
        }
        val state = when {
            form.active && view.runLocked -> text("classes.state-run", "<#9bd48d>Активен · до конца похода")
            form.active -> text("classes.state-active", "<#9bd48d>Активен")
            form.unlocked -> text("classes.state-unlocked", "<#ffffff>Открыт")
            else -> text("classes.state-locked", "<#ffffff>Заблокирован")
        }
        val intro = when {
            form.active -> text("classes.detail-active", "<#9bd48d>Это ваш активный класс. Ниже — способности и следующее развитие.")
            form.unlocked -> text("classes.detail-unlocked", "<#e8dfd2>Класс открыт. Нажмите «Выбрать», чтобы сделать его активным.")
            else -> text("classes.detail-locked", "<#e8dfd2>Эта форма пока закрыта. Ниже показано, чего именно не хватает.")
        }
        val body = mutableListOf(
            PaperDialogBody(intro, 468),
            classTable(listOf(
                text("classes.state-label", "<#e8dfd2>Состояние") to state,
                text("classes.level-label", "<#e8dfd2>Уровень") to if (form.unlocked) Component.text("${form.level} / ${form.cap}") else Component.text("—"),
                text("classes.xp-label", "<#e8dfd2>Опыт") to Component.text(form.xp),
                text("classes.path-label", "<#e8dfd2>Ветка") to Component.text(form.path.joinToString(" › ")),
                text("classes.resource-label", "<#e8dfd2>Ресурс") to Component.text("${form.resource} — ${form.resourceDescription}"),
                text("classes.weapons-label", "<#e8dfd2>Оружие") to Component.text(form.weapons.joinToString(", ")),
            ), DialogTables.Frame.ARTIFACT),
            abilitiesTable(form),
            classTable(
                form.passives.map { Component.text(it.source) to Component.text(it.description) },
                DialogTables.Frame.EPIC,
                text("classes.passive-source", "<#e8dfd2>Источник") to text("classes.passive-effect", "<#e8dfd2>Пассивный эффект"),
            ),
        )
        if (form.blockers.isNotEmpty()) {
            body.add(2, classTable(
                form.blockers.map { Component.text(it.name) to Component.text(it.progress) },
                DialogTables.Frame.LEGENDARY,
                text("classes.requirement", "<#e8dfd2>Чтобы открыть") to text("classes.progress", "<#e8dfd2>Сейчас"),
            ))
        }
        if (form.unlocked && form.children.isNotEmpty()) body += PaperDialogBody(text(
            "classes.next-heading",
            "<#e8dfd2>Следующие ступени — выберите направление развития:",
        ), 468)
        feedback?.let { body.add(0, PaperDialogBody(plain(it), 468)) }

        val buttons = buildList {
            if (!form.active) {
                if (!form.unlocked && canGrantClasses(player)) {
                    if (view.runLocked) add(disabledClassAction(
                        "class_admin_grant_${form.id}", "classes.admin-disabled", "<#ffffff>[Недоступно] Открыть класс",
                        classLockReason(view),
                    ) { classDetail(player, form.id) })
                    else add(action(
                        "class_admin_grant_${form.id}", "classes.admin-label", "<#c4a7e7>Открыть класс",
                        "classes.admin-tooltip", "Открыть и выбрать класс, повысив только необходимые боевые навыки",
                    ) {
                        classDetail(player, form.id, classAdminGrantMessage(classGrants.grant(player, form.id)))
                    })
                } else if (form.unlocked && view.canChange) add(action(
                    "class_select_${form.id}", "classes.select-label", "<#9bd48d>Выбрать класс",
                    "classes.select-tooltip", "Сделать <name> активным классом",
                ) {
                    classDetail(player, form.id, classChangeMessage(classService.select(player, form.id), form.name))
                }.copy(tooltip = text("classes.select-tooltip", "Сделать <name> активным классом", "name" to Component.text(form.name))))
                else add(disabledClassAction(
                    "class_select_${form.id}", "classes.select-disabled", "<#ffffff>[Недоступно] Выбрать класс",
                    if (!form.unlocked) text("classes.select-locked-tooltip", "Сначала выполните условия открытия класса") else classLockReason(view),
                ) { classDetail(player, form.id) })
            }
            if (form.unlocked) form.children.mapNotNull(view.forms::get).forEach { add(classNextDestination(player, it)) }
            form.parentId?.let(view.forms::get)?.let { parent -> add(classPreviousDestination(player, parent)) }
        }
        show(player, PaperDialogScreen(
            id = "dungeon.classes.${form.id}",
            title = text("classes.detail-title", "<#c4abff><name>", "name" to Component.text(form.name)),
            body = body, buttons = buttons, exitButton = back { classes(player) }, columns = 2,
        )) { classDetail(player, form.id) }
    }

    private fun classNextDestination(player: Player, form: DungeonClassForm) = PaperDialogButton(
        PaperDialogActionId.of("class_${form.id}"),
        text(
            if (form.active) "classes.next-active" else if (form.unlocked) "classes.next" else "classes.next-locked",
            if (form.active) "<#9bd48d>✔ <name> ›"
            else if (form.unlocked) "<#c4abff><name> ›"
            else "<#ffffff>[Недоступно] <name> ›",
            "name" to Component.text(form.name),
        ),
        tooltip = text(
            if (form.unlocked) "classes.form-tooltip" else "classes.form-locked-tooltip",
            if (form.unlocked) "<#e8dfd2>Открыть ступень класса<newline><newline><#e8dfd2>Уровень ветки: <level>"
            else "<#e8dfd2>Откроется на <level> уровне<newline><newline><#e8dfd2>Показать требования и способности",
            "level" to Component.text(form.requiredLevel),
        ),
        width = 230,
        onClick = { classDetail(player, form.id) },
    )

    private fun classPreviousDestination(player: Player, form: DungeonClassForm) = PaperDialogButton(
        PaperDialogActionId.of("class_${form.id}"),
        text(
            "classes.previous",
            "<#c4abff>‹ <name>",
            "name" to Component.text(form.name),
        ),
        tooltip = text("classes.previous-tooltip", "Вернуться на предыдущую ступень этой ветки"),
        width = 230,
        onClick = { classDetail(player, form.id) },
    )

    private fun disabledClassAction(
        id: String,
        key: String,
        fallback: String,
        tooltip: Component,
        vararg values: Pair<String, Component>,
        refresh: () -> Unit,
    ) = PaperDialogButton(
        PaperDialogActionId.of(id), text(key, fallback, *values), tooltip = tooltip,
        width = 230, onClick = { refresh() },
    )

    private fun classSummary(view: DungeonClassesView): Component = when (view.availability) {
        DungeonClassAvailability.DISABLED -> text("classes.disabled-short", "<#ffffff>Недоступны")
        DungeonClassAvailability.LOADING -> text("classes.loading-short", "<#e8dfd2>Загрузка…")
        DungeonClassAvailability.READY -> view.activeForm?.let {
            text("classes.summary", "<#ffffff><name> · <level> ур.",
                "name" to Component.text(it.name), "level" to Component.text(it.level))
        } ?: text("classes.none", "<#ffffff>Не выбран")
    }

    private fun classAdminGrantMessage(result: DungeonClassGrantResult): Component = when (result.status) {
        DungeonClassGrantResult.Status.APPLIED -> if (result.skillIncreases.isEmpty()) {
            text("classes.admin-applied", "<#9bd48d>✔ Класс <name> открыт.", "name" to Component.text(result.formName))
        } else {
            text(
                "classes.admin-applied-with-skills",
                "<#9bd48d>✔ Класс <name> открыт. Повышены навыки: <skills>.",
                "name" to Component.text(result.formName),
                "skills" to Component.text(result.skillIncreases.joinToString(", ") { "${it.name} ${it.previousLevel} → ${it.newLevel}" }),
            )
        }
        DungeonClassGrantResult.Status.ALREADY_GRANTED ->
            text("classes.admin-already", "<#e8dfd2>Класс <name> уже открыт.", "name" to Component.text(result.formName))
        DungeonClassGrantResult.Status.DISABLED -> text("classes.admin-disabled-system", "<#d7b486>Система классов сейчас выключена.")
        DungeonClassGrantResult.Status.NOT_READY -> text("classes.admin-loading", "<#d7b486>Профиль класса ещё загружается.")
        DungeonClassGrantResult.Status.UNKNOWN_FORM -> text("classes.admin-unknown", "<#d7b486>Эта форма класса больше не существует.")
        DungeonClassGrantResult.Status.RUN_LOCKED -> text("classes.admin-run-locked", "<#d7b486>Класс зафиксирован до завершения текущего похода.")
        DungeonClassGrantResult.Status.FAILED -> text("classes.admin-failed", "<#d7b486>Не удалось открыть класс. Изменения отменены.")
    }

    private fun classChangeState(view: DungeonClassesView): Component = when {
        view.runLocked -> text("classes.change-run", "<#ffffff>После завершения похода")
        view.combatLocked -> text("classes.change-combat", "<#ffffff>После выхода из боя")
        else -> text("classes.change-ready", "<#9bd48d>Доступна")
    }

    private fun classLockReason(view: DungeonClassesView): Component = when {
        view.runLocked -> text("classes.lock-run", "<#ffffff>Класс зафиксирован до завершения текущего похода")
        view.combatLocked -> text("classes.lock-combat", "<#ffffff>Сменить класс можно после выхода из боя")
        else -> text("classes.locked", "<#ffffff>Класс сейчас изменить нельзя")
    }

    private fun classChangeMessage(result: DungeonClassChange, name: String?, clear: Boolean = false): Component = when (result) {
        DungeonClassChange.APPLIED -> if (clear) text("classes.result.cleared", "<#9bd48d>✔ Класс отключён. Прогресс сохранён.")
            else text("classes.result.selected", "<#9bd48d>✔ Активирован класс <name>.", "name" to Component.text(name.orEmpty()))
        DungeonClassChange.UNCHANGED -> text("classes.result.unchanged", "<#e8dfd2>Этот класс уже выбран.")
        DungeonClassChange.NOT_READY -> text("classes.result.loading", "<#e8dfd2>Профиль ещё загружается. Попробуйте снова через несколько секунд.")
        DungeonClassChange.LOCKED -> text("classes.result.locked", "<#ffffff>Сейчас класс изменить нельзя.")
        DungeonClassChange.UNKNOWN -> text("classes.result.unknown", "<#ffffff>Эта форма класса больше недоступна. Список обновлён.")
    }

    private fun abilitiesTable(form: DungeonClassForm) = classTable(listOf(
        text("classes.mobility", "<#92bed8>F, F") to abilityValue(form.mobility),
        text("classes.signature", "<#ffb277>F + ЛКМ") to abilityValue(form.signature),
        text("classes.utility", "<#9bd48d>F + ПКМ") to abilityValue(form.utility),
    ), DialogTables.Frame.EPIC, text("classes.control", "<#e8dfd2>Управление") to text("classes.ability", "<#e8dfd2>Способность"))

    private fun abilityValue(ability: DungeonClassAbility) = Component.text("${ability.name} — ${ability.description}")

    private fun classTable(
        rows: List<Pair<Component, Component>>,
        frame: DialogTables.Frame,
        headers: Pair<Component, Component>? = null,
    ) = DialogTables.body(rows, headers = headers, frame = frame, width = 420, columns = DialogTables.Columns.VALUE_WIDE)

    private fun autosaveSettingsButton(player: Player) = action("autosaves", "saves.settings.label", "<#c4a7e7>Автосохранение ›", "saves.settings.tooltip", "Выбрать интервал или отключить автоматические точки") { autosaveSettings(player) }

    private fun scoreboardButton(player: Player): PaperDialogButton {
        val enabled = dungeon.scoreboardEnabled(player)
        return action(
            "scoreboard",
            if (enabled) "panel.scoreboard-on-label" else "panel.scoreboard-off-label",
            if (enabled) "<#9bd48d>✔ Табло включено" else "<#f2eee8>○ Табло выключено",
            "panel.scoreboard-tooltip",
            "Показать или скрыть сведения о текущем походе справа на экране",
        ) {
            val result = dungeon.setScoreboardEnabled(player, !enabled)
            val feedback = result.message.takeUnless { result.success }
            if (dungeon.panelView(player) == null) unavailable(player, feedback) else panel(player, feedback)
        }
    }

    private fun autosaveSettings(player: Player, feedback: Component? = null) {
        val seconds = dungeon.autosaveSeconds(player)
        val body = mutableListOf(
            PaperDialogBody(text("saves.settings.body", "<#e8dfd2>Выберите интервал между автоматическими точками.<newline><#e8dfd2>Личная настройка сохраняется после выхода и действует во всех данжах этого сервера. Ручные точки и возврат к месту выхода не меняются."), 468),
            PaperDialogBody(text("saves.settings.current", "<#e8dfd2>Сейчас: <value>", "value" to autosaveIntervalLabel(seconds)), 468),
            PaperDialogBody(dungeon.autosaveDescription(player), 468),
        )
        if (!dungeon.canConfigureAutosaves()) body += PaperDialogBody(text("saves.settings.unavailable", "<#e8dfd2>Настройка автосохранения сейчас недоступна."), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.autosaves", title = text("saves.settings.title", "<#c4a7e7>Автосохранение"), body = body,
            buttons = if (dungeon.canConfigureAutosaves()) DUNGEON_AUTOSAVE_INTERVALS.map { interval ->
                PaperDialogButton(PaperDialogActionId.of("auto_$interval"),
                    text(if (seconds == interval) "saves.settings.selected" else "saves.settings.option",
                        if (seconds == interval) "<#9bd48d>✔ <value>" else "<white>○ <value>", "value" to autosaveIntervalLabel(interval)),
                    tooltip = text("saves.settings.option-tooltip", "Применить только к вашим автоматическим точкам"), width = 230,
                    onClick = {
                        if (dungeon.autosaveSeconds(player) != interval) {
                            val result = dungeon.setAutosaveSeconds(player, interval)
                            autosaveSettings(player, result.message.takeUnless { result.success })
                        }
                    })
            } else listOf(guide(player) { autosaveSettings(player) }),
            exitButton = back { open(player) }, columns = 2,
        )) { autosaveSettings(player) }
    }

    private fun autosaveIntervalLabel(seconds: Int): Component = when {
        seconds == 0 -> text("saves.settings.off", "Выключено")
        seconds % 60 == 0 -> text("saves.settings.minutes", "<value> мин", "value" to Component.text(seconds / 60))
        else -> text("saves.settings.seconds", "<value> с", "value" to Component.text(seconds))
    }

    private fun partyButton(player: Player) = action("party", "panel.party-label", "<#e5ba73>Группа ›", "panel.party-tooltip", "Как собрать пати и управлять группой EliteMobs") { party(player) }

    private fun skillBoostButton(player: Player) = action("skill_boosts", "boosts.label", "<#9bd48d>Бусты опыта ›", "boosts.tooltip", "Временный бонус к опыту боевых навыков EliteMobs") { skillBoosts(player) }

    private fun skillBoosts(player: Player, feedback: Component? = null) {
        val expiry = dungeon.skillBoosts.currentExpiry(player)
        val remaining = expiry?.let { java.time.Duration.between(Instant.now(), it) }
            ?.takeUnless { it.isNegative }
        val body = mutableListOf(
            PaperDialogBody(text("boosts.body", "<#e8dfd2>Бонус <#9bd48d>+25%<#e8dfd2> действует на опыт всех боевых навыков EliteMobs. Новая покупка продлевает оставшееся время."), 468),
            crystalBalance(player),
            PaperDialogBody(if (remaining == null || remaining.isZero) text("boosts.inactive", "<#e8dfd2>Сейчас бонус не активен.")
                else text("boosts.active", "<#9bd48d>✔ Бонус активен ещё <time>", "time" to Component.text(boostDuration(remaining))), 468),
        )
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.skill-boosts", title = text("boosts.title", "<#9bd48d>Бусты опыта"), body = body,
            buttons = dungeon.skillBoosts.list().map { offer ->
                action("boost_${offer.id}", "boosts.${offer.id}.label", "<#9bd48d>+25% · <time> <#e8dfd2>· 💎 <price>",
                    "boosts.${offer.id}.tooltip", "Продлить бонус на <time>") {
                    dungeon.skillBoosts.buy(player, offer) { result ->
                        if (result.success) player.playSound(player.location, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.55f, 1.35f)
                        skillBoosts(player, skillBoostResult(result))
                    }
                }.copy(
                    label = text("boosts.${offer.id}.label", "<#9bd48d>+25% · <time> <#e8dfd2>· 💎 <price>",
                        "time" to Component.text(boostDuration(offer.duration)), "price" to price(offer.price)),
                    tooltip = text("boosts.${offer.id}.tooltip", "Продлить бонус на <time>", "time" to Component.text(boostDuration(offer.duration))),
                )
            }, exitButton = back { shop(player) }, columns = 1,
        )) { skillBoosts(player) }
    }

    private fun boostDuration(duration: java.time.Duration): String {
        val minutes = duration.toMinutes().coerceAtLeast(0)
        return if (minutes % 60L == 0L) "${minutes / 60} ч" else "${minutes} мин"
    }

    private fun skillBoostResult(result: SkillXpBoostResult): Component = when (result) {
        SkillXpBoostResult.BOUGHT -> text("boosts.result.bought", "<#9bd48d>✔ Бонус активирован и время добавлено.")
        SkillXpBoostResult.NO_MONEY -> text("boosts.result.no-money", "<#d7b486>Не хватает кристаллов.")
        SkillXpBoostResult.PENDING -> text("boosts.result.pending", "<#d7b486>Предыдущая покупка ещё выполняется.")
        SkillXpBoostResult.CHANGED -> text("boosts.result.changed", "<#d7b486>Предложение изменилось. Выберите его заново.")
        SkillXpBoostResult.PAYMENT_FAILED -> text("boosts.result.payment-failed", "<#d7b486>Не удалось списать кристаллы. Покупка отменена.")
        SkillXpBoostResult.GRANT_FAILED -> text("boosts.result.grant-failed", "<#d7b486>Не удалось включить бонус. Кристаллы возвращены.")
    }

    internal fun party(player: Player, feedback: Component? = null) {
        val view = dungeon.parties.view(player)
        val body = mutableListOf(
            PaperDialogBody(text("party.body", "<#e8dfd2>Соберите группу до входа в данж. Приглашённый игрок принимает приглашение здесь же."), 468),
        )
        if (!view.available) body += PaperDialogBody(text("party.unavailable", "<#d7b486>Группы EliteMobs на этом сервере недоступны или у вас нет доступа."), 468)
        else if (view.inParty) {
            body += table(view.members.map { member ->
                text(if (member.leader) "party.leader" else "party.member",
                    if (member.leader) "<#f4d87a>Лидер" else "<#e8dfd2>Участник") to
                    text("party.member-value", if (member.online) "<#9bd48d><name>" else "<#aaa49a><name> · не в сети", "name" to Component.text(member.name))
            }, DialogTables.Frame.ARTIFACT)
            if (view.invitableNames.isNotEmpty()) body += PaperDialogBody(text("party.invitable", "<#e8dfd2>Можно пригласить: <#92bed8><players>",
                "players" to Component.text(view.invitableNames.joinToString(", "))), 468)
        } else body += PaperDialogBody(text("party.empty", "<#e8dfd2>Вы пока без группы. Можно создать её самому или принять последнее приглашение."), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        val inputs = if (view.available && view.inParty && view.invitableNames.isNotEmpty())
            listOf(PaperDialogTextInput(partyPlayerInput, text("party.player-input", "<#e8dfd2>Ник игрока"), maxLength = 16)) else emptyList()
        val buttons = if (!view.available) listOf(guide(player) { party(player) }) else buildList {
            if (!view.inParty) {
                add(action("create_party", "party.create-label", "<#9bd48d>Создать группу", "party.create-tooltip", "Создать новую группу EliteMobs") {
                    party(player, partyResult(dungeon.parties.create(player)))
                })
                add(action("accept_party", "party.accept-label", "<#92bed8>Принять приглашение", "party.accept-tooltip", "Принять последнее действующее приглашение") {
                    party(player, partyResult(dungeon.parties.accept(player)))
                })
            } else {
                if (view.invitableNames.isNotEmpty()) add(PaperDialogButton(
                    PaperDialogActionId.of("invite_party"), text("party.invite-label", "<#92bed8>Пригласить"),
                    tooltip = text("party.invite-tooltip", "Отправить приглашение указанному игроку"), width = 230,
                    onClick = { context -> party(player, partyResult(dungeon.parties.invite(player, context.text(partyPlayerInput).orEmpty()))) },
                ))
                add(action("leave_party", "party.leave-label", "<#d7b486>Покинуть группу", "party.leave-tooltip", "Выйти из текущей группы") {
                    party(player, partyResult(dungeon.parties.leave(player)))
                })
            }
        }
        show(player, PaperDialogScreen(
            id = "dungeon.party", title = text("party.title", "<#e5ba73>Группа"),
            body = body, inputs = inputs, buttons = buttons,
            exitButton = back { if (dungeon.panelView(player) == null) unavailable(player) else panel(player) }, columns = 2,
        )) { party(player) }
    }

    private fun partyResult(result: com.magmaguy.elitemobs.parties.PartyOperationResult): Component = when (result) {
        com.magmaguy.elitemobs.parties.PartyOperationResult.SUCCESS -> text("party.result.success", "<#9bd48d>✔ Группа обновлена.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.PLAYER_UNAVAILABLE -> text("party.result.player-unavailable", "<#d7b486>Игрок не найден или не в сети.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.ALREADY_IN_PARTY -> text("party.result.already", "<#d7b486>Вы уже состоите в группе.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.SELF_INVITE -> text("party.result.self", "<#d7b486>Себя приглашать не нужно.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.TARGET_ALREADY_IN_PARTY -> text("party.result.target-party", "<#d7b486>Этот игрок уже состоит в группе.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.TARGET_NO_PERMISSION -> text("party.result.target-access", "<#d7b486>Этому игроку недоступны группы EliteMobs.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.INVITE_ALREADY_PENDING -> text("party.result.pending", "<#d7b486>У игрока уже есть действующее приглашение.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.PARTY_FULL -> text("party.result.full", "<#d7b486>В группе уже пять участников.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.NO_PENDING_INVITE -> text("party.result.no-invite", "<#d7b486>Действующего приглашения нет.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.INVITE_EXPIRED -> text("party.result.expired", "<#d7b486>Приглашение устарело. Попросите отправить новое.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.NOT_IN_PARTY -> text("party.result.not-in-party", "<#d7b486>Вы не состоите в группе.")
        com.magmaguy.elitemobs.parties.PartyOperationResult.DISABLED,
        com.magmaguy.elitemobs.parties.PartyOperationResult.NO_PERMISSION -> text("party.result.unavailable", "<#d7b486>Группы сейчас недоступны.")
    }

    private fun tableLabel(key: String, fallback: String) = text("table.$key", fallback)

    private fun table(rows: List<Pair<Component, Component>>, frame: DialogTables.Frame) = DialogTables.body(
        rows, headers = tableLabel("label-heading", "Параметр") to tableLabel("value-heading", "Значение"),
        frame = frame, width = 320,
    )

    private fun saveRows(view: DungeonSaveView) = listOf(
        tableLabel("manual-count", "Ручные точки") to text("table.manual-value", "<value> / 5",
            "value" to Component.text(view.points.count { it.kind == DungeonSaveKind.MANUAL })),
        tableLabel("auto-count", "Автоточки") to text("table.auto-value", "<value> / 3",
            "value" to Component.text(view.points.count { it.kind == DungeonSaveKind.AUTO })),
    )

    private fun availability(reason: Component?): Component = reason?.let {
        text("saves.dialog.unavailable", "<#d7b486>Сохраниться здесь нельзя<newline><reason>", "reason" to it)
    } ?: text("saves.dialog.ready", "<#9bd48d>✔ Здесь можно сохраниться")

    private fun guide(player: Player, returnTo: () -> Unit) = action("guide", "panel.guide-label", "<#86dcf1>Гайд ›", "panel.guide-tooltip", "Правила и советы по прохождению данжей") {
        if (!HelpCenterModule.openDungeonsGuide(player, returnTo)) player.sendMessage(text("panel.guide-unavailable", "<#d7b486>Гайд сейчас недоступен. Попробуйте позже."))
    }

    private fun close() = action("close", "saves.dialog.close-label", "<#e8dfd2>Закрыть", "saves.dialog.close-tooltip", "Вернуться в игру", close = true) { }.copy(width = 200)

    private fun about(player: Player) {
        val visit = dungeon.panelView(player)?.visit ?: run { unavailable(player); return }
        show(player, PaperDialogScreen(
            id = "dungeon.about",
            title = text("panel.about-title", "<#86dcf1>О данже"),
            body = listOf(
                PaperDialogBody(dungeonDisplayName(visit), 468),
                PaperDialogBody(dungeon.context(player), 468),
            ),
            buttons = listOf(guide(player) { about(player) }),
            exitButton = back { panel(player) },
        )) { about(player) }
    }

    internal fun shop(player: Player, feedback: Component? = null) {
        val view = dungeon.panelView(player) ?: run { unavailable(player); return }
        val body = mutableListOf(
            PaperDialogBody(text("shop.body", "<#e8dfd2>Нажмите на товар, чтобы сразу купить его за кристаллы EliteMobs. Кейс откроется и выдаст одну награду, привязанную к вам."), 468),
            PaperDialogBody(balance(player), 468),
        )
        feedback?.let { body += PaperDialogBody(it, 468) }
        show(player, PaperDialogScreen(id = "dungeon.shop", title = text("shop.title", "<#f4d87a>Припасы и кейсы"), body = body,
            buttons = listOf(skillBoostButton(player)) + dungeon.supplies.list().map { offer ->
                val quote = dungeon.supplies.quote(player, offer)
                pointButton("supply_${offer.id}", text("shop.offer-label", "<#f4d87a><name> ×<amount> <#e8dfd2>· 💎 <price> <#f4d87a>›",
                    "name" to supplyName(offer), "amount" to Component.text(offer.amount), "price" to price(offer.price)),
                    if (quote == null) text("shop.item-unavailable", "<#e8dfd2>Этот предмет сейчас недоступен. Кристаллы не списываются.") else supplyDescription(offer)) {
                    if (quote == null) shop(player, text("shop.item-unavailable", "<#e8dfd2>Этот предмет сейчас недоступен. Кристаллы не списываются."))
                    else {
                        val result = dungeon.supplies.buy(player, quote, view)
                        if (result.success) player.playSound(player.location, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.2f)
                        shop(player, supplyResult(result))
                    }
                }
            }, exitButton = back { panel(player) }, columns = 2,
        )) { shop(player) }
    }

    private fun supplyResult(result: SupplyResult): Component = when (result) {
        SupplyResult.BOUGHT -> text("shop.bought", "<#9bd48d>✔ Покупка завершена. Предмет добавлен в инвентарь.")
        SupplyResult.CHANGED -> text("shop.changed", "<#d7b486>Товар изменился. Вернитесь в магазин и выберите его заново.")
        SupplyResult.OUTSIDE -> text("shop.outside", "<#d7b486>Данж изменился. Откройте меню данжа заново (в данже — Shift + F); покупка не выполнена.")
        SupplyResult.NO_SPACE -> text("shop.no-space", "<#d7b486>Освободите место в инвентаре. Кристаллы не списаны.")
        SupplyResult.NO_MONEY -> text("shop.no-money", "<#d7b486>Не хватает кристаллов. Покупка не выполнена.")
        SupplyResult.ITEM_UNAVAILABLE -> text("shop.item-unavailable", "<#e8dfd2>Этот предмет сейчас недоступен. Кристаллы не списываются.")
        SupplyResult.PAYMENT_FAILED -> text("shop.payment-failed", "<#d7b486>Не удалось завершить оплату. Покупка не выполнена.")
    }

    private fun supplyName(offer: SupplyOffer): Component = text("shop.stock.${offer.id}.name", when (offer.id) {
        "beef" -> "Стейки"; "bread" -> "Хлеб"; "arrows" -> "Стрелы"; "healing" -> "Зелье лечения"; "merchant" -> "Свиток торговца"; "enchant_case" -> "Кейс чар EliteMobs"; "loot_case" -> "Кейс снаряжения"; "loot_case_large" -> "Кейс опытного бойца"; else -> offer.id
    })
    private fun supplyDescription(offer: SupplyOffer): Component = text("shop.stock.${offer.id}.description", when (offer.id) {
        "beef" -> "<#e8dfd2>Обычные стейки для восстановления сытости."
        "bread" -> "<#e8dfd2>Обычный хлеб для восстановления сытости."
        "arrows" -> "<#e8dfd2>Обычные стрелы для лука и арбалета."
        "healing" -> "<#e8dfd2>Восстанавливает 2 сердца после питья.<newline><#e8dfd2>Зажмите ПКМ, чтобы выпить. После использования останется пустая бутылочка."
        "merchant" -> "<#e8dfd2>Настоящий свиток EliteMobs. ПКМ вызывает странствующего торговца и расходует свиток.<newline><#e8dfd2>Между вызовами — 60 секунд."
        "enchant_case" -> "<#e8dfd2>Одна книга EliteMobs с чарами I уровня.<newline>Критические удары — 40%; Ледокол — 30%;<newline>Молния — 20%; Огнемёт — 10%.<newline><newline>Открывается сразу. Книга привязана к вам.<newline>Применяется у зачарователя EliteMobs; его цена и шанс успеха оплачиваются отдельно."
        "loot_case", "loot_case_large" -> "<#e8dfd2>Один случайный предмет снаряжения EliteMobs.<newline>Предел: ваш боевой уровень, максимум ${if (offer.id == "loot_case_large") 40 else 20}.<newline>Уровень награды: 80% предела — шанс 60%;<newline>90% — шанс 30%; 100% — шанс 10%.<newline>Округление вниз, минимум 1. Тип предмета случаен.<newline><newline>Открывается сразу. Награда привязана к вам.<newline>Уникальные награды боссов не выпадают."
        else -> ""
    })
    private fun balance(player: Player): Component = text("shop.balance", "<#e8dfd2>Ваши кристаллы: <#c7a0e8>💎 <value>",
        "value" to Component.text(readDungeonCrystals(player) ?: "—"))
    private fun price(value: Double): Component = Component.text(value.toBigDecimal().stripTrailingZeros().toPlainString())

    private fun saveForm(player: Player, expected: DungeonSaveView, feedback: Component? = null, name: String = "") {
        val body = mutableListOf(PaperDialogBody(text("saves.dialog.save-body", "<#e8dfd2>Введите имя точки. Такое же имя заменит вашу ручную точку."), 468))
        val blocked = dungeon.saveBlockReason(player, expected)
        body += PaperDialogBody(availability(blocked), 468)
        if (expected.points.count { it.kind == DungeonSaveKind.MANUAL } >= 5) body += PaperDialogBody(text("saves.dialog.replace-only", "<#d7b486>Все 5 мест заняты. Укажите имя существующей точки, чтобы заменить её, или сначала удалите ненужную."), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.saves.save",
            title = text("saves.dialog.save-title", "<#c4a7e7>Сохранить точку"),
            body = body,
            inputs = listOf(PaperDialogTextInput(nameInput, text("saves.dialog.name", "<#e8dfd2>Имя точки"), initial = name.take(32), maxLength = 32, width = 300)),
            buttons = listOf(
                action("save", "saves.dialog.confirm-save-label", "<#9bd48d>Сохранить", "saves.dialog.confirm-save-tooltip", "<#e8dfd2>Сохранить точку") { context ->
                    val entered = context.text(nameInput)?.trim().orEmpty()
                    if (blocked != null) saveForm(player, expected, name = entered) else {
                        val result = dungeon.save(player, entered, expected)
                        if (result.success) open(player, result.message) else saveForm(player, expected, result.message, entered)
                    }
                }.let { if (blocked == null) it else it.copy(label = text("saves.dialog.save-disabled", "<#e8dfd2>[Недоступно] Сохранить здесь"), tooltip = blocked) },
            ),
            exitButton = back { open(player) },
            columns = 1,
        ), null)
    }

    private fun detail(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.point",
            title = text("saves.dialog.point-title", "<#c4a7e7>Сохранение"),
            body = listOf(PaperDialogBody(plain(Component.text(point.name)), 468), table(listOf(
                tableLabel("kind", "Тип") to text(
                    if (point.kind == DungeonSaveKind.MANUAL) "saves.dialog.manual" else "saves.dialog.auto",
                    if (point.kind == DungeonSaveKind.MANUAL) "ручная" else "авто"),
                tableLabel("saved-at", "Сохранено") to Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt))),
                tableLabel("coordinates", "Координаты") to locationText(point.location),
            ), DialogTables.Frame.LEGENDARY)),
            buttons = listOf(
                action("travel", "saves.dialog.travel-label", "<#92bed8>Перейти ›", "saves.dialog.travel-tooltip", "Телепортироваться к точке", close = true) { dungeon.travel(player, expected, point.id) },
                action("remove", "saves.dialog.remove-label", "<#ff6b61>Удалить ›", "saves.dialog.remove-tooltip", "Удалить эту точку") { confirmRemove(player, point, expected) },
            ),
            exitButton = back { open(player) },
            columns = 2,
        )) { reopenPoint(player, point.id, false) }
    }

    private fun confirmRemove(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.remove",
            title = text("saves.dialog.remove-title", "<#ff6b61>Удалить точку?"),
            body = listOf(PaperDialogBody(text("saves.dialog.remove-body", "<#e8dfd2>Точка «<name>» будет удалена.", "name" to plain(Component.text(point.name))), 468)),
            buttons = listOf(
                action("confirm", "saves.dialog.confirm-remove-label", "<#ff6b61>Удалить", "saves.dialog.confirm-remove-tooltip", "Удалить точку") {
                    val result = dungeon.remove(player, point, expected)
                    open(player, result.message)
                },
            ),
            exitButton = back { detail(player, point, expected) },
            columns = 1,
        )) { reopenPoint(player, point.id, true) }
    }

    private fun reopenPoint(player: Player, id: String, removing: Boolean) {
        val view = dungeon.view(player) ?: run { open(player); return }
        val point = view.points.firstOrNull { it.id == id }
        if (point == null) open(player)
        else if (removing) confirmRemove(player, point, view) else detail(player, point, view)
    }

    private fun locationButton(player: Player, id: String, labelKey: String, labelFallback: String, tooltipKey: String, location: Location, expected: DungeonSaveView) =
        PaperDialogButton(PaperDialogActionId.of(id), text(labelKey, labelFallback), text(tooltipKey, "Координаты: <coords>", "coords" to locationText(location)), width = 230, closeDialogBeforeAction = true) { dungeon.travel(player, expected, id) }

    private fun pointLabel(point: DungeonSavePoint): Component =
        if (point.kind == DungeonSaveKind.AUTO) text("saves.dialog.auto-label", "<#c4a7e7>Авто · <time> ›", "name" to plain(Component.text(point.name)), "time" to plain(Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt)))))
        else text("saves.dialog.manual-label", "<#c4a7e7><name> ›", "name" to plain(Component.text(point.name)))

    private fun pointTooltip(point: DungeonSavePoint): Component =
        text("saves.dialog.point-tooltip", "<#e8dfd2><kind> · <time><newline><#e8dfd2>Координаты: <coords>",
            "kind" to text(if (point.kind == DungeonSaveKind.MANUAL) "saves.dialog.manual" else "saves.dialog.auto", if (point.kind == DungeonSaveKind.MANUAL) "ручная" else "авто"),
            "time" to plain(Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt)))),
            "coords" to locationText(point.location),
        )

    private fun locationText(location: Location): Component = plain(Component.text("${location.blockX}, ${location.blockY}, ${location.blockZ}"))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component = plain(dungeon.text(key, fallback, *values))
    private fun plain(value: Component): Component = value.decoration(TextDecoration.ITALIC, false)
    private fun action(id: String, labelKey: String, labelFallback: String, tooltipKey: String, tooltipFallback: String, close: Boolean = false, onClick: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), text(labelKey, labelFallback), text(tooltipKey, tooltipFallback), width = 230, closeDialogBeforeAction = close, onClick = onClick)
    private fun pointButton(id: String, label: Component, tooltip: Component, onClick: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, width = 230, onClick = { onClick() })
    private fun back(action: () -> Unit) = PaperDialogButton(PaperDialogActionId.of("back"), text("saves.dialog.back-label", "<#e8dfd2>‹ Назад"), width = 200, onClick = { action() })
}
