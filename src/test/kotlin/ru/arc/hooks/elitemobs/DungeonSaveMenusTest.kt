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

    "opening saves outside a resumable run returns to the panel explanation" {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.view(player) } returns null
        every { dungeon.panelView(player) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } returns Component.text("changed")
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
        shown.single().id shouldBe "dungeon.panel.unavailable"
        shown.single().buttons.map { it.id.value } shouldBe listOf("guide", "portals", "list")
        shown.single().exitButton!!.id.value shouldBe "close"
        shown.single().exitButton!!.closeDialogBeforeAction shouldBe true
        shown.single().body.single().text shouldBe Component.text("changed").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "about page opens safely and returns to the current dungeon panel" {
        val player = paper.addPlayer("about")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("about-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", name = "Пещера"), null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.context(player) } returns Component.text("Подсказка")
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen -> screens += screen }
        menus.panel(player)
        screens.last().buttons.single { it.id.value == "about" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.about"
        screens.last().body.last().text shouldBe Component.text("Подсказка")
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.panel"
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
        DungeonSaveMenus(dungeon) { _, shown -> screen = shown }.panel(player)
        screen!!.exitButton!!.onClick.handle(mockk())
        verify(exactly = 0) { dungeon.quit(any()) }
        screen!!.buttons.single { it.id.value == "quit" }.onClick.handle(mockk())
        verify { dungeon.panelAction(player, any(), "quit") }
    }

    "start exists only while the instance is waiting and menus have no refresh action" {
        val player = paper.addPlayer("viewer")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val saves = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", waiting = false, canResume = true, instanced = true), saves)
        var screen: PaperDialogScreen? = null
        DungeonSaveMenus(dungeon) { _, shown -> screen = shown }.panel(player)
        screen!!.buttons.none { it.id.value == "start" } shouldBe true
        screen!!.buttons.none { it.id.value == "refresh" } shouldBe true

        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run", waiting = true, canResume = false, instanced = true), saves)
        DungeonSaveMenus(dungeon) { _, shown -> screen = shown }.panel(player)
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
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
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
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
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
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
        shown.last().buttons.single { it.id.value == "point_0" }.onClick.handle(mockk())
        shown.last().exitButton!!.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "point_0" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "remove" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "confirm" }.onClick.handle(mockk())
        verify { dungeon.remove(player, point, expected) }
    }
})
