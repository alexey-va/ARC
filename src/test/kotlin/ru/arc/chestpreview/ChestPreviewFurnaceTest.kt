package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.block.BlastFurnace
import org.bukkit.block.Furnace
import org.bukkit.block.Smoker
import org.bukkit.inventory.FurnaceInventory
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.bschests.PersonalLootPreview
import java.util.UUID

class ChestPreviewFurnaceTest : StringSpec({
    beforeSpec { MockBukkit.mock() }
    afterSpec { MockBukkit.unmock() }

    val materials = listOf(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER)

    "every furnace type previews input fuel and output and refreshes their amounts" {
        for (material in materials) {
            val fixture = PreviewChestFixture()
            val state = furnace(material)
            val block = fixture.special(material, state)
            val inventory = mockk<FurnaceInventory>()
            var contents = arrayOf<ItemStack?>(
                ItemStack(Material.IRON_ORE, 12), ItemStack(Material.COAL, 4), ItemStack(Material.IRON_INGOT, 3),
            )
            every { state.inventory } returns inventory
            every { inventory.contents } answers { contents }
            every { fixture.player.hasPermission("arc.chest-preview") } returns true
            every { fixture.player.uniqueId } returns UUID.randomUUID()
            val access = ChestPreviewAccess({ _, _ -> true }, { PersonalLootChestMarker.Unmarked })
            val provider = ChestPreviewProvider(
                settings = ChestPreviewSettings(),
                resolveTarget = { player, _ -> access.resolveBlock(player, block) },
                personalLootPreview = { _, _, _ -> PersonalLootPreview.NotPersonal },
            )

            val first = provider.selectedFrame(fixture.player) { provider.resolve(fixture.player) }!!
            first.items.map { it.type } shouldBe listOf(Material.IRON_ORE, Material.COAL, Material.IRON_INGOT)
            first.counts shouldBe listOf(12, 4, 3)
            first.items.map { it.amount } shouldBe listOf(1, 1, 1)
            contents.map { it!!.amount } shouldBe listOf(12, 4, 3)

            contents = arrayOf(null, ItemStack(Material.COAL, 3), ItemStack(Material.IRON_INGOT, 15))
            val changed = provider.selectedFrame(fixture.player) { provider.resolve(fixture.player) }!!
            changed.items.map { it.type } shouldBe listOf(Material.COAL, Material.IRON_INGOT)
            changed.counts shouldBe listOf(3, 15)
            verify(exactly = 2) { inventory.contents }
        }
    }

    "denied furnace protection never acquires the inventory" {
        for (material in materials) {
            val fixture = PreviewChestFixture()
            val state = furnace(material)
            val block = fixture.special(material, state)
            val access = ChestPreviewAccess({ _, _ -> false }, { error("must not inspect a denied container") })

            access.resolveBlock(fixture.player, block) shouldBe null
            verify(exactly = 0) { state.inventory }
        }
    }

    "locked furnaces are rejected before protection and inventory access" {
        for (material in materials) {
            val fixture = PreviewChestFixture()
            val state = furnace(material)
            val block = fixture.special(material, state)
            every { state.isLocked } returns true
            val access = ChestPreviewAccess({ _, _ -> error("must not authorize a locked container") })

            access.resolveBlock(fixture.player, block) shouldBe null
            verify(exactly = 0) { state.inventory }
        }
    }
})

private fun furnace(material: Material): Furnace = when (material) {
    Material.BLAST_FURNACE -> mockk<BlastFurnace>()
    Material.SMOKER -> mockk<Smoker>()
    else -> mockk<Furnace>()
}.also { every { it.isLocked } returns false }
