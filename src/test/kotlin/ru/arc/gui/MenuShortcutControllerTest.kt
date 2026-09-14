package ru.arc.gui

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import ru.arc.landsui.LandsUiModule
import ru.arc.paper.testing.MockBukkitTestRuntime

class MenuShortcutControllerTest {
    @Test
    fun `dungeon shift F is claimed early while plain F remains available to EliteMobs`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("dungeon-shortcut")
            var opened = 0
            MenuShortcutController(
                paper.createSimplePlugin("dungeon-shortcut-test"),
                inDungeon = { true },
                openDungeonMenu = { opened++ },
            ).use { shortcuts ->
                fun swap(cancelled: Boolean = false) = PlayerSwapHandItemsEvent(
                    player,
                    player.inventory.itemInMainHand,
                    player.inventory.itemInOffHand,
                ).also {
                    it.isCancelled = cancelled
                    shortcuts.onDungeonSwapHands(it)
                }

                player.isSneaking = false
                swap().isCancelled shouldBe false
                opened shouldBe 0

                player.isSneaking = true
                val shifted = swap()
                shifted.isCancelled shouldBe true
                shortcuts.onSwapHands(shifted)
                opened shouldBe 1

                // EliteMobs runs at the same priority but is registered first and cancels F once
                // a class is active. ARC must still own the dedicated dungeon Shift+F gesture.
                swap(cancelled = true).isCancelled shouldBe true
                opened shouldBe 2
            }
        }
    }

    @Test
    fun `claim block shift shortcut opens lands menu on every use and never swaps`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("claim-shortcut")
            val claimBlock = ItemStack(Material.GOLD_BLOCK).apply {
                editMeta { meta ->
                    meta.persistentDataContainer.set(
                        NamespacedKey("lands", "type"),
                        PersistentDataType.STRING,
                        "CLAIM_BLOCK",
                    )
                    meta.persistentDataContainer.set(
                        NamespacedKey("lands", "radius"),
                        PersistentDataType.INTEGER,
                        0,
                    )
                }
            }
            player.inventory.setItemInMainHand(claimBlock)
            mockkObject(LandsUiModule)
            every { LandsUiModule.open(any()) } just runs
            try {
                MenuShortcutController(paper.createSimplePlugin("shortcut-test")).use { shortcuts ->
                    fun swap(): PlayerSwapHandItemsEvent = PlayerSwapHandItemsEvent(
                        player,
                        player.inventory.itemInMainHand,
                        player.inventory.itemInOffHand,
                    ).also(shortcuts::onSwapHands)

                    player.isSneaking = true
                    swap().isCancelled shouldBe true
                    swap().isCancelled shouldBe true
                    verify(exactly = 2) { LandsUiModule.open(player) }

                    player.isSneaking = false
                    swap().isCancelled shouldBe false
                    verify(exactly = 2) { LandsUiModule.open(player) }
                }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }
}
