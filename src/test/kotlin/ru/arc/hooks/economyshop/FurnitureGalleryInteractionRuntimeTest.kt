package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.entity.Interaction
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class FurnitureGalleryInteractionRuntimeTest : StringSpec({
    "waits for the exact native root, repairs a missing target, then cleans up only owned targets" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.install(scheduler)
            val plugin = paper.createSimplePlugin("FurnitureGalleryRuntimeTest")
            val world = paper.addSimpleWorld(FURNITURE_GALLERY_WORLD)
            world.getChunkAt(0, 0)
            var nativeRootAvailable = false
            val target = FurnitureGalleryTargetPlanner.create(
                key = "room_01/table_01",
                furnitureId = "decor:oak_table",
                anchor = FurnitureGalleryAnchor(1.5, 64.0, 1.5, 0.0),
                bounds = FurnitureGalleryBounds(1.2, 64.0, 1.2, 1.8, 65.0, 1.8),
            )
            val runtime = FurnitureGalleryInteractionRuntime(plugin, listOf(target)) { _, _ ->
                nativeRootAvailable
            }
            val unrelated = world.spawn(Location(world, 4.0, 64.0, 4.0), Interaction::class.java)

            try {
                runtime.start()
                scheduler.tick(1)
                world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned) shouldHaveSize 0

                nativeRootAvailable = true
                scheduler.tick(20)
                var owned = world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned)
                owned shouldHaveSize 1
                owned.single().isResponsive shouldBe true
                owned.single().isPersistent shouldBe false
                owned.single().interactionWidth shouldBe target.segments.single().width.toFloat()
                owned.single().interactionHeight shouldBe target.segments.single().height.toFloat()

                owned.single().remove()
                scheduler.tick(100)
                owned = world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned)
                owned shouldHaveSize 1

                runtime.close()
                world.entities.filterIsInstance<Interaction>().filter(runtime.markers::isOwned) shouldHaveSize 0
                unrelated.isValid shouldBe true
                scheduler.pendingCount() shouldBe 0
                scheduler.timerCount() shouldBe 0
            } finally {
                runtime.close()
                Tasks.reset()
            }
        }
    }
})
