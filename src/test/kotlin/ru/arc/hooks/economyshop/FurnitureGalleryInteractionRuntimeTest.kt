package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class FurnitureGalleryInteractionRuntimeTest : StringSpec({
    "discovers exact loaded roots, self-heals their responsive markers, and cleans only owned entities" {
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
                hasPurchaseOffer = { it == id },
                rootResolver = FurnitureGalleryNativeRootResolver { entity -> id.takeIf { entity.uniqueId == root.uniqueId } },
            )
            val unrelated = world.spawn(Location(world, 4.0, 64.0, 4.0), Interaction::class.java)

            try {
                runtime.start()
                scheduler.tick(1)
                var owned = world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned)
                owned shouldHaveSize 2
                owned.forEach { marker ->
                    marker.isResponsive shouldBe true
                    marker.isPersistent shouldBe false
                    marker.interactionHeight shouldBe 1.2f
                    runtime.targetForMarker(marker)?.rootId shouldBe root.uniqueId
                    runtime.markers.read(marker)?.targetKey shouldBe "native:${root.uniqueId}"
                    runtime.markers.read(marker)?.furnitureId shouldBe id
                }

                // A geometry change invalidates the old marker immediately, before the periodic repair.
                root.teleport(Location(world, 5.5, 64.0, 5.5, 90.0f, 0.0f))
                runtime.targetForMarker(owned.first()) shouldBe null
                scheduler.tick(100)
                owned = world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned)
                owned shouldHaveSize 2
                owned.all { it.location.x in 5.0..6.0 && it.location.z in 5.0..6.0 } shouldBe true

                // A deleted segment is recreated from the same cached root plan.
                owned.first().remove()
                scheduler.tick(100)
                world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned) shouldHaveSize 2

                root.remove()
                scheduler.tick(100)
                world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned) shouldHaveSize 0

                runtime.close()
                unrelated.isValid shouldBe true
                scheduler.pendingCount() shouldBe 0
                scheduler.timerCount() shouldBe 0
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }

    "native ItemsAdder purchase identity does not depend on a generated geometry profile" {
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
                hasPurchaseOffer = { false },
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

    "temporarily suppresses only the targeted root markers for ItemsAdder's original swing" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val plugin = paper.createSimplePlugin("FurnitureGallerySwingTest")
            val world = paper.addSimpleWorld(FURNITURE_GALLERY_WORLD)
            world.getChunkAt(0, 0)
            val id = "decor:oak_table"
            val root = world.spawn(Location(world, 1.5, 64.0, 1.5), ArmorStand::class.java)
            val otherRoot = world.spawn(Location(world, 5.5, 64.0, 5.5), ArmorStand::class.java)
            val rootIds = setOf(root.uniqueId, otherRoot.uniqueId)
            val profile = FurnitureGalleryTargetPlanner.profile(
                id,
                listOf(
                    FurnitureGalleryVertex(-0.6, 0.0, -0.3),
                    FurnitureGalleryVertex(0.6, 1.2, 0.3),
                ),
            )
            var targetedEntity: Entity? = null
            val runtime = FurnitureGalleryInteractionRuntime(
                plugin = plugin,
                profiles = mapOf(id to profile),
                hasPurchaseOffer = { it == id },
                rootResolver = FurnitureGalleryNativeRootResolver { entity ->
                    id.takeIf { entity.uniqueId in rootIds }
                },
                swingTargetResolver = FurnitureGallerySwingTargetResolver { targetedEntity },
            )
            val player = mockk<Player>(relaxed = true)
            every { player.world } returns world

            try {
                runtime.start()
                scheduler.tick(1)
                val owned = world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned)
                owned shouldHaveSize 4
                val rootMarkers = owned.filter { runtime.markers.read(it)?.targetKey == "native:${root.uniqueId}" }
                val otherRootMarkers = owned.filter { runtime.markers.read(it)?.targetKey == "native:${otherRoot.uniqueId}" }
                rootMarkers shouldHaveSize 2
                otherRootMarkers shouldHaveSize 2
                targetedEntity = rootMarkers.first()

                runtime.onNativeFurnitureSwing(PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING))
                rootMarkers.all { it.interactionWidth == 0.0f && it.interactionHeight == 0.0f } shouldBe true
                otherRootMarkers.all { it.interactionWidth > 0.0f && it.interactionHeight > 0.0f } shouldBe true

                // The original swing reaches ItemsAdder before markers are restored on the next tick.
                scheduler.tick(1)
                rootMarkers.all { it.interactionWidth > 0.0f && it.interactionHeight > 0.0f } shouldBe true
                otherRootMarkers.all { it.interactionWidth > 0.0f && it.interactionHeight > 0.0f } shouldBe true

                // A non-ARC target cannot temporarily change any marker.
                targetedEntity = null
                runtime.onNativeFurnitureSwing(PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING))
                rootMarkers.all { it.interactionWidth > 0.0f && it.interactionHeight > 0.0f } shouldBe true
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }
})
