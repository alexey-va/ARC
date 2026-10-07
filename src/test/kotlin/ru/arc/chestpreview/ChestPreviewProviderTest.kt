package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Chest
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

class ChestPreviewProviderTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    "permission is checked before resolving a chest or reading inventory" {
        val player = mockk<Player>()
        every { player.hasPermission("arc.chest-preview") } returns false
        var resolverCalls = 0
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            resolverCalls++
            error("unauthorized viewers must not resolve targets")
        }

        provider.resolve(player) shouldBe null
        resolverCalls shouldBe 0
    }

    "similar stacks aggregate in half and slot order in a hologram-only frame" {
        val world = mockk<World>()
        val player = permittedPlayer(world)
        val chestA = chest(arrayOf(
            named(Material.DIAMOND, "<red>Ruby").apply { amount = 3 },
            named(Material.EMERALD, "Emerald").apply { amount = 2 },
            named(Material.DIAMOND, "<red>Ruby").apply { amount = 5 },
            null,
        ))
        val chestB = chest(arrayOf(
            named(Material.DIAMOND, "Ruby").apply { amount = 1 },
            named(Material.EMERALD, "Emerald").apply { amount = 4 },
        ))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(chestA, chestB), InspectionHologramAnchor(world.uid, 4.5, 70.0, -2.5))
        }

        val frame = provider.resolve(player)!!
        val text = PlainTextComponentSerializer.plainText().serialize(frame.hologram)

        text shouldBe "Содержимое сундука\n<red>Ruby × 8\nEmerald × 6\nRuby × 1"
        frame.hologramAnchor shouldBe InspectionHologramAnchor(world.uid, 4.5, 70.15, -2.5)
        // Selecting BOSSBAR or OFF must not force chest contents into another view mode.
        frame.bossbar shouldBe Component.empty()
        verify(exactly = 1) { chestA.blockInventory }
        verify(exactly = 1) { chestB.blockInventory }
    }

    "overflow counts omitted groups and empty inventories show the empty state" {
        val world = mockk<World>()
        val player = permittedPlayer(world)
        val chest = chest(arrayOf(
            named(Material.DIAMOND, "Алмаз").apply { amount = 1 },
            named(Material.EMERALD, "Изумруд").apply { amount = 1 },
            named(Material.GOLD_INGOT, "Золото").apply { amount = 1 },
        ))
        val provider = ChestPreviewProvider(ChestPreviewSettings(maxItems = 2)) { _, _ ->
            ChestPreviewTarget(listOf(chest), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        PlainTextComponentSerializer.plainText().serialize(provider.resolve(player)!!.hologram) shouldBe
            "Содержимое сундука\nАлмаз × 1\nИзумруд × 1\nИ ещё: 1"

        val emptyChest = chest(arrayOfNulls(27))
        val empty = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(emptyChest), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        PlainTextComponentSerializer.plainText().serialize(empty.resolve(player)!!.hologram) shouldBe
            "Содержимое сундука\nПусто"
    }

    "the default item label stays a translatable component" {
        chestPreviewDisplayName(ItemStack(Material.DIAMOND)) shouldBe
            Component.translatable(Material.DIAMOND.translationKey())
    }

    "each viewer resolves a fresh target without sharing the prior viewer payload" {
        val world = mockk<World>()
        val first = permittedPlayer(world)
        val second = permittedPlayer(world)
        val firstChest = chest(arrayOf(named(Material.PAPER, "Первый")))
        val secondChest = chest(arrayOf(named(Material.PAPER, "Второй")))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { player, _ ->
            val selected = if (player === first) firstChest else secondChest
            ChestPreviewTarget(listOf(selected), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }

        val serializer = PlainTextComponentSerializer.plainText()
        serializer.serialize(provider.resolve(first)!!.hologram) shouldBe "Содержимое сундука\nПервый × 1"
        serializer.serialize(provider.resolve(second)!!.hologram) shouldBe "Содержимое сундука\nВторой × 1"
        verify(exactly = 1) { firstChest.blockInventory }
        verify(exactly = 1) { secondChest.blockInventory }
    }

    "pathological custom names are normalized and capped" {
        val world = mockk<World>()
        val player = permittedPlayer(world)
        val chest = chest(arrayOf(named(Material.PAPER, "a\n" + "x".repeat(90))))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(chest), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }

        val row = PlainTextComponentSerializer.plainText().serialize(provider.resolve(player)!!.hologram).lines()[1]
        row shouldBe "a ${"x".repeat(63)} × 1"
    }
})

private fun permittedPlayer(world: World): Player = mockk<Player>().also { player ->
    every { player.hasPermission("arc.chest-preview") } returns true
    every { player.world } returns world
    every { world.uid } returns UUID(1L, 2L)
}

private fun chest(contents: Array<out ItemStack?>): Chest = mockk<Chest>().also { chest ->
    val inventory = mockk<Inventory>()
    val snapshot = Array<ItemStack?>(contents.size) { contents[it] }
    every { inventory.contents } returns snapshot
    every { chest.blockInventory } returns inventory
}

private fun named(material: Material, name: String): ItemStack = ItemStack(material).apply {
    editMeta { it.displayName(Component.text(name)) }
}
