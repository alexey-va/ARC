package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.BoundingBox
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteProfile
import ru.arc.worldcontent.FurnitureFamily
import ru.arc.worldcontent.FurnitureRuntime
import ru.arc.worldcontent.RuntimeFurnitureHandle

class OriginSceneObstaclesTest : FreeSpec({
    "scene-owned item displays are skipped before furniture inspection" {
        val world = mockk<World>(relaxed = true)
        val ownedCart = mockk<ItemDisplay>(relaxed = true)
        val ordinaryFurniture = mockk<Entity>(relaxed = true)
        val untaggedProp = mockk<ItemDisplay>(relaxed = true)
        val furnitureRoot = mockk<Entity>(relaxed = true)

        every { ownedCart.scoreboardTags } returns setOf(ORIGIN_SCENE_PROP_TAG)
        every { ordinaryFurniture.scoreboardTags } returns emptySet()
        every { untaggedProp.scoreboardTags } returns emptySet()
        every { ordinaryFurniture.boundingBox } returns BoundingBox(3.1, 63.0, 4.1, 3.8, 65.0, 4.8)
        every { furnitureRoot.boundingBox } returns BoundingBox(5.0, 63.0, 6.0, 6.5, 65.0, 6.5)
        every { furnitureRoot.location } returns Location(world, 5.2, 64.0, 6.2)
        every { world.getNearbyEntities(any<BoundingBox>()) } returns
            listOf<Entity>(ownedCart, ordinaryFurniture, untaggedProp)

        val runtime = TestFurnitureRuntime(ordinaryFurniture, furnitureRoot)
        val profile = NpcRouteProfile("scene-test", 64, NpcRouteBounds(0, 10, 0, 10), entityObstaclePadding = 0.0)

        originFurnitureObstacleCells(world, profile, runtime) shouldBe setOf(
            NpcRouteCell(3, 4),
            NpcRouteCell(5, 6),
            NpcRouteCell(6, 6),
        )
        runtime.inspected shouldBe listOf(ordinaryFurniture, untaggedProp)
    }
})

private class TestFurnitureRuntime(
    private val furnitureEntity: Entity,
    private val furnitureRoot: Entity,
) : FurnitureRuntime {
    val inspected = mutableListOf<Entity>()

    override val available: Boolean = true

    override fun inspect(entity: Entity): RuntimeFurnitureHandle? {
        inspected += entity
        return if (entity == furnitureEntity) {
            RuntimeFurnitureHandle(furnitureRoot, FurnitureFamily.SIMPLE, "test:furniture")
        } else {
            null
        }
    }

    override fun inspect(block: Block): RuntimeFurnitureHandle? = null

    override fun remove(entity: Entity, family: FurnitureFamily): Boolean = false

    override fun spawnBlock(namespacedId: String, block: Block): Entity = error("not used in obstacle tests")

    override fun spawnPreciseNonSolid(namespacedId: String, location: Location): Entity =
        error("not used in obstacle tests")
}
