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
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.block.EnderChest
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.bschests.PersonalLootPreview
import ru.arc.bschests.personalLootWithDebris
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

class ChestPreviewProviderTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    "permission is checked before target resolution or inventory reads" {
        val player = mockk<Player>()
        every { player.hasPermission("arc.chest-preview") } returns false
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> error("must not resolve") },
            settingsFor = { error("must not read settings") },
        )
        provider.resolve(player) shouldBe null
    }

    "unavailable personal loot never falls back to shared physical contents" {
        val world = world(); val viewer = player(world); val chestUuid = UUID.randomUUID()
        val physical = chest(arrayOf(ItemStack(Material.DIAMOND, 4)))
        val target = ChestPreviewTarget(
            listOf(physical),
            InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5),
            blocks = listOf(mockk<Block>()),
            personalLootChestUuid = chestUuid,
        )
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> target },
            personalLootPreview = { _, _, _ -> PersonalLootPreview.Unavailable },
        )

        provider.selectedFrame(viewer) { provider.resolve(viewer) } shouldBe null
        verify(exactly = 0) { physical.blockInventory }
    }

    "warm personal snapshots are isolated by viewer and do not read the physical chest" {
        val world = world(); val firstViewer = player(world); val secondViewer = player(world)
        val chestUuid = UUID.randomUUID()
        val physical = chest(arrayOf(ItemStack(Material.GOLD_INGOT, 9)))
        val target = ChestPreviewTarget(
            listOf(physical),
            InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5),
            blocks = listOf(mockk<Block>()),
            personalLootChestUuid = chestUuid,
        )
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> target },
            personalLootPreview = { viewer, _, _ ->
                PersonalLootPreview.Contents(
                    listOf(ItemStack(if (viewer == firstViewer.uniqueId) Material.DIAMOND else Material.EMERALD)),
                )
            },
        )

        provider.selectedFrame(firstViewer) { provider.resolve(firstViewer) }!!.items.map { it.type } shouldBe
            listOf(Material.DIAMOND)
        provider.selectedFrame(secondViewer) { provider.resolve(secondViewer) }!!.items.map { it.type } shouldBe
            listOf(Material.EMERALD)
        verify(exactly = 0) { physical.blockInventory }
    }

    "confirmed missing BetterStructures data projects the same nonempty template and fillers" {
        val world = world(); val viewer = player(world); val chestUuid = UUID.randomUUID()
        val template = listOf(null, ItemStack(Material.DIAMOND, 3), null)
        val physical = chest(arrayOf(template[0], template[1], template[2]))
        val target = ChestPreviewTarget(
            listOf(physical),
            InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5),
            blocks = listOf(mockk<Block>()),
            personalLootChestUuid = chestUuid,
        )
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> target },
            personalLootPreview = { _, _, _ -> PersonalLootPreview.Contents(emptyList(), usePhysicalTemplate = true) },
        )

        val frame = provider.selectedFrame(viewer) { provider.resolve(viewer) }!!
        frame.items.map { it.type } shouldBe
            personalLootWithDebris(listOf(ItemStack(Material.DIAMOND, 3)), viewer.uniqueId, chestUuid)
                .filterNotNull().map { it.type }
        frame.counts.first() shouldBe 3
        verify(exactly = 1) { physical.blockInventory }
    }

    "empty BetterStructures templates and exhausted personal loot stay empty" {
        val world = world(); val viewer = player(world); val chestUuid = UUID.randomUUID()
        val emptyPhysical = chest(arrayOfNulls(27))
        val block = mockk<Block>()
        val target = ChestPreviewTarget(
            listOf(emptyPhysical),
            InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5),
            blocks = listOf(block),
            personalLootChestUuid = chestUuid,
        )
        val templateProvider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> target },
            personalLootPreview = { _, _, _ -> PersonalLootPreview.Contents(emptyList(), usePhysicalTemplate = true) },
        )
        templateProvider.selectedFrame(viewer) { templateProvider.resolve(viewer) }!!.items shouldBe emptyList()
        verify(exactly = 1) { emptyPhysical.blockInventory }

        val exhaustedProvider = ChestPreviewProvider(
            settings = ChestPreviewSettings(),
            resolveTarget = { _, _ -> target },
            personalLootPreview = { _, _, _ -> PersonalLootPreview.Contents(emptyList()) },
        )
        exhaustedProvider.selectedFrame(viewer) { exhaustedProvider.resolve(viewer) }!!.items shouldBe emptyList()
        verify(exactly = 1) { emptyPhysical.blockInventory }
    }

    "distinct icons retain item appearance and physical slot order without any text" {
        val world = world()
        val player = player(world)
        val diamond = ItemStack(Material.DIAMOND, 3).apply { editMeta { it.displayName(Component.text("Ruby")) } }
        val first = chest(arrayOf(diamond, ItemStack(Material.EMERALD, 2), diamond.clone().apply { amount = 5 }))
        val second = chest(arrayOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD, 4)))
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(first, second), InspectionHologramAnchor(world.uid, 4.5, 70.0, -2.5))
        })
        val frame = provider.selectedFrame(player) {
            val suppression = provider.resolve(player)!!
            suppression.hologram shouldBe Component.empty()
            suppression.bossbar shouldBe Component.empty()
            suppression.suppressesLowerSources shouldBe true
        }!!
        frame.items.map { it.type } shouldBe listOf(Material.DIAMOND, Material.EMERALD, Material.DIAMOND)
        frame.items.map { it.amount } shouldBe listOf(1, 1, 1)
        frame.counts shouldBe listOf(8, 6, 1)
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
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(barrel), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        })
        val frame = provider.selectedFrame(player) { provider.resolve(player) }!!
        frame.items.map { it.type } shouldBe listOf(Material.COPPER_INGOT)
        frame.counts shouldBe listOf(1)
        verify(exactly = 1) { barrel.inventory }
    }

    "selected stack counts aggregate across halves after the unique icon limit is reached" {
        val world = world(); val player = player(world)
        val first = chest(arrayOf(ItemStack(Material.DIAMOND, 64), ItemStack(Material.STONE)))
        val second = chest(arrayOf(
            ItemStack(Material.EMERALD, 4),
            ItemStack(Material.DIAMOND, 12),
            ItemStack(Material.STONE, 2),
        ))
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(maxItems = 2),
            resolveTarget = { _, _ ->
                ChestPreviewTarget(listOf(first, second), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
            },
        )

        val frame = provider.selectedFrame(player) { provider.resolve(player) }!!
        frame.items.map { it.type } shouldBe listOf(Material.DIAMOND, Material.STONE)
        frame.items.map { it.amount } shouldBe listOf(1, 1)
        frame.counts shouldBe listOf(76, 3)
        verify(exactly = 1) { first.blockInventory }
        verify(exactly = 1) { second.blockInventory }
    }

    "two-container scans stop at 54 physical slots" {
        val world = world(); val player = player(world)
        val first = chest(Array(32) { ItemStack(Material.PAPER) })
        val secondContents = Array(32) { ItemStack(Material.PAPER) }
        secondContents[22] = ItemStack(Material.DIAMOND, 64)
        val second = chest(secondContents)
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(first, second), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        })

        val frame = provider.selectedFrame(player) { provider.resolve(player) }!!
        frame.items.map { it.type } shouldBe listOf(Material.PAPER)
        frame.counts shouldBe listOf(54)
    }

    "per-viewer settings control enabled state, target distance, icon cap and gap" {
        val world = world(); val viewer = player(world); val disabled = player(world)
        val inventory = chest(arrayOf(
            ItemStack(Material.DIAMOND, 2),
            ItemStack(Material.EMERALD, 3),
            ItemStack(Material.GOLD_INGOT, 4),
        ))
        val resolvedDistances = mutableListOf<Double>()
        val provider = ChestPreviewProvider(
            settings = ChestPreviewSettings(maxItems = 1, maxDistance = 1.0),
            resolveTarget = { _, distance ->
                resolvedDistances += distance
                ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
            },
            settingsFor = { player ->
                if (player.uniqueId == disabled.uniqueId) ChestPreviewSettings(enabled = false)
                else ChestPreviewSettings(maxItems = 2, maxDistance = 2.0, verticalGap = 0.25)
            },
        )

        provider.resolve(disabled) shouldBe null
        resolvedDistances shouldBe emptyList()
        verify(exactly = 0) { inventory.blockInventory }

        val frame = provider.selectedFrame(viewer) { provider.resolve(viewer) }!!
        resolvedDistances shouldBe listOf(2.0)
        frame.items.map { it.type } shouldBe listOf(Material.DIAMOND, Material.EMERALD)
        frame.counts shouldBe listOf(2, 3)
        frame.anchor shouldBe InspectionHologramAnchor(world.uid, 0.5, 64.25, 0.5)
        verify(exactly = 1) { inventory.blockInventory }
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
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(enderChest), InspectionHologramAnchor(world.uid, 1.5, 64.0, 0.5))
        })

        provider.selectedFrame(viewer) { provider.resolve(viewer) }!!.let { frame ->
            frame.items.map { it.type } shouldBe listOf(Material.ENDER_PEARL)
            frame.counts shouldBe listOf(3)
        }
        provider.selectedFrame(other) { provider.resolve(other) }!!.let { frame ->
            frame.items.map { it.type } shouldBe listOf(Material.DIAMOND)
            frame.counts shouldBe listOf(2)
        }
        provider.selectedFrame(viewer) { provider.resolve(viewer) }!!.let { frame ->
            frame.items.map { it.type } shouldBe listOf(Material.ENDER_PEARL)
            frame.counts shouldBe listOf(3)
        }
        verify(exactly = 2) { viewer.enderChest }
        verify(exactly = 1) { other.enderChest }
    }

    "an unresolved Ender Chest never reads private contents" {
        val world = world(); val viewer = player(world)
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ -> null })
        provider.selectedFrame(viewer) { provider.resolve(viewer) } shouldBe null
        verify(exactly = 0) { viewer.enderChest }
    }

    "max items caps icons and empty inventory has no empty-state label" {
        val world = world(); val player = player(world)
        var inventory = chest(arrayOf(ItemStack(Material.DIAMOND), ItemStack(Material.EMERALD), ItemStack(Material.GOLD_INGOT)))
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(maxItems = 2), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        })
        provider.selectedFrame(player) { provider.resolve(player) }!!.items.size shouldBe 2
        inventory = chest(arrayOfNulls(27))
        provider.selectedFrame(player) { provider.resolve(player) }!!.items shouldBe emptyList()
    }

    "a higher priority winner or OFF does not retain the previous scene" {
        val world = world(); val player = player(world)
        val inventory = chest(arrayOf(ItemStack(Material.DIAMOND)))
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        })
        provider.selectedFrame(player) { provider.resolve(player) }!!.items.size shouldBe 1
        provider.selectedFrame(player) { /* shared inspector did not select this provider */ } shouldBe null
        provider.resolve(player) // Out-of-cycle registration resolution must not cache a scene.
        provider.selectedFrame(player) {} shouldBe null
    }

    "capture never shares another viewer's inventory" {
        val world = world(); val first = player(world); val second = player(world)
        val inventory = chest(arrayOf(ItemStack(Material.PAPER)))
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(world.uid, 0.5, 64.0, 0.5))
        })
        provider.selectedFrame(first) { provider.resolve(second) } shouldBe null
    }

    "wrong-world targets are rejected before reading contents" {
        val world = world(); val player = player(world)
        val inventory = mockk<Chest>()
        val provider = ChestPreviewProvider(settings = ChestPreviewSettings(), resolveTarget = { _, _ ->
            ChestPreviewTarget(listOf(inventory), InspectionHologramAnchor(UUID.randomUUID(), 0.5, 64.0, 0.5))
        })
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
