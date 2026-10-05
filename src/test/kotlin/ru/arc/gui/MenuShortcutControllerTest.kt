package ru.arc.gui

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import ru.arc.landsui.LandsUiModule
import ru.arc.helpcenter.HelpCenterPage
import ru.arc.paper.testing.MockBukkitTestRuntime

class MenuShortcutControllerTest {
    @Test
    fun `spectator shortcut opens main before EliteMobs while plain F and disabled shortcut pass through`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("spectator-shortcut")
            player.gameMode = GameMode.SPECTATOR
            var action = MenuShortcutAction.MAIN
            var opened = 0
            var abilityInputs = 0
            val eliteMobs = object : Listener {
                @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
                fun onSwapHands(event: PlayerSwapHandItemsEvent) {
                    abilityInputs++
                    event.isCancelled = true
                }
            }
            paper.server.pluginManager.registerEvents(eliteMobs, paper.createSimplePlugin("elitemobs-spectator"))
            MenuShortcutController(
                paper.createSimplePlugin("arc-spectator"),
                selection = { action },
                openMenu = { _, page ->
                    page shouldBe HelpCenterPage.ROOT
                    opened++
                    true
                },
                inDungeon = { false },
                openDungeonMenu = { error("Spectators cannot open the dungeon panel") },
                eliteMobsAbilityListener = { it === eliteMobs },
            ).use {
                fun swap(cancelled: Boolean = false) = PlayerSwapHandItemsEvent(
                    player, player.inventory.itemInMainHand, player.inventory.itemInOffHand,
                ).also {
                    it.isCancelled = cancelled
                    paper.server.pluginManager.callEvent(it)
                }

                player.isSneaking = true
                repeat(2) { swap().isCancelled shouldBe true }
                opened shouldBe 2
                abilityInputs shouldBe 0

                swap(cancelled = true)
                opened shouldBe 2
                abilityInputs shouldBe 0

                player.isSneaking = false
                swap()
                opened shouldBe 2
                abilityInputs shouldBe 1

                player.isSneaking = true
                action = MenuShortcutAction.DISABLED
                swap()
                opened shouldBe 2
                abilityInputs shouldBe 2
            }
        }
    }

    @Test
    fun `early dungeon shortcut cancellation prevents EliteMobs ability handling`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("dungeon-priority")
            var opened = 0
            var abilityInputs = 0
            val eliteMobs = object : Listener {
                @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
                fun onSwapHands(event: PlayerSwapHandItemsEvent) {
                    abilityInputs++
                }
            }
            paper.server.pluginManager.registerEvents(
                eliteMobs,
                paper.createSimplePlugin("elitemobs-priority"),
            )
            MenuShortcutController(
                paper.createSimplePlugin("arc-shortcut-priority"),
                inDungeon = { true },
                openDungeonMenu = { opened++ },
                eliteMobsAbilityListener = { it === eliteMobs },
            ).use {
                player.isSneaking = true
                val event = PlayerSwapHandItemsEvent(
                    player,
                    player.inventory.itemInMainHand,
                    player.inventory.itemInOffHand,
                )
                paper.server.pluginManager.callEvent(event)

                event.isCancelled shouldBe true
                opened shouldBe 1
                abilityInputs shouldBe 0
            }
        }
    }

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
                    shortcuts.onSwapHands(it)
                }

                player.isSneaking = false
                swap().isCancelled shouldBe false
                opened shouldBe 0

                player.isSneaking = true
                val shifted = swap()
                shifted.isCancelled shouldBe true
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
