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
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Test
import ru.arc.landsui.LandsUiModule
import ru.arc.helpcenter.HelpCenterPage
import ru.arc.paper.testing.MockBukkitTestRuntime

class MenuShortcutControllerTest {
    @Test
    fun `ArcBuilder display editor owns plain and shift F before every ARC route and EliteMobs`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("display-editor-shortcut")
            val arcBuilder = paper.createSimplePlugin("ArcBuilder")
            var editing = true
            var insideDungeon = false
            var editorInputs = 0
            var abilityInputs = 0
            var personalSelections = 0
            var menuOpens = 0
            var dungeonChecks = 0
            var dungeonOpens = 0
            val eliteMobs = object : Listener {
                @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
                fun onSwapHands(event: PlayerSwapHandItemsEvent) {
                    abilityInputs++
                }
            }
            val editor = object : Listener {
                @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
                fun onSwapHands(event: PlayerSwapHandItemsEvent) {
                    if (editing) {
                        event.isCancelled shouldBe true
                        editorInputs++
                    }
                }
            }
            paper.server.pluginManager.registerEvents(eliteMobs, paper.createSimplePlugin("elitemobs-editor"))
            paper.server.pluginManager.registerEvents(editor, arcBuilder)
            player.setMetadata("arcbuilder-display-editor", FixedMetadataValue(arcBuilder, true))
            mockkObject(LandsUiModule)
            every { LandsUiModule.openCurrent(any()) } just runs
            try {
                MenuShortcutController(
                    paper.createSimplePlugin("arc-editor-shortcut"),
                    selection = { personalSelections++; MenuShortcutAction.MAIN },
                    openMenu = { _, _ -> menuOpens++; true },
                    inDungeon = { dungeonChecks++; insideDungeon },
                    openDungeonMenu = { dungeonOpens++ },
                    eliteMobsAbilityListener = { it === eliteMobs },
                ).use {
                    fun swap() = PlayerSwapHandItemsEvent(
                        player, player.inventory.itemInMainHand, player.inventory.itemInOffHand,
                    ).also(paper.server.pluginManager::callEvent)

                    for ((index, sneaking) in listOf(false, true).withIndex()) {
                        player.isSneaking = sneaking
                        swap().isCancelled shouldBe true
                        editorInputs shouldBe index + 1
                    }
                    insideDungeon = true
                    swap().isCancelled shouldBe true
                    editorInputs shouldBe 3
                    player.inventory.setItemInMainHand(ItemStack(Material.GOLD_BLOCK).apply {
                        editMeta { meta ->
                            meta.persistentDataContainer.set(
                                NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK",
                            )
                            meta.persistentDataContainer.set(
                                NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0,
                            )
                        }
                    })
                    swap().isCancelled shouldBe true
                    editorInputs shouldBe 4
                    abilityInputs shouldBe 0
                    personalSelections shouldBe 0
                    menuOpens shouldBe 0
                    dungeonChecks shouldBe 0
                    dungeonOpens shouldBe 0
                    verify(exactly = 0) { LandsUiModule.openCurrent(player) }

                    editing = false
                    player.removeMetadata("arcbuilder-display-editor", arcBuilder)
                    swap().isCancelled shouldBe true
                    verify(exactly = 1) { LandsUiModule.openCurrent(player) }

                    player.inventory.setItemInMainHand(ItemStack(Material.AIR))
                    swap().isCancelled shouldBe true
                    dungeonOpens shouldBe 1
                    insideDungeon = false
                    swap().isCancelled shouldBe true
                    personalSelections shouldBe 1
                    menuOpens shouldBe 1
                    player.isSneaking = false
                    swap().isCancelled shouldBe false
                    abilityInputs shouldBe 1
                    editorInputs shouldBe 4
                }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }

    @Test
    fun `foreign false and disabled ArcBuilder metadata cannot capture the shortcut`() {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("editor-metadata-owner")
            val arcBuilder = paper.createSimplePlugin("ArcBuilder")
            val foreignPlugin = paper.createSimplePlugin("foreign-editor")
            player.isSneaking = true
            var opened = 0
            MenuShortcutController(
                paper.createSimplePlugin("arc-metadata-shortcut"),
                selection = { MenuShortcutAction.MAIN },
                openMenu = { _, _ -> opened++; true },
                inDungeon = { false },
            ).use {
                fun swap() = PlayerSwapHandItemsEvent(
                    player, player.inventory.itemInMainHand, player.inventory.itemInOffHand,
                ).also(paper.server.pluginManager::callEvent)

                player.setMetadata("arcbuilder-display-editor", FixedMetadataValue(foreignPlugin, true))
                swap().isCancelled shouldBe true
                opened shouldBe 1
                player.setMetadata("arcbuilder-display-editor", FixedMetadataValue(arcBuilder, false))
                swap().isCancelled shouldBe true
                opened shouldBe 2
                paper.server.pluginManager.disablePlugin(arcBuilder)
                arcBuilder.isEnabled shouldBe false
                player.setMetadata("arcbuilder-display-editor", FixedMetadataValue(arcBuilder, true))
                swap().isCancelled shouldBe true
                opened shouldBe 3
            }
        }
    }

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
    fun `dungeon shortcut wins with empty hands staffs and weapons while plain F reaches EliteMobs`() {
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
                for ((index, material) in listOf(Material.AIR, Material.BLAZE_ROD, Material.DIAMOND_SWORD).withIndex()) {
                    player.inventory.setItemInMainHand(ItemStack(material))
                    fun swap() = PlayerSwapHandItemsEvent(
                        player, player.inventory.itemInMainHand, player.inventory.itemInOffHand,
                    ).also(paper.server.pluginManager::callEvent)

                    player.isSneaking = true
                    repeat(2) { swap().isCancelled shouldBe true }
                    opened shouldBe (index + 1) * 2
                    abilityInputs shouldBe index

                    player.isSneaking = false
                    swap().isCancelled shouldBe false
                    opened shouldBe (index + 1) * 2
                    abilityInputs shouldBe index + 1
                }
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
            every { LandsUiModule.openCurrent(any()) } just runs
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
                    verify(exactly = 2) { LandsUiModule.openCurrent(player) }

                    player.isSneaking = false
                    swap().isCancelled shouldBe false
                    verify(exactly = 2) { LandsUiModule.openCurrent(player) }
                }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }
}
