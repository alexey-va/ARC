package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class DungeonSaveMenusTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "supply button buys directly and only successful purchases play a sound" {
        for (outcome in listOf(SupplyResult.BOUGHT, SupplyResult.NO_MONEY)) {
            val player = mockk<org.bukkit.entity.Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            val dungeon = mockk<EMDungeonQol>(relaxed = true)
            val view = DungeonPanelView(UUID.randomUUID(), DungeonVisit("run"), null)
            every { dungeon.panelView(player) } returns view
            every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(firstArg<String>()) }
            val offer = SupplyOffer("food", org.bukkit.Material.BREAD, 1, 1.0)
            val quote = SupplyQuote(offer, org.bukkit.inventory.ItemStack(org.bukkit.Material.BREAD), player.uniqueId)
            val supplies = mockk<DungeonSupplyShop>()
            every { dungeon.supplies } returns supplies
            every { supplies.list() } returns listOf(offer)
            every { supplies.quote(player, offer) } returns quote
            every { supplies.buy(player, quote, view) } returns outcome
            val shown = mutableListOf<PaperDialogScreen>()
            val menus = DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }
            menus.shop(player)
            shown.last().buttons.map { it.id.value }.take(2) shouldBe listOf("skill_boosts", "supply_food")
            shown.last().buttons.single { it.id.value == "supply_food" }.onClick.handle(mockk())
            verify(exactly = 1) { supplies.buy(player, quote, view) }
            shown.map { it.id }.distinct().size shouldBe 1
            verify(exactly = if (outcome.success) 1 else 0) {
                player.playSound(any<org.bukkit.Location>(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.65f, 1.2f)
            }
        }
    }

    "dungeon panel keeps primary actions first and utility actions last" {
        val player = paper.addPlayer("panel-order")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("panel-order-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()

        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)

        shown.single().buttons.map { it.id.value } shouldBe listOf(
            "quests", "classes", "party", "shop", "lost_loot", "saves", "entry", "guide", "about", "scoreboard", "quit",
        )
        shown.single().buttons.none { it.id.value == "shops" || it.id.value == "skill_boosts" } shouldBe true
    }

    "quest overview and detail distinguish completion from tracking" {
        val player = paper.addPlayer("quest-viewer")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            ru.arc.util.TextUtil.mm(secondArg<String>(),
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(values.map { (name, value) ->
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(name, value)
                }))
        }
        val ids = List(4) { UUID.randomUUID() }
        var entries: List<DungeonQuestInfo>? = listOf(
            DungeonQuestInfo(ids[0], "В процессе и отслеживается", true, false, true, listOf("Скелеты 3 / 10"),
                listOf(DungeonQuestGoalState.ACTIVE)),
            DungeonQuestInfo(ids[1], "В процессе без отслеживания", false, false, true, listOf("Вернитесь к кузнецу"),
                listOf(DungeonQuestGoalState.NEXT)),
            DungeonQuestInfo(ids[2], "Готово и отслеживается", true, true, true, listOf("Сдайте награду"),
                listOf(DungeonQuestGoalState.COMPLETE)),
            DungeonQuestInfo(ids[3], "Готово без отслеживания", false, true, true, listOf("Сдайте награду"),
                listOf(DungeonQuestGoalState.NEXT)),
        )
        val shown = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, readQuests = { entries }) { _, screen, _ -> shown += screen }
        menus.quests(player)
        shown.last().id shouldBe "dungeon.quests"
        shown.last().buttons.map { it.id.value } shouldBe listOf("quest_0", "quest_1", "quest_2", "quest_3", "refresh")
        val plainText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        shown.last().buttons.take(4).map { plainText.serialize(it.label) } shouldBe
            entries!!.map { "${if (it.tracked) "✔" else "○"} ${it.name} ›" }
        val bodyText = shown.last().body.flatMap { body ->
            listOf(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(body.text))
        }
        listOf("В процессе · Отслеживается", "В процессе · Не отслеживается",
            "Готово к сдаче · Отслеживается", "Готово к сдаче · Не отслеживается").forEachIndexed { index, status ->
            val entry = entries!![index]
            bodyText.any { it.contains("${if (entry.tracked) "✔" else "○"} ${entry.name}\n$status") } shouldBe true
        }
        shown.last().body.flatMap { body ->
            val points = mutableListOf<Int>()
            fun collect(component: Component) {
                (component as? net.kyori.adventure.text.TextComponent)?.content()?.codePoints()?.forEach(points::add)
                component.children().forEach(::collect)
            }
            collect(body.text)
            points
        }.none { it in 0xE540..0xE59E } shouldBe true

        shown.last().buttons.first { it.id.value == "quest_0" }.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.quest"
        shown.last().body.any { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text) == "В процессе · Отслеживается" } shouldBe true
        shown.last().body.any { plainText.serialize(it.text) == "○ Скелеты 3 / 10" } shouldBe true
        shown.last().body.single { plainText.serialize(it.text) == "○ Скелеты 3 / 10" }.width shouldBe 320

        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().buttons.first { it.id.value == "quest_1" }.onClick.handle(mockk())
        shown.last().body.any { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text) == "В процессе · Не отслеживается" } shouldBe true
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().buttons.first { it.id.value == "quest_2" }.onClick.handle(mockk())
        shown.last().body.any { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text) == "Готово к сдаче · Отслеживается" } shouldBe true
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().buttons.first { it.id.value == "quest_3" }.onClick.handle(mockk())
        shown.last().body.any { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text) == "Готово к сдаче · Не отслеживается" } shouldBe true

        entries = entries!!.map { it.copy(tracked = it.id == ids[1]) }
        menus.quests(player)
        shown.last().buttons.take(4).map { plainText.serialize(it.label).first() } shouldBe listOf('○', '✔', '○', '○')
        entries = entries!!.map { it.copy(tracked = false) }
        menus.quests(player)
        shown.last().buttons.take(4).map { plainText.serialize(it.label).first() } shouldBe List(4) { '○' }

        entries = emptyList()
        menus.quests(player)
        shown.last().buttons.map { it.id.value } shouldBe listOf("refresh")
        shown.last().exitButton!!.id.value shouldBe "back"
        entries = null
        menus.quests(player)
        plainText.serialize(shown.last().body.last().text) shouldBe "Данные заданий ещё загружаются. Попробуйте обновить страницу."
    }

    "opening saves outside a resumable run returns to the panel explanation" {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.view(player) } returns null
        every { dungeon.panelView(player) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } returns Component.text("changed")
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.open(player)
        shown.single().id shouldBe "dungeon.panel.unavailable"
        shown.single().buttons.map { it.id.value } shouldBe listOf("return", "lost_loot", "guide", "portals", "list", "classes", "party", "scoreboard")
        shown.single().exitButton!!.id.value shouldBe "back"
        shown.single().exitButton!!.closeDialogBeforeAction shouldBe false
        shown.single().body.map { it.text } shouldBe listOf(
            Component.text("changed").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
            Component.text("changed").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
        )
    }

    "outside menu renders zero, nonzero, and unavailable dungeon crystal balances" {
        val balances = listOf("0", "27", null)
        val observed = mutableListOf<String?>()

        balances.forEach { balance ->
            val player = paper.addPlayer("balance-${observed.size}")
            val dungeon = mockk<EMDungeonQol>(relaxed = true)
            every { dungeon.view(player) } returns null
            every { dungeon.panelView(player) } returns null
            every { dungeon.lastReturn(player) } returns null
            every { dungeon.text(any(), any(), *anyVararg()) } answers {
                Component.text(firstArg<String>())
            }
            val shown = mutableListOf<PaperDialogScreen>()
            DungeonSaveMenus(dungeon, crystals = {
                observed += balance
                balance
            }) { _, screen, _ -> shown += screen }.open(player)

            observed.last() shouldBe balance
            shown.single().body.map { it.text } shouldBe listOf(
                Component.text("panel.outside").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text(if (balance == null) "panel.crystals-unavailable" else "panel.crystals")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
            )
        }
    }

    "unavailable return refreshes the panel and keeps useful actions clickable" {
        val player = paper.addPlayer("outside-actions")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.view(player) } returns null
        every { dungeon.panelView(player) } returns null
        every { dungeon.lastReturn(player) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon, crystals = { "0" }) { _, screen, _ -> shown += screen }.open(player)

        val screen = shown.single()
        screen.buttons.single { it.id.value == "return" }.also {
            it.closeDialogBeforeAction shouldBe false
            it.onClick.handle(mockk())
        }
        verify(exactly = 0) { dungeon.returnToLast(any(), any()) }
        shown.size shouldBe 2
        shown.last().id shouldBe "dungeon.panel.unavailable"
        shown.last().buttons.single { it.id.value == "list" }.also {
            it.closeDialogBeforeAction shouldBe true
            it.onClick.handle(mockk())
        }
        verify { dungeon.action(player, "list") }
    }

    "outside menu enables return and passes the captured departure to the callback" {
        val player = paper.addPlayer("outside-return")
        val world = paper.addSimpleWorld("outside-return-world")
        val departure = DungeonDeparture(Location(world, 4.5, 71.0, -2.5, 180f, 12f), "run", 42L)
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.view(player) } returns null
        every { dungeon.panelView(player) } returns null
        every { dungeon.lastReturn(player) } returns departure
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon, crystals = { "7" }) { _, screen, _ -> shown += screen }.open(player)

        shown.single().buttons.single { it.id.value == "return" }.also {
            it.closeDialogBeforeAction shouldBe true
            it.onClick.handle(mockk())
        }
        verify { dungeon.returnToLast(player, departure) }
    }

    "about page opens safely and returns to the current dungeon panel" {
        val player = paper.addPlayer("about")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("about-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", name = "Пещера"), null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.context(player) } returns Component.text("Подсказка")
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen, _ -> screens += screen }
        menus.panel(player)
        screens.last().buttons.single { it.id.value == "about" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.about"
        screens.last().body.last().text shouldBe Component.text("Подсказка")
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.panel"
    }

    "party section manages the native party directly without a command hop" {
        val player = paper.addPlayer("party")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("party-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val parties = mockk<DungeonParties>()
        every { dungeon.parties } returns parties
        every { parties.view(player) } returns DungeonPartyView(available = true)
        every { parties.create(player) } returns com.magmaguy.elitemobs.parties.PartyOperationResult.SUCCESS
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen, _ -> screens += screen }
        menus.panel(player)
        screens.last().buttons.any { it.id.value == "main" } shouldBe false
        screens.last().buttons.single { it.id.value == "party" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.party"
        screens.last().buttons.single { it.id.value == "create_party" }.onClick.handle(mockk())
        verify { parties.create(player) }
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.panel"
        every { parties.view(player) } returns DungeonPartyView(available = false)
        screens.last().buttons.single { it.id.value == "party" }.onClick.handle(mockk())
        screens.last().buttons.map { it.id.value } shouldBe listOf("guide")
        screens.last().body.last().text shouldBe Component.text("<#d7b486>Группы EliteMobs на этом сервере недоступны или у вас нет доступа.")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "quest detail switches tracking and abandons only after confirmation" {
        val player = paper.addPlayer("quest-actions")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val questId = UUID.randomUUID()
        var entries = listOf(DungeonQuestInfo(questId, "Поход", false, false, true, listOf("Зачистить 2 / 5")))
        val tracked = mutableListOf<Pair<UUID, Boolean>>()
        val abandoned = mutableListOf<UUID>()
        val shown = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(
            dungeon,
            readQuests = { entries },
            changeTracking = { _, id, enabled -> tracked += id to enabled; DungeonQuestTrackingChange.TRACKED },
            abandonQuest = { _, id -> abandoned += id; entries = emptyList(); DungeonQuestAbandonChange.ABANDONED },
        ) { _, screen, _ -> shown += screen }

        menus.quests(player)
        shown.last().buttons.single { it.id.value == "quest_0" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "track" }.onClick.handle(mockk())
        tracked shouldBe listOf(questId to true)
        shown.last().id shouldBe "dungeon.quest"

        shown.last().buttons.single { it.id.value == "abandon" }.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.quest.abandon"
        abandoned shouldBe emptyList()
        shown.last().buttons.single { it.id.value == "confirm_abandon" }.onClick.handle(mockk())
        abandoned shouldBe listOf(questId)
        shown.last().id shouldBe "dungeon.quests"
        shown.last().buttons.map { it.id.value } shouldBe listOf("refresh")
    }

    "class section shows a native progression page and selects an unlocked form" {
        val player = paper.addPlayer("classes")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val classes = mockk<DungeonClassService>()
        val form = dungeonClassForm("warrior", "Воин")
        every { classes.view(player) } returns DungeonClassesView(
            availability = DungeonClassAvailability.READY,
            roots = listOf(form.id),
            forms = mapOf(form.id to form),
        )
        every { classes.select(player, form.id) } returns DungeonClassChange.APPLIED
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, classService = classes) { _, screen, _ -> screens += screen }

        menus.classes(player)
        screens.last().id shouldBe "dungeon.classes"
        val rootButton = screens.last().buttons.single { it.id.value == "class_warrior" }
        rootButton.label shouldBe Component.text("<#c4abff><name> ›")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        rootButton.tooltip shouldBe Component.text("<#e8dfd2><role><newline><newline><#e8dfd2>Открыть ветку класса")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        rootButton.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.classes.warrior"
        screens.last().buttons.single { it.id.value == "class_select_warrior" }.onClick.handle(mockk())

        verify(exactly = 1) { classes.select(player, "warrior") }
        screens.last().id shouldBe "dungeon.classes.warrior"
        screens.last().body.first().text shouldBe Component.text("<#9bd48d>✔ Активирован класс <name>.")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "class selection is muted while EliteMobs locks the run" {
        val player = paper.addPlayer("class-locked")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val classes = mockk<DungeonClassService>(relaxed = true)
        val form = dungeonClassForm("warrior", "Воин")
        every { classes.view(player) } returns DungeonClassesView(
            availability = DungeonClassAvailability.READY,
            runLocked = true,
            roots = listOf(form.id),
            forms = mapOf(form.id to form),
        )
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, classService = classes) { _, screen, _ -> screens += screen }

        menus.classes(player)
        screens.last().buttons.single { it.id.value == "class_warrior" }.onClick.handle(mockk())
        screens.last().buttons.single { it.id.value == "class_select_warrior" }.onClick.handle(mockk())

        verify(exactly = 0) { classes.select(any(), any()) }
    }

    "class detail puts selection before clearly labelled progression choices" {
        val player = paper.addPlayer("class-path")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val classes = mockk<DungeonClassService>()
        val root = dungeonClassForm("paladin", "Паладин").copy(children = listOf("guardian"))
        val child = dungeonClassForm("guardian", "Страж").copy(
            rootId = root.id,
            parentId = root.id,
            requiredLevel = 31,
            unlocked = false,
            blockers = listOf(DungeonClassRequirement("Броня", "12 / 30")),
        )
        every { classes.view(player) } returns DungeonClassesView(
            availability = DungeonClassAvailability.READY,
            roots = listOf(root.id),
            forms = mapOf(root.id to root, child.id to child),
        )
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, classService = classes) { _, screen, _ -> screens += screen }

        menus.classes(player)
        screens.last().buttons.single().onClick.handle(mockk())

        screens.last().buttons.map { it.id.value } shouldBe listOf("class_select_paladin", "class_guardian")
        screens.last().buttons.last().label shouldBe Component.text("<#ffffff>[Недоступно] <name> ›")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        screens.last().buttons.last().tooltip shouldBe Component.text("<#e8dfd2>Откроется на <level> уровне<newline><newline><#e8dfd2>Показать требования и способности")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "class admin can open a locked form from its detail" {
        val player = paper.addPlayer("class-admin")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val classes = mockk<DungeonClassService>()
        val grants = mockk<DungeonClassGrantService>()
        val form = dungeonClassForm("guardian", "Страж").copy(unlocked = false)
        every { classes.view(player) } returns DungeonClassesView(
            availability = DungeonClassAvailability.READY,
            roots = listOf(form.id),
            forms = mapOf(form.id to form),
        )
        every { grants.grant(player, form.id) } returns DungeonClassGrantResult(
            DungeonClassGrantResult.Status.APPLIED,
            form.id,
            form.name,
        )
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(
            dungeon,
            classService = classes,
            classGrants = grants,
            canGrantClasses = { true },
        ) { _, screen, _ -> screens += screen }

        menus.classes(player)
        screens.last().buttons.single().onClick.handle(mockk())
        val adminButton = screens.last().buttons.single { it.id.value == "class_admin_grant_guardian" }
        adminButton.label shouldBe Component.text("<#c4a7e7>Открыть класс")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        adminButton.onClick.handle(mockk())

        verify(exactly = 1) { grants.grant(player, form.id) }
        screens.last().body.first().text shouldBe Component.text("<#9bd48d>✔ Класс <name> открыт.")
            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "root close is separate from the quit action" {
        val player = paper.addPlayer("viewer")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val saves = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.view(player) } returns saves
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), saves)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        var screen: PaperDialogScreen? = null
        DungeonSaveMenus(dungeon) { _, shown, _ -> screen = shown }.panel(player)
        screen!!.exitButton!!.onClick.handle(mockk())
        verify(exactly = 0) { dungeon.quit(any()) }
        screen!!.buttons.single { it.id.value == "quit" }.onClick.handle(mockk())
        verify { dungeon.panelAction(player, any(), "quit") }
    }

    "autosave settings apply one choice without closing and return to saves" {
        val player = paper.addPlayer("interval")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("interval-world")
        every { dungeon.view(player) } returns DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.canConfigureAutosaves() } returns true
        every { dungeon.autosaveSeconds(player) } returns 120
        every { dungeon.setAutosaveSeconds(player, 60) } returns DungeonSaveEdit(true, Component.empty())
        val screens = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> screens += screen }.open(player)
        screens.last().buttons.single { it.id.value == "autosaves" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.autosaves"
        screens.last().buttons.map { it.id.value } shouldBe listOf("auto_60", "auto_120", "auto_300", "auto_0")
        screens.last().buttons.all { !it.closeDialogBeforeAction } shouldBe true
        screens.last().buttons.single { it.id.value == "auto_60" }.onClick.handle(mockk())
        verify(exactly = 1) { dungeon.setAutosaveSeconds(player, 60) }
        screens.last().id shouldBe "dungeon.autosaves"
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.saves"
    }

    "history reopener reads updated autosave interval instead of the old saves snapshot" {
        val player = paper.addPlayer("fresh-settings")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("fresh-settings-world")
        every { dungeon.view(player) } returns DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.canConfigureAutosaves() } returns true
        var seconds = 120
        every { dungeon.autosaveSeconds(player) } answers { seconds }
        every { dungeon.autosaveDescription(player) } answers { Component.text("interval=$seconds") }
        every { dungeon.setAutosaveSeconds(player, 60) } answers { seconds = 60; DungeonSaveEdit(true, Component.empty()) }
        val screens = mutableListOf<PaperDialogScreen>()
        val reopeners = mutableMapOf<String, () -> Unit>()
        DungeonSaveMenus(dungeon) { _, screen, reopen -> screens += screen; reopen?.let { reopeners[screen.id] = it } }.open(player)
        val reopenSaves = reopeners.getValue("dungeon.saves")
        screens.last().buttons.single { it.id.value == "autosaves" }.onClick.handle(mockk())
        screens.last().buttons.single { it.id.value == "auto_60" }.onClick.handle(mockk())
        reopenSaves()
        screens.last().id shouldBe "dungeon.saves"
        screens.last().body[1].text shouldBe Component.text("interval=60")
    }

    "start exists only while the instance is waiting and menus have no refresh action" {
        val player = paper.addPlayer("viewer")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val saves = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", waiting = false, canResume = true, instanced = true), saves)
        var screen: PaperDialogScreen? = null
        DungeonSaveMenus(dungeon) { _, shown, _ -> screen = shown }.panel(player)
        screen!!.buttons.none { it.id.value == "start" } shouldBe true
        screen!!.buttons.none { it.id.value == "refresh" } shouldBe true

        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", waiting = true, canResume = false, instanced = true), saves)
        DungeonSaveMenus(dungeon) { _, shown, _ -> screen = shown }.panel(player)
        screen!!.buttons.any { it.id.value == "start" } shouldBe true
        screen!!.buttons.none { it.id.value == "refresh" } shouldBe true
        screen!!.buttons.single { it.id.value == "start" }.onClick.handle(mockk())
        verify { dungeon.panelAction(player, any(), "start") }
    }

    "save captures expected view and shows error in the form" {
        val player = paper.addPlayer("editor")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.view(player) } returns expected
        every { dungeon.saveBlockReason(player, expected) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.save(player, "base", expected) } returns DungeonSaveEdit(false, Component.text("error"))
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.open(player)
        shown.last().buttons.single { it.id.value == "save" }.onClick.handle(mockk<PaperDialogClickContext> {
            every { text(any()) } returns ""
        })
        shown.last().buttons.single { it.id.value == "save" }.onClick.handle(mockk<PaperDialogClickContext> {
            every { text(any()) } returns "base"
        })
        verify { dungeon.save(player, "base", expected) }
        shown.last().id shouldBe "dungeon.saves.save"
        shown.last().inputs.single().initial shouldBe "base"
    }

    "renders a blocked save before opening the form and never writes from its muted button" {
        val player = paper.addPlayer("blocked")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.view(player) } returns expected
        every { dungeon.saveBlockReason(player, expected) } returns null
        every { dungeon.saveBlockReason(player, expected) } returns Component.text("В бою")
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.open(player)
        val save = shown.single().buttons.single { it.id.value == "save" }
        save.tooltip shouldBe Component.text("В бою")
        save.onClick.handle(mockk<PaperDialogClickContext> { every { text(any()) } returns "ignored" })
        verify(exactly = 0) { dungeon.save(any(), any(), any()) }
    }

    "point detail, back, and delete confirmation use the captured point view" {
        val player = paper.addPlayer("points")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val point = DungeonSavePoint("point-id", "Boss", DungeonSaveKind.MANUAL, Location(world, 1.0, 2.0, 3.0), 100L)
        val expected = DungeonSaveView(world.uid, "run", listOf(point), null, null)
        every { dungeon.view(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.remove(player, point, expected) } returns DungeonSaveEdit(true, Component.text("removed"))
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.open(player)
        shown.last().buttons.single { it.id.value == "point_0" }.onClick.handle(mockk())
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "point_0" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "remove" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "confirm" }.onClick.handle(mockk())
        verify { dungeon.remove(player, point, expected) }
    }
})

private fun dungeonClassForm(id: String, name: String) = DungeonClassForm(
    id = id,
    name = name,
    rootId = id,
    parentId = null,
    children = emptyList(),
    path = listOf(name),
    unlocked = true,
    selected = false,
    active = false,
    requiredLevel = 1,
    level = 1,
    cap = 20,
    xp = "0 / 100",
    foundations = listOf(DungeonClassRequirement("Мечи", "10 / 10")),
    blockers = emptyList(),
    resource = "Выносливость",
    resourceDescription = "Восстанавливается со временем.",
    weapons = listOf("Мечи"),
    mobility = DungeonClassAbility("Рывок", "Быстрое перемещение"),
    signature = DungeonClassAbility("Удар", "Сильная атака"),
    utility = DungeonClassAbility("Стойка", "Защитный эффект"),
    passives = listOf(DungeonClassPassive(name, "Повышает стойкость")),
)
