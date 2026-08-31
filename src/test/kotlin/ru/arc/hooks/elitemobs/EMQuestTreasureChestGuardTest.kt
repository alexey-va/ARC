package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.customtreasurechests.CustomTreasureChestConfigFields
import com.magmaguy.elitemobs.items.customloottable.CustomLootEntry
import com.magmaguy.elitemobs.items.customloottable.CustomLootTable
import com.magmaguy.elitemobs.items.customloottable.EliteCustomLootEntry
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import java.nio.file.Path

class EMQuestTreasureChestGuardTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearConfigCache() {
        ConfigManager.clear()
    }

    @Test
    fun `blocks a chest whose rewards are all locked by missing quest permissions`() {
        val entries = listOf(eliteEntry("supplies.yml"), eliteEntry("supplies.yml"))

        isQuestTreasureChestLocked(
            entries = entries,
            hasPermission = { false },
            customItemPermission = { "elitequest.primis_gladius_supplies_1_loot.yml" },
        ).shouldBeTrue()
    }

    @Test
    fun `allows the chest when the player has its quest permission`() {
        isQuestTreasureChestLocked(
            entries = listOf(eliteEntry("supplies.yml")),
            hasPermission = { true },
            customItemPermission = { "elitequest.primis_gladius_supplies_1_loot.yml" },
        ).shouldBeFalse()
    }

    @Test
    fun `fails open for mixed loot and non-quest permissions`() {
        isQuestTreasureChestLocked(
            entries = listOf(eliteEntry("supplies.yml"), mockk<CustomLootEntry>()),
            hasPermission = { false },
            customItemPermission = { "elitequest.primis_gladius_supplies_1_loot.yml" },
        ).shouldBeFalse()
        isQuestTreasureChestLocked(
            entries = listOf(eliteEntry("admin-item.yml")),
            hasPermission = { false },
            customItemPermission = { "arc.admin" },
        ).shouldBeFalse()
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cancels before EliteMobs can consume the click and sends one player-facing message`() {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission(any<String>()) } returns false
        }
        val location = mockk<Location>()
        val block = mockk<Block> {
            every { this@mockk.location } returns location
        }
        val chest = chest(listOf(eliteEntry("supplies.yml")))
        val listener =
            EMListener(
                config = ConfigManager.of(tempDir, "elitemobs.yml"),
                treasureChestAt = { chest },
                customItemPermission = { "elitequest.primis_gladius_supplies_1_loot.yml" },
            )
        val event =
            PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                BlockFace.UP,
                EquipmentSlot.HAND,
            )

        listener.guardQuestTreasureChest(event)

        event.isCancelled.shouldBeTrue()
        verify(exactly = 1) { player.sendMessage(any<Component>()) }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cancels an off-hand duplicate without duplicating the chat message`() {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission(any<String>()) } returns false
        }
        val location = mockk<Location>()
        val block = mockk<Block> {
            every { this@mockk.location } returns location
        }
        val listener =
            EMListener(
                config = ConfigManager.of(tempDir, "elitemobs.yml"),
                treasureChestAt = { chest(listOf(eliteEntry("supplies.yml"))) },
                customItemPermission = { "elitequest.primis_gladius_supplies_1_loot.yml" },
            )
        val event =
            PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                null,
                block,
                BlockFace.UP,
                EquipmentSlot.OFF_HAND,
            )

        listener.guardQuestTreasureChest(event)

        event.isCancelled.shouldBeTrue()
        verify(exactly = 0) { player.sendMessage(any<Component>()) }
    }

    private fun eliteEntry(filename: String) =
        mockk<EliteCustomLootEntry> {
            every { this@mockk.filename } returns filename
        }

    private fun chest(entries: List<CustomLootEntry>): TreasureChest {
        val lootTable = mockk<CustomLootTable> {
            every { this@mockk.entries } returns entries
        }
        val fields = mockk<CustomTreasureChestConfigFields> {
            every { customLootTable } returns lootTable
        }
        return mockk {
            every { customTreasureChestConfigFields } returns fields
        }
    }
}
