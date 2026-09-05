package ru.arc.gui

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.helpcenter.HelpCenterPage
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class MenuInputSettingsTest : TestBase() {
    @Test
    fun `default shortcut opens root once without summoning or swapping`() {
        val player = server.addPlayer()
        player.isSneaking = true
        val pages = mutableListOf<HelpCenterPage>()
        var summons = 0
        var selection = MenuShortcutAction.from(null)
        MenuShortcutController(plugin, { selection }, { _, page -> pages.add(page); true }, { summons++; true }).use { shortcuts ->
            fun swap() = PlayerSwapHandItemsEvent(player, ItemStack(Material.STONE), ItemStack(Material.TORCH))
                .also(shortcuts::onSwapHands)
            swap().isCancelled shouldBe true
            pages shouldBe listOf(HelpCenterPage.ROOT)
            summons shouldBe 0
            selection = MenuShortcutAction.MOUNT
            swap().isCancelled shouldBe true
            summons shouldBe 1
            pages.size shouldBe 1
            selection = MenuShortcutAction.DISABLED
            swap().isCancelled shouldBe false
            selection = MenuShortcutAction.MAIN
            player.isSneaking = false
            swap().isCancelled shouldBe false
            player.isSneaking = true
            val cancelled = PlayerSwapHandItemsEvent(player, ItemStack(Material.STONE), ItemStack(Material.AIR)).also { it.isCancelled = true }
            shortcuts.onSwapHands(cancelled)
            pages.size shouldBe 1
        }
        MenuShortcutAction.from("unknown") shouldBe MenuShortcutAction.MAIN
    }

    @Test
    fun `close all removes native escape callback but preserves the back button`() {
        val back = PaperDialogButton(PaperDialogActionId.of("back"), Component.text("Back"), onClick = mockk())
        val action = back.copy(id = PaperDialogActionId.of("action"))
        val original = PaperDialogScreen(Component.text("Settings"), buttons = listOf(action), exitButton = back)
        val close = MenuEscapeBehavior.apply(original, false)
        close.exitButton shouldBe null
        close.buttons shouldBe listOf(action, back)
        close.canCloseWithEscape shouldBe true
        close.buttons.all { !it.closeDialogBeforeAction } shouldBe true
        MenuEscapeBehavior.apply(close, false) shouldBe close
        MenuEscapeBehavior.apply(original, true) shouldBe original
    }
    @Test
    fun `async dialogs invalidate their visit on native escape while keeping normal back navigation`() {
        var closed = 0
        val back = PaperDialogButton(PaperDialogActionId.of("back"), Component.text("Back"), width = 150, onClick = mockk())
        val action = back.copy(id = PaperDialogActionId.of("action"), width = 230)
        val exit = PaperDialogButton(PaperDialogActionId.of("close_menu"), Component.text("Close"), closeDialogBeforeAction = true, onClick = { closed++ })
        val original = PaperDialogScreen(Component.text("Settings"), buttons = listOf(action), exitButton = back)
        val close = MenuEscapeBehavior.apply(original, false, exit)
        close.buttons.last().width shouldBe 230
        close.exitButton shouldBe exit
        close.exitButton!!.onClick.handle(mockk())
        closed shouldBe 1
        MenuEscapeBehavior.apply(original, true, exit) shouldBe original
    }

}
