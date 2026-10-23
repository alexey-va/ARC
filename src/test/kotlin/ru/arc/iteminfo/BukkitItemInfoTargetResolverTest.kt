package ru.arc.iteminfo

import dev.lone.itemsadder.api.CustomFurniture
import dev.lone.itemsadder.api.CustomStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.PluginManager
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.hooks.economyshop.FURNITURE_GALLERY_WORLD
import java.util.UUID
import java.util.function.Predicate

class BukkitItemInfoTargetResolverTest : StringSpec({
    "ray predicates only identify eligible furniture and prepare the exact winning entity label" {
        val world = mockk<World>()
        every { world.name } returns "survival"
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.eyeLocation } returns Location(world, 0.0, 64.0, 0.0)
        every { player.rayTraceBlocks(5.0, FluidCollisionMode.NEVER) } returns null
        val decoy = furnitureEntity(world)
        val winner = furnitureEntity(world)
        val blank = furnitureEntity(world)
        val crate = furnitureEntity(world)
        val decoyDefinition = furnitureDefinition(decoy, "furniture:decoy")
        val winnerDefinition = furnitureDefinition(winner, "furniture:winner")
        val blankDefinition = furnitureDefinition(blank, " ")
        val crateDefinition = furnitureDefinition(crate, "furniture:crate")
        val definitions = linkedMapOf(
            decoy to decoyDefinition,
            winner to winnerDefinition,
            blank to blankDefinition,
            crate to crateDefinition,
        )
        val current = mockk<CustomStack>()
        val label = mockk<ItemStack>()
        every { current.itemStack } returns label
        every { label.itemMeta } returns mockk {
            every { hasDisplayName() } returns true
            every { displayName() } returns Component.text("Current winner")
        }
        val pluginManager = mockk<PluginManager>()
        every { pluginManager.isPluginEnabled("ItemsAdder") } returns true
        val accepted = mutableListOf<Entity>()
        every { world.rayTraceEntities(any(), any(), 5.0, 0.20, any()) } answers {
            val predicate = arg<Predicate<Entity>>(4)
            listOf(decoy, blank, crate, winner).filterTo(accepted, predicate::test)
            RayTraceResult(Vector(0.0, 64.0, 2.0), winner)
        }

        mockkStatic(Bukkit::class, CustomFurniture::class, CustomStack::class)
        try {
            every { Bukkit.getPluginManager() } returns pluginManager
            every { CustomFurniture.byAlreadySpawned(any<Entity>()) } answers { definitions[firstArg<Entity>()] }
            every { CustomStack.getInstance("furniture:winner") } returns current

            val result = BukkitItemInfoTargetResolver(5.0, managedCrate = { it === crate.location }).resolve(player)

            result shouldBe ItemInfoTarget(Component.text("Current winner"), "furniture:winner")
            accepted shouldBe listOf(decoy, winner)
            verify(exactly = 0) { decoyDefinition.itemStack }
            verify(exactly = 0) { blankDefinition.itemStack }
            verify(exactly = 0) { crateDefinition.itemStack }
            verify(exactly = 1) { winnerDefinition.itemStack }
            verify(exactly = 1) { CustomStack.getInstance(any()) }
        } finally {
            unmockkStatic(Bukkit::class, CustomFurniture::class, CustomStack::class)
        }
    }

    "gallery predicates preserve exact native root identity before preparing a label" {
        val world = mockk<World>()
        every { world.name } returns FURNITURE_GALLERY_WORLD
        val player = mockk<Player>()
        every { player.uniqueId } returns UUID.randomUUID()
        every { player.world } returns world
        every { player.eyeLocation } returns Location(world, 0.0, 64.0, 0.0)
        every { player.rayTraceBlocks(5.0, FluidCollisionMode.NEVER) } returns null
        val root = furnitureEntity(world)
        val child = furnitureEntity(world)
        val custom = furnitureDefinition(root, "furniture:chair")
        val accepted = mutableListOf<Entity>()
        every { world.rayTraceEntities(any(), any(), 5.0, 0.0, any()) } answers {
            val predicate = arg<Predicate<Entity>>(4)
            listOf(child, root).filterTo(accepted, predicate::test)
            RayTraceResult(Vector(0.0, 64.0, 2.0), root)
        }
        val pluginManager = mockk<PluginManager>()
        every { pluginManager.isPluginEnabled("ItemsAdder") } returns true

        mockkStatic(Bukkit::class, CustomFurniture::class, CustomStack::class)
        try {
            every { Bukkit.getPluginManager() } returns pluginManager
            every { CustomFurniture.byAlreadySpawned(any<Entity>()) } returns custom
            every { CustomStack.getInstance("furniture:chair") } returns null

            BukkitItemInfoTargetResolver(5.0, managedCrate = { false }).resolve(player)?.namespacedId shouldBe
                "furniture:chair"
            accepted shouldBe listOf(root)
            verify(exactly = 1) { custom.itemStack }
        } finally {
            unmockkStatic(Bukkit::class, CustomFurniture::class, CustomStack::class)
        }
    }
})

private fun furnitureEntity(sourceWorld: World): ItemDisplay = mockk {
    every { uniqueId } returns UUID.randomUUID()
    every { world } returns sourceWorld
    every { location } returns Location(sourceWorld, 0.0, 64.0, 2.0)
}

private fun furnitureDefinition(root: Entity, id: String): CustomFurniture = mockk {
    every { entity } returns root
    every { namespacedID } returns id
    every { itemStack } returns null
}
