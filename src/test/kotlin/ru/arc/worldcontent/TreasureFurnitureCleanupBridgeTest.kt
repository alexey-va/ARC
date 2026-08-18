package ru.arc.worldcontent

import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import ru.arc.KotestTestBase

class TreasureFurnitureCleanupBridgeTest :
    KotestTestBase({
        describe("precise owner cleanup") {
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
