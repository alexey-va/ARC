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
            ids shouldContain "saves"
            ids shouldContain "bestiary"
            ids.last() shouldBe "global"
            (ids.size - 1) % shown.last().columns shouldBe 0
            ids shouldContain "scoreboard"
            ids shouldContain "quit"
        }
        shown.first().buttons.single { it.id.value == "start" }.closeDialogBeforeAction shouldBe true
    }

    "saves action opens the child and child back returns to the root" {
        val player = paper.addPlayer("navigation")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val view = DungeonSaveView(world.uid, "run", emptyList(), Location(world, 0.0, 70.0, 0.0), null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", canResume = true), view)
        every { dungeon.view(player) } returns view
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.last().buttons.single { it.id.value == "saves" }.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.saves"
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().id shouldBe "dungeon.panel"
    }

    "continuation is an explicit root action tied to the displayed saved departure" {
        val player = paper.addPlayer("continue")
        val world = paper.addSimpleWorld("continue-dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val expected = DungeonSaveView(world.uid, "run", emptyList(), world.spawnLocation, Location(world, 12.0, 70.0, 4.0))
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), expected)
        every { dungeon.continuation(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }
        menus.panel(player)
        val resume = shown.last().buttons.single { it.id.value == "resume" }
        resume.closeDialogBeforeAction shouldBe true
        verify(exactly = 0) { dungeon.travel(player, any(), any()) }
        resume.onClick.handle(mockk())
        verify(exactly = 1) { dungeon.travel(player, expected, "exit") }
        every { dungeon.continuation(player) } returns null
        menus.panel(player)
        val unavailable = shown.last().buttons.single { it.id.value == "resume" }
        unavailable.closeDialogBeforeAction shouldBe false
        unavailable.onClick.handle(mockk())
        verify(exactly = 1) { dungeon.travel(player, expected, "exit") }
    }

    "root and child footers follow the default back preference" {
        val player = paper.addPlayer("footer")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val view = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), view)
        every { dungeon.view(player) } returns view
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.last().exitButton!!.id.value shouldBe "back"
        shown.last().buttons.single { it.id.value == "saves" }.onClick.handle(mockk())
        shown.last().exitButton!!.id.value shouldBe "back"
    }

    "panel actions carry the displayed expected view to the authoritative qol" {
        val player = paper.addPlayer("stale")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val expected = DungeonPanelView(world.uid, DungeonVisit("run", waiting = true, instanced = true), null)
        every { dungeon.panelView(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        var action: String? = null
        every { dungeon.panelAction(player, expected, any()) } answers { action = thirdArg() }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.single().buttons.single { it.id.value == "start" }.onClick.handle(mockk<PaperDialogClickContext>(relaxed = true))
        verify { dungeon.panelAction(player, expected, "start") }
        action shouldBe "start"
    }
})
