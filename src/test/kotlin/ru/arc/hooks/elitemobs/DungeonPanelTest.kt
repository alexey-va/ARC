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
            DungeonVisit("lobby", waiting = true, instanced = true),
            DungeonVisit("open", instanced = false),
            DungeonVisit("ongoing", canResume = true, instanced = true),
        )
        for (visit in visits) {
            every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, visit, null)
            DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
            shown.last().id shouldBe "dungeon.panel"
            shown.last().buttons.map { it.id.value } shouldContain "saves"
            shown.last().buttons.map { it.id.value } shouldContain "quit"
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

    "root footer closes only while child footer goes back" {
        val player = paper.addPlayer("footer")
        val world = paper.addSimpleWorld("dungeon")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val view = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), view)
        every { dungeon.view(player) } returns view
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen, _ -> shown += screen }.panel(player)
        shown.last().exitButton!!.id.value shouldBe "close"
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
