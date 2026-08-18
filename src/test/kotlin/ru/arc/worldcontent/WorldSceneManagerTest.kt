package ru.arc.worldcontent

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import ru.arc.KotestTestBase

class WorldSceneManagerTest :
    KotestTestBase({
        lateinit var manager: WorldSceneManager

        beforeTest {
            manager =
                WorldSceneManager(
                    WorldSceneRepository(dataPath.resolve("data/world-scenes-test.json")),
                    UnavailableFurnitureRuntime,
                )
        }

        describe("block scene reconciliation") {
            it("previews, applies idempotently, reads back, and restores the block on delete") {
                val world = server.addSimpleWorld("scene-world")
                world.getChunkAt(0, 0).load()
                val block = world.getBlockAt(3, 64, 5)
                block.type = Material.DIRT
                val spec =
                    WorldSceneSpec(
                        "spawn_decor",
                        listOf(SceneObjectSpec.block("floor", world.name, 3, 64, 5, "minecraft:polished_andesite")),
                    )

                val preview = manager.preview(spec)
                preview.createCount shouldBe 1
                manager.apply(spec, preview.reviewDigest).revision shouldBe 1
                block.type shouldBe Material.POLISHED_ANDESITE
                manager.get("spawn_decor")!!.objects.single().id shouldBe "floor"

                val noop = manager.preview(spec)
                noop.unchangedCount shouldBe 1
                noop.createCount shouldBe 0
                noop.updateCount shouldBe 0

                val deletePreview = manager.previewDelete("spawn_decor")
                deletePreview.deleteCount shouldBe 1
                manager.delete("spawn_decor", deletePreview.reviewDigest)
                block.type shouldBe Material.DIRT
                manager.get("spawn_decor")!!.objects.size shouldBe 0
            }

            it("refuses a stale review when the live preimage changes") {
                val world = server.addSimpleWorld("scene-stale-world")
                world.getChunkAt(0, 0).load()
                val block = world.getBlockAt(8, 64, 8)
                block.type = Material.DIRT
                val spec =
                    WorldSceneSpec(
                        "stale_scene",
                        listOf(SceneObjectSpec.block("floor", world.name, 8, 64, 8, "minecraft:stone")),
                    )
                val preview = manager.preview(spec)
                block.type = Material.GRASS_BLOCK

                runCatching { manager.apply(spec, preview.reviewDigest) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<SceneReviewConflictException>()
                    .message shouldContain "stale"
                block.type shouldBe Material.GRASS_BLOCK
            }

            it("refuses ownership overlap between two scenes") {
                val world = server.addSimpleWorld("scene-ownership-world")
                world.getChunkAt(0, 0).load()
                val first = WorldSceneSpec("first_scene", listOf(SceneObjectSpec.block("floor", world.name, 9, 64, 9, "stone")))
                manager.apply(first, manager.preview(first).reviewDigest)
                val second = WorldSceneSpec("second_scene", listOf(SceneObjectSpec.block("other", world.name, 9, 64, 9, "dirt")))

                runCatching { manager.preview(second) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "already managed"
            }

            it("rolls back to the previous reviewed scene revision") {
                val world = server.addSimpleWorld("scene-rollback-world")
                world.getChunkAt(0, 0).load()
                val block = world.getBlockAt(4, 64, 4)
                block.type = Material.DIRT
                val stone = WorldSceneSpec("rollback_scene", listOf(SceneObjectSpec.block("floor", world.name, 4, 64, 4, "stone")))
                manager.apply(stone, manager.preview(stone).reviewDigest)
                val andesite = stone.copy(objects = listOf(SceneObjectSpec.block("floor", world.name, 4, 64, 4, "polished_andesite")))
                manager.apply(andesite, manager.preview(andesite).reviewDigest)

                val rollback = manager.previewRollback("rollback_scene")
                manager.rollback("rollback_scene", rollback.reviewDigest)

                block.type shouldBe Material.STONE
                manager.get("rollback_scene")!!.objects.single().blockData shouldBe "stone"
            }
        }

        describe("ItemsAdder furniture ownership") {
            it("records exact generated barriers and clears them through native removal") {
                val world = server.addSimpleWorld("scene-furniture-world")
                world.getChunkAt(0, 0).load()
                val runtime = FakeFurnitureRuntime()
                manager =
                    WorldSceneManager(
                        WorldSceneRepository(dataPath.resolve("data/world-scenes-furniture-test.json")),
                        runtime,
                    )
                val spec =
                    WorldSceneSpec(
                        "furniture_scene",
                        listOf(
                            SceneObjectSpec(
                                id = "bench",
                                kind = SceneObjectKind.ITEMSADDER_FURNITURE,
                                world = world.name,
                                x = 6.0,
                                y = 64.0,
                                z = 6.0,
                                namespacedId = "ia:bench",
                                placement = FurniturePlacement.BLOCK,
                            ),
                        ),
                    )

                manager.apply(spec, manager.preview(spec).reviewDigest)
                val state = manager.state("furniture_scene")!!.objects.single()
                state.barriers shouldBe listOf(BlockPosition(world.name, 6, 64, 6))
                world.getBlockAt(6, 64, 6).type shouldBe Material.BARRIER

                val deletion = manager.previewDelete("furniture_scene")
                manager.delete("furniture_scene", deletion.reviewDigest)
                world.getBlockAt(6, 64, 6).type shouldBe Material.AIR
                runtime.removed shouldBe 1
            }
        }
    })

private object UnavailableFurnitureRuntime : FurnitureRuntime {
    override val available = false

    override fun inspect(entity: Entity): RuntimeFurnitureHandle? = null

    override fun remove(
        entity: Entity,
        family: FurnitureFamily,
    ): Boolean = false

    override fun spawnBlock(
        namespacedId: String,
        block: Block,
    ): Entity = error("not available")

    override fun spawnPreciseNonSolid(
        namespacedId: String,
        location: Location,
    ): Entity = error("not available")
}

private class FakeFurnitureRuntime : FurnitureRuntime {
    override val available = true
    private val owned = mutableMapOf<java.util.UUID, String>()
    var removed: Int = 0
        private set

    override fun inspect(entity: Entity): RuntimeFurnitureHandle? =
        owned[entity.uniqueId]?.let { RuntimeFurnitureHandle(entity, FurnitureFamily.SIMPLE, it) }

    override fun remove(
        entity: Entity,
        family: FurnitureFamily,
    ): Boolean {
        owned.remove(entity.uniqueId)
        entity.remove()
        removed++
        return !entity.isValid
    }

    override fun spawnBlock(
        namespacedId: String,
        block: Block,
    ): Entity {
        block.type = Material.BARRIER
        return block.world.spawn(block.location.add(0.5, 0.0, 0.5), ArmorStand::class.java).also {
            owned[it.uniqueId] = namespacedId
        }
    }

    override fun spawnPreciseNonSolid(
        namespacedId: String,
        location: Location,
    ): Entity = location.world.spawn(location, ArmorStand::class.java).also { owned[it.uniqueId] = namespacedId }
}
