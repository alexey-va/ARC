package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.block.EnderChest
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

class ChestPreviewProviderTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    "permission is checked before target resolution or inventory reads" {
        val player = mockk<Player>()
        every { player.hasPermission("arc.chest-preview") } returns false
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ -> error("must not resolve") }
        provider.resolve(player) shouldBe null
    }

    "distinct icons retain item appearance and physical slot order without any text" {
        val world = world()
        val player = player(world)
        val diamond = ItemStack(Material.DIAMOND, 3).apply { editMeta { it.displayName(Component.text("Ruby")) } }
        val first = chest(arrayOf(diamond, ItemStack(Material.EMERALD, 2), diamond.clone().apply { amount = 5 }))
        val second = chest(arrayOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD, 4)))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(first, second), InspectionHologramAnchor(world.uid, 4.5, 70.0, -2.5))
        }
        val frame = provider.selectedFrame(player) {
            val suppression = provider.resolve(player)!!
            suppression.hologram shouldBe Component.empty()
            suppression.bossbar shouldBe Component.empty()
            suppression.suppressesLowerSources shouldBe true
        }!!
        frame.items.map { it.type } shouldBe listOf(Material.DIAMOND, Material.EMERALD, Material.DIAMOND)
        frame.items.map { it.amount } shouldBe listOf(1, 1, 1)
        frame.items.first().isSimilar(diamond) shouldBe true
        diamond.amount shouldBe 3
        frame.anchor shouldBe InspectionHologramAnchor(world.uid, 4.5, 70.15, -2.5)
        verify(exactly = 1) { first.blockInventory }
        verify(exactly = 1) { second.blockInventory }
    }

    "barrels are read through their live block inventory" {
        val world = world(); val player = player(world)
        val barrel = mockk<Barrel>()
        val inventory = mockk<Inventory>()
        every { inventory.contents } returns arrayOf(ItemStack(Material.COPPER_INGOT))
        every { barrel.inventory } returns inventory
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(barrel), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        provider.selectedFrame(player) { provider.resolve(player) }!!.items.map { it.type } shouldBe
            listOf(Material.COPPER_INGOT)
        verify(exactly = 1) { barrel.inventory }
    }

    "Ender Chest preview uses each viewer's own inventory without cross-viewer caching" {
        val world = world(); val viewer = player(world); val other = player(world)
        val enderChest = mockk<EnderChest>()
        val viewerInventory = mockk<Inventory>()
        val otherInventory = mockk<Inventory>()
        every { viewerInventory.contents } returns arrayOf(ItemStack(Material.ENDER_PEARL, 3))
        every { otherInventory.contents } returns arrayOf(ItemStack(Material.DIAMOND, 2))
        every { viewer.enderChest } returns viewerInventory
        every { other.enderChest } returns otherInventory
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(enderChest), InspectionHologramAnchor(world.uid, 1.5, 64.0, 0.5))
        }

        provider.selectedFrame(viewer) { provider.resolve(viewer) }!!.items.map { it.type } shouldBe
            listOf(Material.ENDER_PEARL)
        provider.selectedFrame(other) { provider.resolve(other) }!!.items.map { it.type } shouldBe
            listOf(Material.DIAMOND)
        provider.selectedFrame(viewer) { provider.resolve(viewer) }!!.items.map { it.type } shouldBe
            listOf(Material.ENDER_PEARL)
        verify(exactly = 2) { viewer.enderChest }
        verify(exactly = 1) { other.enderChest }
    }

    "an unresolved Ender Chest never reads private contents" {
        val world = world(); val viewer = player(world)
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ -> null }
        provider.selectedFrame(viewer) { provider.resolve(viewer) } shouldBe null
        verify(exactly = 0) { viewer.enderChest }
    }

    "max items caps icons and empty inventory has no empty-state label" {
        val world = world(); val player = player(world)
        var inventory = chest(arrayOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD), ItemStack(Material.GOLD_INGOT)))
        val provider = ChestPreviewProvider(ChestPreviewSettings(maxItems = 2)) { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        provider.selectedFrame(player) { provider.resolve(player) }!!.items.size shouldBe 2
        inventory = chest(arrayOfNulls(27))
        provider.selectedFrame(player) { provider.resolve(player) }!!.items shouldBe emptyList()
    }

    "a higher priority winner or OFF does not retain the previous scene" {
        val world = world(); val player = player(world)
        val inventory = chest(arrayOf(ItemStack(Material.DIAMOND)))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        provider.selectedFrame(player) { provider.resolve(player) }!!.items.size shouldBe 1
        provider.selectedFrame(player) { /* shared inspector did not select this provider */ } shouldBe null
        provider.resolve(player) // Out-of-cycle registration resolution must not cache a scene.
        provider.selectedFrame(player) {} shouldBe null
    }

    "capture never shares another viewer's inventory" {
        val world = world(); val first = player(world); val second = player(world)
        val inventory = chest(arrayOf(ItemStack(Material.PAPER)))
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        }
        provider.selectedFrame(first) { provider.resolve(second) } shouldBe null
    }

    "wrong-world targets are rejected before reading contents" {
        val world = world(); val player = player(world)
        val inventory = mockk<Chest>()
        val provider = ChestPreviewProvider(ChestPreviewSettings()) { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(UUID.randomUUID(), 0.5, 64.0, 0.5))
        }
        provider.selectedFrame(player) { provider.resolve(player) } shouldBe null
        verify(exactly = 0) { inventory.blockInventory }
    }
})

private fun world(): World = mockk<World>().also { every { it.uid } returns UUID.randomUUID() }
private fun player(world: World): Player = mockk<Player>().also {
    every { it.hasPermission("arc.chest-preview") } returns true
    every { it.world } returns world
    every { it.uniqueId } returns UUID.randomUUID()
}
private fun chest(contents: Array<out ItemStack?>): Chest = mockk<Chest>().also {
    val inventory = mockk<Inventory>()
    every { inventory.contents } returns Array<ItemStack?>(contents.size) { contents[it] }
    every { it.blockInventory } returns inventory
}
