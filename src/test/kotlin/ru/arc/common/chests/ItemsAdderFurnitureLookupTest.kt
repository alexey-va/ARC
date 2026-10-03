package ru.arc.common.chests

import dev.lone.itemsadder.api.CustomFurniture
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import ru.arc.KotestTestBase

class ItemsAdderFurnitureLookupTest : KotestTestBase({
    it("resolves furniture only at the marked anchor even when a neighbour is found first") {
        val world = server.addSimpleWorld("furniture-lookup")
        val anchor = world.getBlockAt(10, 64, 10)
        val neighbour = anchor.getRelative(1, 0, 0)
        val root = world.spawn(neighbour.location, ItemDisplay::class.java)
        val furniture = mockk<CustomFurniture>()
        every { furniture.entity } returns root
        mockkStatic(CustomFurniture::class)
        try {
            every { CustomFurniture.byAlreadySpawned(any<Block>()) } answers {
                furniture.takeIf { firstArg<Block>() == neighbour }
            }
            every { CustomFurniture.byAlreadySpawned(any<Entity>()) } returns furniture

            ItemsAdderFurnitureLookup.findOnBlocks(anchor) shouldBe null
            ItemsAdderFurnitureLookup.findNearEntities(anchor) shouldBe null
            root.isValid shouldBe true

            root.teleport(anchor.location)
            ItemsAdderFurnitureLookup.findOnBlocks(anchor) shouldBe furniture
            ItemsAdderFurnitureLookup.findNearEntities(anchor) shouldBe furniture
        } finally {
            unmockkStatic(CustomFurniture::class)
        }
    }
})
