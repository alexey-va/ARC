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

            it("allows an empty vanilla chest interaction anchor") {
                val world = server.addSimpleWorld("scene-chest-world")
                world.getChunkAt(0, 0).load()
                val block = world.getBlockAt(5, 64, 5)
                val spec =
                    WorldSceneSpec(
                        "chest_scene",
                        listOf(SceneObjectSpec.block("crate", world.name, 5, 64, 5, "minecraft:chest[facing=north,type=single,waterlogged=false]")),
                    )

                manager.apply(spec, manager.preview(spec).reviewDigest)
                block.type shouldBe Material.CHEST

                val deletion = manager.previewDelete("chest_scene")
                manager.delete("chest_scene", deletion.reviewDigest)
                block.type shouldBe Material.AIR
            }
        }

        describe("ItemsAdder furniture ownership") {
            it("replaces only the exact declared legacy furniture with a vanilla chest") {
                val world = server.addSimpleWorld("scene-legacy-furniture-world")
                world.getChunkAt(0, 0).load()
                val runtime = FakeFurnitureRuntime()
                manager =
                    WorldSceneManager(
                        WorldSceneRepository(dataPath.resolve("data/world-scenes-legacy-furniture-test.json")),
                        runtime,
                    )
                val block = world.getBlockAt(7, 64, 7)
                runtime.spawnBlock("ia:old_crate", block)
                val spec =
                    WorldSceneSpec(
                        "legacy_furniture_scene",
                        listOf(
                            SceneObjectSpec.block(
                                "crate",
                                world.name,
                                7,
                                64,
                                7,
                                "minecraft:chest[facing=east,type=single,waterlogged=false]",
                                "ia:old_crate",
                            ),
                        ),
                    )

                val preview = manager.preview(spec)
                manager.apply(spec, preview.reviewDigest)

                block.type shouldBe Material.CHEST
                runtime.removed shouldBe 1
                manager.preview(spec).unchangedCount shouldBe 1

                val updated =
                    spec.copy(
                        objects =
                            listOf(
                                spec.objects.single().copy(
                                    blockData = "minecraft:chest[facing=north,type=single,waterlogged=false]",
                                ),
                            ),
                    )
                manager.apply(updated, manager.preview(updated).reviewDigest)
                runtime.removed shouldBe 1

                val deletion = manager.previewDelete("legacy_furniture_scene")
                manager.delete("legacy_furniture_scene", deletion.reviewDigest)
                block.type shouldBe Material.AIR
            }

            it("refuses to replace a different legacy furniture id") {
                val world = server.addSimpleWorld("scene-wrong-legacy-furniture-world")
                world.getChunkAt(0, 0).load()
                val runtime = FakeFurnitureRuntime()
                manager =
                    WorldSceneManager(
                        WorldSceneRepository(dataPath.resolve("data/world-scenes-wrong-legacy-test.json")),
                        runtime,
                    )
                val block = world.getBlockAt(8, 64, 8)
                runtime.spawnBlock("ia:foreign", block)
                val spec =
                    WorldSceneSpec(
                        "wrong_legacy_scene",
                        listOf(
                            SceneObjectSpec.block("crate", world.name, 8, 64, 8, "minecraft:chest", "ia:expected"),
                        ),
                    )

                runCatching { manager.preview(spec) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<IllegalArgumentException>()
                    .message shouldContain "expected ia:expected, found ia:foreign"
                block.type shouldBe Material.BARRIER
                runtime.removed shouldBe 0
            }

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
    private val anchors = mutableMapOf<String, Entity>()
    var removed: Int = 0
        private set

    override fun inspect(entity: Entity): RuntimeFurnitureHandle? =
        owned[entity.uniqueId]?.let { RuntimeFurnitureHandle(entity, FurnitureFamily.SIMPLE, it) }

    override fun inspect(block: Block): RuntimeFurnitureHandle? =
        anchors[blockKey(block)]?.let(::inspect)

    override fun remove(
        entity: Entity,
        family: FurnitureFamily,
    ): Boolean {
        owned.remove(entity.uniqueId)
        anchors.entries.removeIf { it.value.uniqueId == entity.uniqueId }
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
            anchors[blockKey(block)] = it
        }
    }

    override fun spawnPreciseNonSolid(
        namespacedId: String,
        location: Location,
    ): Entity = location.world.spawn(location, ArmorStand::class.java).also { owned[it.uniqueId] = namespacedId }

    private fun blockKey(block: Block): String = "${block.world.name}:${block.x}:${block.y}:${block.z}"
}
