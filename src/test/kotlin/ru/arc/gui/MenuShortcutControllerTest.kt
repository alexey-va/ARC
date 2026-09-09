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
