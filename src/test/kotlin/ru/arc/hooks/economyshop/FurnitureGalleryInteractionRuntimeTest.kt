package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.abs

class FurnitureGalleryInteractionRuntimeTest : StringSpec({
    "tracks native roots for sight queries, removes legacy markers, and never creates Interaction entities" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val plugin = paper.createSimplePlugin("FurnitureGalleryRuntimeTest")
            val world = paper.addSimpleWorld(FURNITURE_GALLERY_WORLD)
            world.getChunkAt(0, 0)
            val id = "decor:oak_table"
            val root = world.spawn(Location(world, 1.5, 64.0, 1.5), ArmorStand::class.java)
            val profile = FurnitureGalleryTargetPlanner.profile(
                id,
                listOf(
                    FurnitureGalleryVertex(-0.6, 0.0, -0.3),
                    FurnitureGalleryVertex(0.6, 1.2, 0.3),
                ),
            )
            val runtime = FurnitureGalleryInteractionRuntime(
                plugin = plugin,
                profiles = mapOf(id to profile),
                rootResolver = FurnitureGalleryNativeRootResolver { entity -> id.takeIf { entity.uniqueId == root.uniqueId } },
            )
            val markerV1 = world.spawn(Location(world, 1.5, 64.0, 1.5), Interaction::class.java)
            val markerV2 = world.spawn(Location(world, 2.5, 64.0, 1.5), Interaction::class.java)
            val ownerKey = NamespacedKey(plugin, "furniture-gallery-hitbox")
            markerV1.persistentDataContainer.set(ownerKey, PersistentDataType.BYTE, 1.toByte())
            markerV2.persistentDataContainer.set(ownerKey, PersistentDataType.BYTE, 2.toByte())
            val unrelated = world.spawn(Location(world, 4.0, 64.0, 4.0), Interaction::class.java)

            val player = mockk<Player>()
            var eye = Location(world, 1.5, 65.0, 0.5, 0.0f, 0.0f)
            every { player.world } returns world
            every { player.eyeLocation } answers { eye }
            every { player.rayTraceBlocks(any<Double>(), any<FluidCollisionMode>()) } returns null

            try {
                runtime.start()
                scheduler.tick(1)

                markerV1.isValid shouldBe false
                markerV2.isValid shouldBe false
                world.entities.filterIsInstance<Interaction>() shouldHaveSize 1
                val sight = runtime.targetInSight(player, 5.0)
                sight shouldNotBe null
                sight?.root?.uniqueId shouldBe root.uniqueId
                sight?.furnitureId shouldBe id
                sight?.targetKey shouldBe "native:${root.uniqueId}"
                abs(requireNotNull(sight).distance - 0.7) shouldBeLessThanOrEqual 1.0e-6

                root.teleport(Location(world, 5.5, 64.0, 5.5, 90.0f, 0.0f))
                eye = Location(world, 5.5, 65.0, 4.2, 0.0f, 0.0f)
                val movedSight = runtime.targetInSight(player, 5.0)
                movedSight?.root?.uniqueId shouldBe root.uniqueId
                abs(requireNotNull(movedSight).distance - 0.7) shouldBeLessThanOrEqual 1.0e-6

                root.remove()
                runtime.targetInSight(player, 5.0) shouldBe null
                runtime.close()
                unrelated.isValid shouldBe true
                world.entities.filterIsInstance<Interaction>() shouldHaveSize 1
                scheduler.pendingCount() shouldBe 0
                scheduler.timerCount() shouldBe 0
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }

    "uses native root bounds for unprofiled furniture and respects block occlusion except for its own barrier" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val plugin = paper.createSimplePlugin("FurnitureGalleryOcclusionTest")
            val world = paper.addSimpleWorld(FURNITURE_GALLERY_WORLD)
            world.getChunkAt(0, 0)
            val id = "decor:unprofiled_chair"
            val root = world.spawn(Location(world, 1.5, 64.0, 1.5), ArmorStand::class.java)
            val runtime = FurnitureGalleryInteractionRuntime(
                plugin = plugin,
                profiles = emptyMap(),
                rootResolver = object : FurnitureGalleryNativeRootResolver {
                    override fun furnitureId(entity: Entity): String? = id.takeIf { entity.uniqueId == root.uniqueId }

                    override fun furnitureRoot(block: org.bukkit.block.Block): Entity? = root.takeIf {
                        block.world.uid == world.uid && block.x == 1 && block.y == 64 && block.z == 1
                    }
                },
            )
            val player = mockk<Player>()
            every { player.world } returns world
            every { player.eyeLocation } returns Location(world, 1.5, 65.0, 0.5, 0.0f, 0.0f)
            var blockHit: RayTraceResult? = null
            every { player.rayTraceBlocks(any<Double>(), any<FluidCollisionMode>()) } answers { blockHit }

            try {
                runtime.start()
                scheduler.tick(1)

                // A ray first meeting the anchor barrier still resolves the native root.
                val ownBarrier = mockk<RayTraceResult>()
                every { ownBarrier.hitBlock } returns world.getBlockAt(1, 64, 1)
                every { ownBarrier.hitPosition } returns Vector(1.5, 65.0, 1.0)
                blockHit = ownBarrier
                runtime.targetInSight(player, 5.0)?.root?.uniqueId shouldBe root.uniqueId

                // A different block before the furniture box occludes the query.
                val wall = mockk<RayTraceResult>()
                every { wall.hitBlock } returns world.getBlockAt(1, 64, 0)
                every { wall.hitPosition } returns Vector(1.5, 65.0, 0.9)
                blockHit = wall
                runtime.targetInSight(player, 5.0) shouldBe null
                world.entities.filterIsInstance<Interaction>() shouldHaveSize 0
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }

    "validates native ItemsAdder purchase identity independently of geometry profile availability" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val plugin = paper.createSimplePlugin("FurnitureGalleryNativeClickTest")
            val gallery = paper.addSimpleWorld(FURNITURE_GALLERY_WORLD)
            val elsewhere = paper.addSimpleWorld("survival")
            val root = gallery.spawn(Location(gallery, 1.5, 64.0, 1.5), ArmorStand::class.java)
            val runtime = FurnitureGalleryInteractionRuntime(
                plugin = plugin,
                profiles = emptyMap(),
                rootResolver = FurnitureGalleryNativeRootResolver { entity ->
                    "decor:unprofiled".takeIf { entity.uniqueId == root.uniqueId }
                },
            )

            try {
                runtime.nativeFurnitureIdentity("decor:unprofiled", root) shouldBe "native:${root.uniqueId}"
                runtime.nativeFurnitureIdentity("decor:other", root) shouldBe null
                root.teleport(Location(elsewhere, 1.5, 64.0, 1.5))
                runtime.nativeFurnitureIdentity("decor:unprofiled", root) shouldBe null
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }
})
