package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
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
    private val show: (Player, PaperDialogScreen) -> Unit = { player, screen ->
        val close = PaperDialogButton(PaperDialogActionId.of("close"), dungeon.text("saves.dialog.close-label", "<#aaa49a>Закрыть"), width = 200, closeDialogBeforeAction = true) { }
        // A root Close is already the footer, so never move a second Close into the grid.
        val prepared = if (screen.exitButton?.id?.value == "close" && !MenuEscapeBehavior.goesBack(player)) screen.copy(exitButton = null) else screen
        ArcMenus.openDialog(player, prepared, close)
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
            else -> text("panel.finished", "<#aaa49a>Прохождение остановлено · можно выйти")
        }
        val body = mutableListOf(
            PaperDialogBody(text("panel.intro", "<#f4bd6a><name>", "name" to dungeonDisplayName(visit)), 468),
        )
        if (visit.instanced) body += PaperDialogBody(state, 468)
        val stats = buildList {
            visit.stats?.level?.takeIf { it > 0 }?.let { add(text("panel.level", "<#aaa49a>Уровень: <#e8dfd2><value>", "value" to Component.text(it))) }
            visit.stats?.difficulty?.let { add(text("panel.difficulty", "<#aaa49a>Сложность: <#e8dfd2><value>", "value" to dungeonDifficulty(dungeon, it))) }
            visit.stats?.playerCount?.let { add(text("panel.party", "<#aaa49a>Участников: <#e8dfd2><value>", "value" to Component.text(it))) }
        }
        if (stats.isNotEmpty()) body += PaperDialogBody(stats.reduce { a, b -> a.append(Component.text(" · ")).append(b) }, 468)
        readDungeonCrystals(player)?.let { body += PaperDialogBody(text("panel.crystals", "<#aaa49a>Ваши кристаллы: <#c7a0e8>💎 <value>", "value" to Component.text(it)), 468) }
        body += PaperDialogBody(availability(blocked), 468)
        if (view.saves != null) body += PaperDialogBody(saveCounts(view.saves), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.panel", title = text("panel.title", "<#f4bd6a>Панель данжа"), body = body,
            buttons = listOfNotNull(
                if (visit.waiting) action("start", "panel.start-label", "<#9bd48d>Начать поход", "panel.start-tooltip", "Запустить прохождение для собранной группы", close = true) {
                    dungeon.panelAction(player, view, "start")
                } else null,
                action("quit", "panel.quit-label", "<#d7b486>Выйти из данжа", "panel.quit-tooltip", "Покинуть данж штатным способом", close = true) { dungeon.panelAction(player, view, "quit") }.let { if (visit.instanced) it else it.copy(tooltip = text("panel.quit-open-tooltip", "Вызвать выход ко спавну. Если появился портал, войдите в него.")) },
                action("saves", "panel.saves-label", "<#d7b486>Сохранения ›", "panel.saves-tooltip", "Ваши ручные точки, автосохранения и место прошлого выхода") { open(player) },
                action("entry", "panel.entry-label", "<#d7b486>К началу данжа", "panel.entry-tooltip", "Обычный портал к началу данжа", close = view.saves?.entry != null) {
                    if (view.saves?.entry != null) dungeon.travel(player, view.saves, "entry") else panel(player, text("panel.entry-unavailable", "<#aaa49a>Безопасный переход ко входу сейчас недоступен. Для выхода используйте кнопку выше."))
                }.let { if (view.saves?.entry != null) it else it.copy(label = text("panel.entry-disabled", "<#aaa49a>К началу · недоступно")) },
                action("shop", "panel.shop-label", "<#d7b486>Припасы ›", "panel.shop-tooltip", "Еда, стрелы и полезные предметы за кристаллы") { shop(player) },
                partyButton(player),
                action("guide", "panel.guide-label", "<#86dcf1>Гайд ›", "panel.guide-tooltip", "Читальная справка о данжах") {
                    if (!HelpCenterModule.openDungeonsGuide(player) { panel(player) }) {
                        panel(player, text("panel.guide-unavailable", "<#d7b486>Гайд сейчас недоступен. Закройте панель и попробуйте позже."))
                    }
                },
                action("about", "panel.about-label", "<#86dcf1>О данже ›", "panel.about-tooltip", "Описание и подсказка этого данжа") { about(player) },
                mainMenu(player),
            ),
            exitButton = close(), columns = 2,
        ))
    }

    internal fun open(player: Player, feedback: Component? = null) {
        val view = dungeon.view(player) ?: run {
            val panel = dungeon.panelView(player)
            if (panel == null) unavailable(player) else {
                show(player, PaperDialogScreen(id = "dungeon.saves.unavailable", title = text("saves.dialog.title", "<#f4bd6a>Сохранения"),
                    body = listOf(PaperDialogBody(availability(dungeon.saveBlockReason(player, null)), 468)),
                    buttons = listOf(autosaveSettingsButton(player), guide(player) { open(player) }), exitButton = back { panel(player) }))
            }
            return
        }
        val blocked = dungeon.saveBlockReason(player, view)
        val body = mutableListOf(
            PaperDialogBody(text("saves.dialog.body", "<#e8dfd2>Ваши места в этом данже. Сохраняется только позиция: добыча и монстры не откатываются."), 468),
            PaperDialogBody(dungeon.autosaveDescription(player), 468),
            PaperDialogBody(saveCounts(view), 468),
            PaperDialogBody(availability(blocked), 468),
        )
        if (view.points.none { it.kind == DungeonSaveKind.MANUAL }) body += PaperDialogBody(text("saves.dialog.empty", "<#aaa49a>Ручных точек пока нет. Сохраните удобное место для возвращения."), 468)
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.saves", title = text("saves.dialog.title", "<#f4bd6a>Сохранения"), body = body,
            buttons = listOf(
                action("save", "saves.dialog.save-label", "<#9bd48d>Сохранить здесь", "saves.dialog.save-tooltip", "Запомнить текущую позицию") {
                    if (blocked == null) saveForm(player, view) else open(player)
                }.let { if (blocked == null) it else it.copy(label = text("saves.dialog.save-disabled", "<#aaa49a>Сохранение недоступно"), tooltip = blocked) },
                autosaveSettingsButton(player),
            ) + listOfNotNull(view.exit?.let { locationButton(player, "exit", "saves.dialog.exit-label", "<#d7b486>Место прошлого выхода", "saves.dialog.exit-tooltip", it, view) }) +
                view.points.mapIndexed { index, point -> pointButton("point_$index", pointLabel(point), pointTooltip(point)) { detail(player, point, view) } },
            exitButton = back { panel(player) }, columns = 2,
        ))
    }

    private fun unavailable(player: Player): Unit = show(player, PaperDialogScreen(
        id = "dungeon.panel.unavailable", title = text("panel.title", "<#f4bd6a>Панель данжа"),
        body = listOf(PaperDialogBody(text("panel.outside", "<#e8dfd2>Подготовка к походу<newline><#aaa49a>Почитайте гайд или выберите данж. После входа здесь появится панель прохождения.<newline><#d7b486>/данж тп <#aaa49a>— к порталам · <#d7b486>/данж список <#aaa49a>— выбор данжа"), 468)),
        buttons = listOf(
            guide(player) { unavailable(player) },
            action("portals", "panel.portals-label", "<#d7b486>К порталам", "panel.portals-tooltip", "Перейти к порталам данжей в гильдии", close = true) { dungeon.action(player, "tp") },
            action("list", "panel.list-label", "<#d7b486>Выбрать данж", "panel.list-tooltip", "Открыть список данжей EliteMobs", close = true) { dungeon.action(player, "list") },
            partyButton(player),
            mainMenu(player),
        ), exitButton = close(), columns = 2,
    ))

    private fun mainMenu(player: Player) = action("main", "panel.main-label", "<#aaa49a>Главное меню ›", "panel.main-tooltip", "Открыть главное меню сервера") { dungeon.action(player, "main") }

    private fun autosaveSettingsButton(player: Player) = action("autosaves", "saves.settings.label", "<#d7b486>Автосохранение ›", "saves.settings.tooltip", "Выбрать интервал или отключить автоматические точки") { autosaveSettings(player) }

    private fun autosaveSettings(player: Player, feedback: Component? = null) {
        val seconds = dungeon.autosaveSeconds(player)
        val body = mutableListOf(
            PaperDialogBody(text("saves.settings.body", "<#e8dfd2>Выберите интервал между автоматическими точками.<newline><#aaa49a>Личная настройка сохраняется после выхода и действует во всех данжах этого сервера. Ручные точки и возврат к месту выхода не меняются."), 468),
            PaperDialogBody(text("saves.settings.current", "<#aaa49a>Сейчас: <value>", "value" to autosaveIntervalLabel(seconds)), 468),
            PaperDialogBody(dungeon.autosaveDescription(player), 468),
        )
        if (!dungeon.canConfigureAutosaves()) body += PaperDialogBody(text("saves.settings.unavailable", "<#aaa49a>Настройка автосохранения сейчас недоступна."), 468)
        feedback?.let { body += PaperDialogBody(it, 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.autosaves", title = text("saves.settings.title", "<#f4bd6a>Автосохранение"), body = body,
            buttons = if (dungeon.canConfigureAutosaves()) DUNGEON_AUTOSAVE_INTERVALS.map { interval ->
                PaperDialogButton(PaperDialogActionId.of("auto_$interval"),
                    text(if (seconds == interval) "saves.settings.selected" else "saves.settings.option",
                        if (seconds == interval) "<#9bd48d>✔ <value>" else "<#d7b486><value>", "value" to autosaveIntervalLabel(interval)),
                    tooltip = text("saves.settings.option-tooltip", "Применить только к вашим автоматическим точкам"), width = 230,
                    onClick = { val result = dungeon.setAutosaveSeconds(player, interval); autosaveSettings(player, result.message.takeUnless { result.success }) })
            } else listOf(guide(player) { autosaveSettings(player) }),
            exitButton = back { open(player) }, columns = 2,
        ))
    }

    private fun autosaveIntervalLabel(seconds: Int): Component = when {
        seconds == 0 -> text("saves.settings.off", "Выключено")
        seconds % 60 == 0 -> text("saves.settings.minutes", "<value> мин", "value" to Component.text(seconds / 60))
        else -> text("saves.settings.seconds", "<value> с", "value" to Component.text(seconds))
    }

    private fun partyButton(player: Player) = action("party", "panel.party-label", "<#d7b486>Группа ›", "panel.party-tooltip", "Как собрать пати и управлять группой EliteMobs") { party(player) }

    private fun party(player: Player) {
        val available = dungeon.partiesAvailable()
        show(player, PaperDialogScreen(
            id = "dungeon.party", title = text("party.title", "<#f4bd6a>Группа"),
            body = listOf(
                PaperDialogBody(text("party.body", "<#e8dfd2>Соберите пати перед входом в данж.<newline><#aaa49a>В управлении группой можно создать пати, пригласить игроков и посмотреть участников. Приглашённый игрок должен принять приглашение."), 468),
                PaperDialogBody(text("party.tips", "<#e8dfd2>Группа помогает проходить данжи вместе: рядом засчитывается общий прогресс заданий, а за групповую добычу можно голосовать.<newline><#aaa49a>Перед входом договоритесь о данже и сложности. Сохранения позиций остаются личными."), 468),
            ) + if (available) emptyList() else listOf(PaperDialogBody(text("party.unavailable", "<#aaa49a>Группы EliteMobs на этом сервере пока недоступны."), 468)),
            buttons = listOfNotNull(
                if (available) action("manage_party", "party.manage-label", "<#9bd48d>Управление группой", "party.manage-tooltip", "Открыть меню группы EliteMobs", close = true) { dungeon.action(player, "party") } else null,
                guide(player) { party(player) },
            ), exitButton = back { panel(player) }, columns = 2,
        ))
    }

    private fun saveCounts(view: DungeonSaveView): Component = text("saves.dialog.counts", "<#aaa49a>Ручные: <#e8dfd2><manual>/5 <#aaa49a>· Авто: <#e8dfd2><auto>/3",
        "manual" to Component.text(view.points.count { it.kind == DungeonSaveKind.MANUAL }), "auto" to Component.text(view.points.count { it.kind == DungeonSaveKind.AUTO }))

    private fun availability(reason: Component?): Component = reason?.let {
        text("saves.dialog.unavailable", "<#d7b486>Сохраниться здесь нельзя<newline><reason>", "reason" to it)
    } ?: text("saves.dialog.ready", "<#9bd48d>✔ Здесь можно сохраниться")

    private fun guide(player: Player, returnTo: () -> Unit) = action("guide", "panel.guide-label", "<#86dcf1>Гайд ›", "panel.guide-tooltip", "Правила и советы по прохождению данжей") {
        if (!HelpCenterModule.openDungeonsGuide(player, returnTo)) player.sendMessage(text("panel.guide-unavailable", "<#d7b486>Гайд сейчас недоступен. Попробуйте позже."))
    }

    private fun close() = action("close", "saves.dialog.close-label", "<#aaa49a>Закрыть", "saves.dialog.close-tooltip", "Вернуться в игру", close = true) { }.copy(width = 200)

    private fun about(player: Player) {
        val visit = dungeon.panelView(player)?.visit ?: run { unavailable(player); return }
        show(player, PaperDialogScreen(
            id = "dungeon.about",
            title = text("panel.about-title", "<#f4bd6a>О данже"),
            body = listOf(
                PaperDialogBody(dungeonDisplayName(visit), 468),
                PaperDialogBody(dungeon.context(player), 468),
            ),
            buttons = listOf(guide(player) { about(player) }),
            exitButton = back { panel(player) },
        ))
    }

    private fun shop(player: Player, feedback: Component? = null) {
        val view = dungeon.panelView(player) ?: run { unavailable(player); return }
        val body = mutableListOf(
            PaperDialogBody(text("shop.body", "<#e8dfd2>Пополните запасы для похода. Оплата кристаллами EliteMobs; перед покупкой будет подтверждение."), 468),
            PaperDialogBody(balance(player), 468),
        )
        feedback?.let { body += PaperDialogBody(it, 468) }
        show(player, PaperDialogScreen(id = "dungeon.shop", title = text("shop.title", "<#f4bd6a>Припасы"), body = body,
            buttons = dungeon.supplies.list().map { offer ->
                val quote = dungeon.supplies.quote(player, offer)
                pointButton("supply_${offer.id}", text("shop.offer-label", "<#d7b486><name> ×<amount> <#aaa49a>· 💎 <price>",
                    "name" to supplyName(offer), "amount" to Component.text(offer.amount), "price" to price(offer.price)),
                    if (quote == null) text("shop.item-unavailable", "<#aaa49a>Этот предмет сейчас недоступен. Кристаллы не списываются.") else supplyDescription(offer)) {
                    if (quote == null) shop(player, text("shop.item-unavailable", "<#aaa49a>Этот предмет сейчас недоступен. Кристаллы не списываются."))
                    else confirmPurchase(player, quote, view)
                }
            }.ifEmpty { listOf(guide(player) { shop(player) }) }, exitButton = back { panel(player) }, columns = 2,
        ))
    }

    private fun confirmPurchase(player: Player, quote: SupplyQuote, expected: DungeonPanelView, feedback: Component? = null) {
        val offer = quote.offer
        val body = mutableListOf(
            PaperDialogBody(text("shop.confirm-body", "<#e8dfd2><name> ×<amount><newline><#aaa49a>Стоимость: <#c7a0e8>💎 <price>", "name" to supplyName(offer),
                "amount" to Component.text(offer.amount), "price" to price(offer.price)), 468),
            PaperDialogBody(supplyDescription(offer), 468), PaperDialogBody(balance(player), 468),
        )
        feedback?.let { body += PaperDialogBody(it, 468) }
        show(player, PaperDialogScreen(id = "dungeon.shop.confirm", title = text("shop.confirm-title", "<#f4bd6a>Купить припасы?"), body = body,
            buttons = listOf(action("buy", "shop.buy-label", "<#9bd48d>Купить", "shop.buy-tooltip", "Купить ровно показанный набор за указанную цену") {
                val result = dungeon.supplies.buy(player, quote, expected)
                val message = supplyResult(result)
                if (result.success) shop(player, message) else confirmPurchase(player, quote, expected, message)
            }), exitButton = back { shop(player) },
        ))
    }

    private fun supplyResult(result: SupplyResult): Component = when (result) {
        SupplyResult.BOUGHT -> text("shop.bought", "<#9bd48d>✔ Припасы куплены и добавлены в инвентарь.")
        SupplyResult.CHANGED -> text("shop.changed", "<#d7b486>Товар изменился. Вернитесь в магазин и выберите его заново.")
        SupplyResult.OUTSIDE -> text("shop.outside", "<#d7b486>Данж изменился. Откройте /данж заново; покупка не выполнена.")
        SupplyResult.NO_SPACE -> text("shop.no-space", "<#d7b486>Освободите место в инвентаре. Кристаллы не списаны.")
        SupplyResult.NO_MONEY -> text("shop.no-money", "<#d7b486>Не хватает кристаллов. Покупка не выполнена.")
        SupplyResult.ITEM_UNAVAILABLE -> text("shop.item-unavailable", "<#aaa49a>Этот предмет сейчас недоступен. Кристаллы не списываются.")
        SupplyResult.PAYMENT_FAILED -> text("shop.payment-failed", "<#d7b486>Не удалось завершить оплату. Покупка не выполнена.")
    }

    private fun supplyName(offer: SupplyOffer): Component = text("shop.stock.${offer.id}.name", when (offer.id) {
        "beef" -> "Стейки"; "bread" -> "Хлеб"; "arrows" -> "Стрелы"; "healing" -> "Зелье лечения"; "merchant" -> "Свиток торговца"; else -> offer.id
    })
    private fun supplyDescription(offer: SupplyOffer): Component = text("shop.stock.${offer.id}.description", when (offer.id) {
        "beef" -> "<#e8dfd2>Обычные стейки для восстановления сытости."
        "bread" -> "<#e8dfd2>Обычный хлеб для восстановления сытости."
        "arrows" -> "<#e8dfd2>Обычные стрелы для лука и арбалета."
        "healing" -> "<#e8dfd2>Восстанавливает 2 сердца после питья.<newline><#aaa49a>Зажмите ПКМ, чтобы выпить. После использования останется пустая бутылочка."
        "merchant" -> "<#e8dfd2>Настоящий свиток EliteMobs. ПКМ вызывает странствующего торговца и расходует свиток.<newline><#aaa49a>Между вызовами — 60 секунд."
        else -> ""
    })
    private fun balance(player: Player): Component = text("shop.balance", "<#aaa49a>Ваши кристаллы: <#c7a0e8>💎 <value>",
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
            title = text("saves.dialog.save-title", "<#f4bd6a>Сохранить точку"),
            body = body,
            inputs = listOf(PaperDialogTextInput(nameInput, text("saves.dialog.name", "<#aaa49a>Имя точки"), initial = name.take(32), maxLength = 32, width = 300)),
            buttons = listOf(
                action("save", "saves.dialog.confirm-save-label", "<#9bd48d>Сохранить", "saves.dialog.confirm-save-tooltip", "<#f4bd6a>Сохранить точку") { context ->
                    val entered = context.text(nameInput)?.trim().orEmpty()
                    if (blocked != null) saveForm(player, expected, name = entered) else {
                        val result = dungeon.save(player, entered, expected)
                        if (result.success) open(player, result.message) else saveForm(player, expected, result.message, entered)
                    }
                }.let { if (blocked == null) it else it.copy(label = text("saves.dialog.save-disabled", "<#aaa49a>Сохранение недоступно"), tooltip = blocked) },
            ),
            exitButton = back { open(player) },
            columns = 1,
        ))
    }

    private fun detail(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.point",
            title = text("saves.dialog.point-title", "<#f4bd6a>Сохранение"),
            body = listOf(PaperDialogBody(plain(Component.text(point.name)), 468), PaperDialogBody(pointTooltip(point), 468)),
            buttons = listOf(
                action("travel", "saves.dialog.travel-label", "<#9bd48d>Перейти", "saves.dialog.travel-tooltip", "Телепортироваться к точке", close = true) { dungeon.travel(player, expected, point.id) },
                action("remove", "saves.dialog.remove-label", "<#d7b486>Удалить", "saves.dialog.remove-tooltip", "Удалить эту точку") { confirmRemove(player, point, expected) },
            ),
            exitButton = back { open(player) },
            columns = 2,
        ))
    }

    private fun confirmRemove(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.remove",
            title = text("saves.dialog.remove-title", "<#f4bd6a>Удалить точку?"),
            body = listOf(PaperDialogBody(text("saves.dialog.remove-body", "<#e8dfd2>Точка «<name>» будет удалена.", "name" to plain(Component.text(point.name))), 468)),
            buttons = listOf(
                action("confirm", "saves.dialog.confirm-remove-label", "<#d7b486>Удалить", "saves.dialog.confirm-remove-tooltip", "Удалить точку") {
                    val result = dungeon.remove(player, point, expected)
                    open(player, result.message)
                },
            ),
            exitButton = back { detail(player, point, expected) },
            columns = 1,
        ))
    }

    private fun locationButton(player: Player, id: String, labelKey: String, labelFallback: String, tooltipKey: String, location: Location, expected: DungeonSaveView) =
        PaperDialogButton(PaperDialogActionId.of(id), text(labelKey, labelFallback), text(tooltipKey, "Координаты: <coords>", "coords" to locationText(location)), width = 230, closeDialogBeforeAction = true) { dungeon.travel(player, expected, id) }

    private fun pointLabel(point: DungeonSavePoint): Component =
        if (point.kind == DungeonSaveKind.AUTO) text("saves.dialog.auto-label", "<#d7b486>Авто · <time>", "name" to plain(Component.text(point.name)), "time" to plain(Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt)))))
        else text("saves.dialog.manual-label", "<#d7b486><name>", "name" to plain(Component.text(point.name)))

    private fun pointTooltip(point: DungeonSavePoint): Component =
        text("saves.dialog.point-tooltip", "<#e8dfd2><kind> · <time><newline><#aaa49a>Координаты: <coords>",
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
    private fun back(action: () -> Unit) = PaperDialogButton(PaperDialogActionId.of("back"), text("saves.dialog.back-label", "<#aaa49a>‹ Назад"), width = 200, onClick = { action() })
}
