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
    fun `dungeon shortcut overrides personal binding only while sneaking in a dungeon`() {
        val player = server.addPlayer()
        var inside = true
        var selection = MenuShortcutAction.MOUNT
        var dungeonOpens = 0
        var summons = 0
        val pages = mutableListOf<HelpCenterPage>()
        MenuShortcutController(plugin, { selection }, { _, page -> pages.add(page); true },
            { summons++; true }, { inside }, { dungeonOpens++ }).use { shortcuts ->
            fun swap(cancelled: Boolean = false) = PlayerSwapHandItemsEvent(player, ItemStack(Material.STONE), ItemStack(Material.TORCH))
                .also { it.isCancelled = cancelled; shortcuts.onSwapHands(it) }
            player.isSneaking = true
            swap().isCancelled shouldBe true
            selection = MenuShortcutAction.DISABLED
            swap().isCancelled shouldBe true
            dungeonOpens shouldBe 2
            summons shouldBe 0
            pages shouldBe emptyList()
            player.isSneaking = false
            swap().isCancelled shouldBe false
            player.isSneaking = true
            val alreadyCancelled = swap(cancelled = true)
            alreadyCancelled.isCancelled shouldBe true
            dungeonOpens shouldBe 3
            inside = false
            swap().isCancelled shouldBe false
            selection = MenuShortcutAction.MOUNT
            swap().isCancelled shouldBe true
            summons shouldBe 1
            dungeonOpens shouldBe 3
        }
    }

    @Test
    fun `escape follows actual history by default and preserves explicit close preference`() {
        MenuEscapeBehavior.goesBack(null) shouldBe true
        MenuEscapeBehavior.goesBack("back") shouldBe true
        MenuEscapeBehavior.goesBack("unknown") shouldBe true
        MenuEscapeBehavior.goesBack("close") shouldBe false
    }

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
    fun `a declared Back remains the native footer under a close preference`() {
        val back = PaperDialogButton(PaperDialogActionId.of("back"), Component.text("Back"), width = 200, onClick = mockk())
        val action = back.copy(id = PaperDialogActionId.of("action"))
        val original = PaperDialogScreen(Component.text("Settings"), buttons = listOf(action), exitButton = back)
        MenuEscapeBehavior.footerGoesBack(original, false) shouldBe true
        MenuEscapeBehavior.apply(original, false) shouldBe original
        MenuEscapeBehavior.apply(original, true) shouldBe original
        original.canCloseWithEscape shouldBe true
    }

    @Test
    fun `declared Close and root preference remain distinct from returning footers`() {
        val close = PaperDialogButton(PaperDialogActionId.of("close_menu"), Component.text("Close"),
            width = 150, closeDialogBeforeAction = true, onClick = {})
        val original = PaperDialogScreen(Component.text("Notice"), buttons = emptyList(), exitButton = close)
        MenuEscapeBehavior.footerGoesBack(original, true) shouldBe false
        MenuEscapeBehavior.apply(original, false, close).exitButton shouldBe close.copy(width = 200)
        val automatic = original.copy(exitButton = null)
        MenuEscapeBehavior.footerGoesBack(automatic, false) shouldBe false
        MenuEscapeBehavior.footerGoesBack(automatic, true) shouldBe true
    }
}
