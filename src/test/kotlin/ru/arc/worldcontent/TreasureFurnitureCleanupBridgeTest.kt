package ru.arc.worldcontent

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import ru.arc.KotestTestBase

class TreasureFurnitureCleanupBridgeTest :
    KotestTestBase({
        describe("precise owner cleanup") {
            it("removes tracked display and hitbox residuals after native cleanup without touching neighbours") {
                val world = server.addSimpleWorld("display-cleanup")
                val anchor = Location(world, 10.0, 64.0, 10.0)
                val root = world.spawn(anchor, ArmorStand::class.java)
                val visual = world.spawn(anchor, ItemDisplay::class.java)
                val hitbox = world.spawn(anchor, Interaction::class.java)
                val neighbourVisual = world.spawn(anchor, ItemDisplay::class.java)
                val neighbourHitbox = world.spawn(anchor, Interaction::class.java)
                val runtime = mockk<FurnitureRuntime>()
                every { runtime.available } returns true
                every { runtime.inspect(any<Entity>()) } answers {
                    firstArg<Entity>().takeIf { it.uniqueId == root.uniqueId }?.let {
                        RuntimeFurnitureHandle(it, FurnitureFamily.SIMPLE, "iasurvival:treasure")
                    }
                }
                every { runtime.remove(root, FurnitureFamily.SIMPLE) } answers {
                    root.remove()
                    true
                }

                val result = FurnitureCleanupService.executeKnown(
                    anchor,
                    listOf(root.uniqueId, visual.uniqueId, hitbox.uniqueId, visual.uniqueId),
                    emptyList(),
                    runtime,
                )

                result.removedFurniture shouldBe 3
                root.isValid shouldBe false
                visual.isValid shouldBe false
                hitbox.isValid shouldBe false
                neighbourVisual.isValid shouldBe true
                neighbourHitbox.isValid shouldBe true
                FurnitureCleanupService.executeKnown(
                    anchor, listOf(root.uniqueId, visual.uniqueId, hitbox.uniqueId), emptyList(), runtime,
                ).removedFurniture shouldBe 0
            }

            it("keeps a recognized display when native furniture cleanup fails") {
                val world = server.addSimpleWorld("failed-display-cleanup")
                val anchor = Location(world, 10.0, 64.0, 10.0)
                val root = world.spawn(anchor, ItemDisplay::class.java)
                val runtime = mockk<FurnitureRuntime>()
                every { runtime.available } returns true
                every { runtime.inspect(root) } returns RuntimeFurnitureHandle(root, FurnitureFamily.SIMPLE, "iasurvival:treasure")
                every { runtime.remove(root, FurnitureFamily.SIMPLE) } returns false

                val result = FurnitureCleanupService.executeKnown(anchor, listOf(root.uniqueId), emptyList(), runtime)

                result.removedFurniture shouldBe 0
                result.failedFurniture shouldBe listOf(root.uniqueId)
                root.isValid shouldBe true
            }

            it("removes only recognized tracked furniture and exact stored barriers") {
                val world = server.addSimpleWorld("treasure-cleanup")
                val anchor = Location(world, 10.0, 64.0, 10.0)
                val owned = world.spawn(anchor, ArmorStand::class.java)
                val foreign = world.spawn(anchor.clone().add(1.0, 0.0, 0.0), ArmorStand::class.java)
                val barrier = world.getBlockAt(10, 65, 10)
                barrier.type = Material.BARRIER
                val unrelatedBarrier = world.getBlockAt(11, 65, 10)
                unrelatedBarrier.type = Material.BARRIER

                val runtime =
                    object : FurnitureRuntime {
                        override val available = true

                        override fun inspect(entity: Entity): RuntimeFurnitureHandle? =
                            entity.takeIf { it.uniqueId == owned.uniqueId }?.let {
                                RuntimeFurnitureHandle(it, FurnitureFamily.SIMPLE, "iasurvival:treasure")
                            }

                        override fun remove(
                            entity: Entity,
                            family: FurnitureFamily,
                        ): Boolean {
                            entity.remove()
                            return !entity.isValid
                        }

                        override fun spawnBlock(
                            namespacedId: String,
                            block: Block,
                        ): Entity = error("not used")

                        override fun spawnPreciseNonSolid(
                            namespacedId: String,
                            location: Location,
                        ): Entity = error("not used")
                    }

                val result =
                    FurnitureCleanupService.executeKnown(
                        anchor = anchor,
                        entityIds = listOf(owned.uniqueId, foreign.uniqueId),
                        barriers = listOf(BlockPosition(world.name, 10, 65, 10)),
                        runtime = runtime,
                    )

                result.removedFurniture shouldBe 1
                owned.isValid shouldBe false
                foreign.isValid shouldBe true
                barrier.type shouldBe Material.AIR
                unrelatedBarrier.type shouldBe Material.BARRIER
            }
        }
    })
