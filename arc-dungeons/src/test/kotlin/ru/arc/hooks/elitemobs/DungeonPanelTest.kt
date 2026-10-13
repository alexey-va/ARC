package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime

class DungeonPanelTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "default panel exposes native actions for lobby, open, and ongoing visits" {
        val player = paper.addPlayer("panel")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        every { dungeon.partiesAvailable() } returns true
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        val visits = listOf(
            DungeonVisit("lobby", waiting = true, instanced = true, contentId = "crypt"),
            DungeonVisit("open", instanced = false, contentId = "forest"),
            DungeonVisit("ongoing", canResume = true, instanced = true, contentId = "crypt"),
        )
        for (visit in visits) {
            every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, visit, null)
            DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
            shown.last().id shouldBe "dungeon.panel"
            val ids = shown.last().buttons.map { it.id.value }
            ids.contains("saves") shouldBe false
            ids.contains("entry") shouldBe false
            ids.contains("resume") shouldBe false
            ids shouldContain "party"
            ids shouldContain "bestiary"
            ids.last() shouldBe "global"
            ids.contains("panel_padding") shouldBe false
            ids.contains("scoreboard") shouldBe false
            ids.contains("about") shouldBe false
            ids.contains("quit") shouldBe visit.instanced
            ids.contains("start") shouldBe visit.waiting
        }
        shown.first().buttons.single { it.id.value == "start" }.closeDialogBeforeAction shouldBe true
    }

    "panel table shows real quest and bestiary progress without save counts" {
        val player = paper.addPlayer("progress")
        val world = paper.addSimpleWorld("progress-dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        val saves = DungeonSaveView(world.uid, "run", emptyList(), world.spawnLocation, null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid,
            DungeonVisit("run", contentId = "crypt"), saves)
        every { dungeon.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, Component>>>()
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>(),
                net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(values.map { (name, value) ->
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component(name, value)
                }))
        }
        var quests: List<DungeonQuestInfo>? = listOf(
            DungeonQuestInfo(java.util.UUID.randomUUID(), "Сданные цели", false, true, true,
                listOf("Цель", "Вернуться к NPC"), listOf(DungeonQuestGoalState.COMPLETE, DungeonQuestGoalState.NEXT)),
            DungeonQuestInfo(java.util.UUID.randomUUID(), "В пути", true, false, true,
                listOf("Цель", "Цель"), listOf(DungeonQuestGoalState.COMPLETE, DungeonQuestGoalState.ACTIVE)),
        )
        var discovered: Int? = 7
        val shown = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon, readQuests = { quests }, bestiaryProgress = { owner, content ->
            owner shouldBe player
            content shouldBe "crypt"
            DungeonBestiaryProgress(discovered, 12)
        }) { _, screen, _ -> shown += screen }
        fun body(): String = shown.last().body.joinToString("\n") {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.text)
        }
        menus.panel(player)
        listOf("Мои задания", "1 / 2 к сдаче", "Цели заданий", "2 / 3", "Изучено врагов", "7 / 12").forEach {
            body().contains(it) shouldBe true
        }
        listOf("Ручные точки", "Автоточки").forEach { body().contains(it) shouldBe false }
        quests = null
        discovered = null
        menus.panel(player)
        body().contains("Загружается…") shouldBe true
        body().contains("… / 12") shouldBe true
        body().contains("Цели заданий") shouldBe false
        body().contains("0 / 12") shouldBe false
        quests = emptyList()
        menus.panel(player)
        body().contains("Нет активных") shouldBe true
    }

    "saves action opens the child and child back returns to the root" {
        val player = paper.addPlayer("navigation")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        val view = DungeonSaveView(world.uid, "run", emptyList(), Location(world, 0.0, 70.0, 0.0), null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", canResume = true), view)
        every { dungeon.view(player) } returns view
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.last().buttons.map { it.id.value }.contains("entry") shouldBe true
        shown.last().buttons.map { it.id.value }.contains("resume") shouldBe false
        shown.last().buttons.single { it.id.value == "saves" }.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.saves"
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.panel"
    }

    "continuation is an explicit root action tied to the displayed saved departure" {
        val player = paper.addPlayer("continue")
        val world = paper.addSimpleWorld("continue-dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        val expected = DungeonSaveView(world.uid, "run", emptyList(), world.spawnLocation, Location(world, 12.0, 70.0, 4.0))
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), expected)
        every { dungeon.continuation(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }
        menus.panel(player)
        shown.last().buttons.map { it.id.value }.contains("entry") shouldBe true
        val resume = shown.last().buttons.single { it.id.value == "resume" }
        resume.closeDialogBeforeAction shouldBe true
        verify(exactly = 0) { dungeon.travel(player, any(), any()) }
        resume.onClick.handle(mockk())
        verify(exactly = 1) { dungeon.travel(player, expected, "exit") }
        every { dungeon.continuation(player) } returns null
        menus.panel(player)
        shown.last().buttons.map { it.id.value }.contains("resume") shouldBe false
        verify(exactly = 1) { dungeon.travel(player, expected, "exit") }
    }

    "root and child footers follow the default back preference" {
        val player = paper.addPlayer("footer")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        val view = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), view)
        every { dungeon.view(player) } returns view
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.last().buttons.map { it.id.value }.contains("saves") shouldBe true
        shown.last().buttons.map { it.id.value }.contains("entry") shouldBe false
        shown.last().buttons.map { it.id.value }.contains("resume") shouldBe false
        shown.last().exitButton!!.id.value shouldBe "back"
        shown.last().buttons.single { it.id.value == "saves" }.onClick.handle(mockk())
        shown.last().exitButton!!.id.value shouldBe "back"
    }

    "panel actions carry the displayed expected view to the authoritative qol" {
        val player = paper.addPlayer("stale")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.continuation(player) } returns null
        val expected = DungeonPanelView(world.uid, DungeonVisit("run", waiting = true, instanced = true), null)
        every { dungeon.panelView(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        var action: String? = null
        every { dungeon.panelAction(player, expected, any()) } answers { action = thirdArg() }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.single().buttons.map { it.id.value }.contains("party") shouldBe false
        shown.single().buttons.single { it.id.value == "start" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
        verify { dungeon.panelAction(player, expected, "start") }
        action shouldBe "start"
    }
})
