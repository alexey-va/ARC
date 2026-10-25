package ru.arc.dungeons

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.spyk
import io.mockk.unmockkObject
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Test
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.dungeons.menu.MenuEscapeBehavior
import ru.arc.hooks.elitemobs.EMDungeonQol
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime

class DungeonsServiceMenuTest {
    @Test
    fun `returning footer stays below dungeon content while explicit Close retains its meaning`() {
        val back = PaperDialogButton(PaperDialogActionId.of("back"), Component.text("‹ Назад"), width = 230, onClick = {})
        val item = back.copy(id = PaperDialogActionId.of("item"))
        val screen = PaperDialogScreen(Component.text("Dungeon"), buttons = listOf(item), exitButton = back)
        MenuEscapeBehavior.footerGoesBack(screen, false) shouldBe true
        val prepared = MenuEscapeBehavior.apply(screen, true)
        prepared.buttons shouldBe listOf(item)
        prepared.exitButton shouldBe back.copy(width = 200)
        MenuEscapeBehavior.footerGoesBack(screen.copy(exitButton = back.copy(closeDialogBeforeAction = true)), true) shouldBe false
        MenuEscapeBehavior.footerGoesBack(screen.copy(exitButton = null), false) shouldBe false
    }

    @Test
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE") // Use the pinned runtime's headless presenter, retaining real shared history.
    fun `hotkey service opens its own dialog after an ARC menu and after the flow closes`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = spyk(paper.addPlayer("dungeon-dialog-owner"))
            every { player.closeDialog() } just runs
            val rendered = mutableListOf<String>()
            PaperDialogRuntime(paper.createSimplePlugin("ARC")) { _, screen, _ ->
                rendered += screen.id
            }.use { main ->
                PaperDialogRuntime(paper.createSimplePlugin("ArcDungeons")) { _, screen, _ ->
                    rendered += screen.id
                }.use { dungeon ->
                    val qol = mockk<EMDungeonQol>()
                    val plugin = mockk<ArcDungeonsPlugin> { every { dungeonQol } returns qol }
                    val provider = DungeonsServiceProvider(plugin)
                    val panel = PaperDialogScreen(Component.text("Dungeon"), id = "dungeon.panel", buttons = emptyList())
                    every { qol.action(player, "menu", emptyList()) } answers { dungeon.open(player, panel) }
                    mockkObject(DungeonsMenus)
                    every { DungeonsMenus.beginDialogFlow(player) } answers { dungeon.beginFlow(player) }
                    try {
                        main.beginFlow(player)
                        main.open(player, PaperDialogScreen(Component.text("Main"), id = "help.root", buttons = emptyList()))
                        provider.dungeonAction(player, "menu", emptyList())
                        rendered shouldBe listOf("help.root", "dungeon.panel")

                        dungeon.close(player)
                        provider.dungeonAction(player, "menu", emptyList())
                        rendered shouldBe listOf("help.root", "dungeon.panel", "dungeon.panel")
                    } finally {
                        unmockkObject(DungeonsMenus)
                    }
                }
            }
        }
    }
}
