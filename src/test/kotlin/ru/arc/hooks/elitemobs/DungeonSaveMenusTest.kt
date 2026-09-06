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
        shown.single().buttons.map { it.id.value } shouldBe listOf("guide", "portals", "list", "party", "main")
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

    "party section routes to native management when supported and main navigation stays open" {
        val player = paper.addPlayer("party")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("party-world")
        every { dungeon.panelView(player) } returns DungeonPanelView(world.uid, DungeonVisit("run"), null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.partiesAvailable() } returns true
        val screens = mutableListOf<PaperDialogScreen>()
        val menus = DungeonSaveMenus(dungeon) { _, screen -> screens += screen }
        menus.panel(player)
        screens.last().buttons.single { it.id.value == "main" }.also {
            it.closeDialogBeforeAction shouldBe false
            it.onClick.handle(mockk())
        }
        verify { dungeon.action(player, "main") }
        screens.last().buttons.single { it.id.value == "party" }.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.party"
        screens.last().buttons.single { it.id.value == "manage_party" }.onClick.handle(mockk())
        verify { dungeon.action(player, "party") }
        screens.last().exitButton!!.onClick.handle(mockk())
        screens.last().id shouldBe "dungeon.panel"
        every { dungeon.partiesAvailable() } returns false
        screens.last().buttons.single { it.id.value == "party" }.onClick.handle(mockk())
        screens.last().buttons.map { it.id.value } shouldBe listOf("guide")
        screens.last().body.last().text shouldBe Component.text("<#aaa49a>Группы EliteMobs на этом сервере пока недоступны.")
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
        DungeonSaveMenus(dungeon) { _, shown -> screen = shown }.panel(player)
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
        DungeonSaveMenus(dungeon) { _, screen -> screens += screen }.open(player)
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
