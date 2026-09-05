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

    "returning after the dungeon ended replaces the stale dialog with an explanation" {
        val player = mockk<org.bukkit.entity.Player>(relaxed = true)
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        every { dungeon.view(player) } returns null
        every { dungeon.text(any(), any(), *anyVararg()) } returns Component.text("changed")
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
        shown.single().id shouldBe "dungeon.saves.unavailable"
        shown.single().buttons.single().closeDialogBeforeAction shouldBe true
        shown.single().body.single().text shouldBe Component.text("changed").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
    }

    "root close does not quit and explicit quit does" {
        val player = paper.addPlayer("viewer")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        every { dungeon.view(player) } returns DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        var screen: PaperDialogScreen? = null
        DungeonSaveMenus(dungeon) { _, shown -> screen = shown }.open(player)
        screen!!.exitButton!!.onClick.handle(mockk())
        verify(exactly = 0) { dungeon.quit(any()) }
        screen!!.buttons.single { it.id.value == "quit" }.onClick.handle(mockk())
        verify(exactly = 1) { dungeon.quit(player) }
    }

    "save captures expected view and shows error in the form" {
        val player = paper.addPlayer("editor")
        val dungeon = mockk<EMDungeonQol>(relaxed = true)
        val world = paper.addSimpleWorld("dungeon")
        val expected = DungeonSaveView(world.uid, "run", emptyList(), null, null)
        every { dungeon.view(player) } returns expected
        every { dungeon.text(any(), any(), *anyVararg()) } answers { Component.text(secondArg<String>()) }
        every { dungeon.save(player, "base", expected) } returns DungeonSaveEdit(false, Component.text("error"))
        val shown = mutableListOf<PaperDialogScreen>()
        DungeonSaveMenus(dungeon) { _, screen -> shown += screen }.open(player)
        shown.last().buttons.single { it.id.value == "save" }.onClick.handle(mockk())
        shown.last().buttons.single { it.id.value == "save" }.onClick.handle(mockk<PaperDialogClickContext> {
            every { text(any()) } returns "base"
        })
        verify { dungeon.save(player, "base", expected) }
        shown.last().id shouldBe "dungeon.saves.save"
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
