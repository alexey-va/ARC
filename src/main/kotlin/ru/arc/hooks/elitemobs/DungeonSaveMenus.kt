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

internal class DungeonSaveMenus(
    private val dungeon: EMDungeonQol,
    private val crystals: (Player) -> String? = ::readDungeonCrystals,
    private val readQuests: (Player) -> List<DungeonQuestInfo>? = ::readDungeonQuests,
    private val show: (Player, PaperDialogScreen, (() -> Unit)?) -> Unit = { player, screen, reopen ->
        val close = PaperDialogButton(PaperDialogActionId.of("close"), dungeon.text("saves.dialog.close-label", "<#e8dfd2>Закрыть"), width = 200, closeDialogBeforeAction = true) { }
        // A root Close is already the footer, so never move a second Close into the grid.
        val prepared = if (screen.exitButton?.id?.value == "close" && !MenuEscapeBehavior.goesBack(player)) screen.copy(exitButton = null) else screen
        ArcMenus.openDialog(player, prepared, close, reopen = reopen)
    },
) {
    private val nameInput = PaperDialogInputId.of("name")
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

    internal fun panel(player: Player, feedback: Component? = null) {
        val view = dungeon.panelView(player) ?: run { unavailable(player); return }
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
                action("quit", "panel.quit-label", "<#d7b486>Выйти из данжа", "panel.quit-tooltip", "Покинуть данж штатным способом", close = true) { dungeon.panelAction(player, view, "quit") }.let { if (visit.instanced) it else it.copy(tooltip = text("panel.quit-open-tooltip", "Вызвать выход ко спавну. Если появился портал, войдите в него.")) },
                action("saves", "panel.saves-label", "<#c4a7e7>Сохранения ›", "panel.saves-tooltip", "Ваши ручные точки, автосохранения и место прошлого выхода") { open(player) },
                action("entry", "panel.entry-label", "<#92bed8>К началу данжа ›", "panel.entry-tooltip", "Обычный портал к началу данжа", close = view.saves?.entry != null) {
                    if (view.saves?.entry != null) dungeon.travel(player, view.saves, "entry") else panel(player, text("panel.entry-unavailable", "<#e8dfd2>Безопасный переход ко входу сейчас недоступен. Для выхода используйте кнопку выше."))
                }.let { if (view.saves?.entry != null) it else it.copy(label = text("panel.entry-disabled", "<#e8dfd2>[Недоступно] К началу данжа")) },
                action("shop", "panel.shop-label", "<#f4d87a>Припасы ›", "panel.shop-tooltip", "Припасы и кейсы EliteMobs за кристаллы") { shop(player) },
                scoreboardButton(player),
                shops(player),
                lostLoot(player),
                partyButton(player),
                action("quests", "quests.label", "<#c4a7e7>Задания ›", "quests.tooltip", "Принятые задания EliteMobs и их текущий прогресс") { quests(player) },
                action("guide", "panel.guide-label", "<#86dcf1>Гайд ›", "panel.guide-tooltip", "Читальная справка о данжах") {
                    if (!HelpCenterModule.openDungeonsGuide(player) { panel(player) }) {
                        panel(player, text("panel.guide-unavailable", "<#d7b486>Гайд сейчас недоступен. Закройте панель и попробуйте позже."))
                    }
                },
                action("about", "panel.about-label", "<#86dcf1>О данже ›", "panel.about-tooltip", "Описание и подсказка этого данжа") { about(player) },
            ),
            exitButton = if (MenuEscapeBehavior.goesBack(player)) back {} else close(), columns = 2,
        )) { panel(player) }
    }

    internal fun quests(player: Player, requestedPage: Int = 0) {
        val entries = readQuests(player)
        val page = requestedPage.coerceIn(0, (entries.orEmpty().size - 1).coerceAtLeast(0))
        val quest = entries?.getOrNull(page)
        val body = mutableListOf(PaperDialogBody(text("quests.intro", "<#f2eee8>Ваши принятые задания EliteMobs. Отслеживаемое показано первым; задания могут относиться к другим локациям."), 468))
        if (quest == null) {
            body += PaperDialogBody(if (entries == null) text("quests.unavailable", "<#d7b486>Данные заданий ещё загружаются. Попробуйте обновить страницу.")
                else text("quests.empty", "<#f2eee8>Принятых заданий пока нет. Поговорите с персонажами, которые предлагают задания."), 468)
        } else {
            body += PaperDialogBody(plain(readableQuestText(TextUtil.legacy(quest.name))), 468)
            body += PaperDialogBody(if (quest.tracked) text("quests.tracked", "<#9bd48d>Отслеживается") else text("quests.accepted", "<#f2eee8>Принято"), 468)
            if (quest.complete) body += PaperDialogBody(text("quests.complete", "<#9bd48d>Цели выполнены — задание готово к сдаче."), 468)
            quest.lines.forEach { body += PaperDialogBody(plain(readableQuestText(TextUtil.legacy(it))), 468) }
            body += PaperDialogBody(text("quests.page", "<#f2eee8>Задание <current> из <total>", "current" to Component.text(page + 1), "total" to Component.text(entries.size)), 468)
        }
        show(player, PaperDialogScreen(
            id = "dungeon.quests", title = text("quests.title", "<#c4a7e7>Мои задания"), body = body,
            buttons = listOfNotNull(
                if (page > 0) action("previous", "quests.previous", "<#92bed8>‹ Предыдущая страница", "quests.previous-tooltip", "Показать предыдущее задание") { quests(player, page - 1) } else null,
                if (page + 1 < entries.orEmpty().size) action("next", "quests.next", "<#92bed8>Следующая страница ›", "quests.next-tooltip", "Показать следующее задание") { quests(player, page + 1) } else null,
                action("refresh", "quests.refresh", "<#92bed8>Обновить", "quests.refresh-tooltip", "Прочитать текущий прогресс из EliteMobs") { quests(player, page) },
            ), exitButton = back { panel(player) }, columns = 2,
        )) { quests(player, page) }
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
            scoreboardButton(player),
            shops(player),
            lostLoot(player),
            guide(player) { unavailable(player) },
            action("portals", "panel.portals-label", "<#92bed8>К порталам ›", "panel.portals-tooltip", "Перейти к порталам данжей в гильдии", close = true) { dungeon.action(player, "tp") },
            action("list", "panel.list-label", "<#ffb277>Выбрать данж ›", "panel.list-tooltip", "Открыть список данжей EliteMobs", close = true) { dungeon.action(player, "list") },
            partyButton(player),
        ), exitButton = if (MenuEscapeBehavior.goesBack(player)) back {} else close(), columns = 2,
        )) { unavailable(player) }
    }

    private fun crystalBalance(player: Player) = PaperDialogBody(crystals(player)?.let {
        text("panel.crystals", "<#e8dfd2>Ваши кристаллы: <#c7a0e8>💎 <value>", "value" to Component.text(it))
    } ?: text("panel.crystals-unavailable", "<#e8dfd2>Кристаллы: баланс сейчас недоступен"), 468)

    private fun lostLoot(player: Player) = action("lost_loot", "lost-loot.label", "<#c4a7e7>Потерянная добыча ›", "lost-loot.tooltip", "Забрать или продать сохранённый лут со всех данжей", close = true) {
        ru.arc.eliteloot.LostLootModule.open(player)
    }

    private fun shops(player: Player) = action("shops", "panel.shops-label", "<#92bed8>К магазинам ›", "panel.shops-tooltip", "Перейти к торговцам данжей", close = true) { dungeon.action(player, "shops") }

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

    private fun party(player: Player) {
        val available = dungeon.partiesAvailable()
        show(player, PaperDialogScreen(
            id = "dungeon.party", title = text("party.title", "<#e5ba73>Группа"),
            body = listOf(
                PaperDialogBody(text("party.body", "<#e8dfd2>Соберите пати перед входом в данж.<newline><#e8dfd2>В управлении группой можно создать пати, пригласить игроков и посмотреть участников. Приглашённый игрок должен принять приглашение."), 468),
                PaperDialogBody(text("party.tips", "<#e8dfd2>Группа помогает проходить данжи вместе: рядом засчитывается общий прогресс заданий, а за групповую добычу можно голосовать.<newline><#e8dfd2>Перед входом договоритесь о данже и сложности. Сохранения позиций остаются личными."), 468),
            ) + if (available) emptyList() else listOf(PaperDialogBody(text("party.unavailable", "<#e8dfd2>Группы EliteMobs на этом сервере пока недоступны."), 468)),
            buttons = listOfNotNull(
                if (available) action("manage_party", "party.manage-label", "<#c4a7e7>Управление группой ›", "party.manage-tooltip", "Открыть меню группы EliteMobs", close = true) { dungeon.action(player, "party") } else null,
                guide(player) { party(player) },
            ), exitButton = back { panel(player) }, columns = 2,
        )) { party(player) }
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
            buttons = dungeon.supplies.list().map { offer ->
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
            }.ifEmpty { listOf(guide(player) { shop(player) }) }, exitButton = back { panel(player) }, columns = 2,
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
