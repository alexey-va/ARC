package ru.arc.treasurechests

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.KotestTestBase

class HuntLocationGeneratorTest :
    KotestTestBase({

        describe("HuntLocationGenerator") {
            it("should place chests on solid ground with headroom") {
                val world = server.addSimpleWorld("gen-flat")
                buildFlatPlatform(world, centerX = 0, centerZ = 0, radius = 20, y = 64)

                val center = Location(world, 0.5, 64.0, 0.5)
                val locations =
                    HuntLocationGenerator.generate(
                        world = world,
                        center = center,
                        count = 5,
                        config = HuntLocationGeneratorConfig(horizontalRadius = 15.0, minSeparation = 3.0),
                        random = seededRandom(42),
                    )

                locations shouldHaveSize 5
                locations.forEach { loc ->
                    loc.world shouldBe world
                    loc.y shouldBeGreaterThan 64.0
                    loc.block
                        .getRelative(0, -1, 0)
                        .type.isSolid shouldBe true
                    loc.block.type.isAir shouldBe true
                    loc.block
                        .getRelative(0, 1, 0)
                        .type.isAir shouldBe true
                }
            }

            it("should reject invalid ground materials") {
                HuntLocationGenerator.isValidGround(Material.WATER) shouldBe false
                HuntLocationGenerator.isValidGround(Material.STONE) shouldBe true
                HuntLocationGenerator.isValidGround(Material.OAK_LEAVES) shouldBe false
            }

            it("should keep minimum separation between picks") {
                val world = server.addSimpleWorld("gen-separation")
                buildFlatPlatform(world, centerX = 10, centerZ = 10, radius = 40, y = 80)

                val center = Location(world, 10.5, 80.0, 10.5)
                val locations =
                    HuntLocationGenerator.generate(
                        world = world,
                        center = center,
                        count = 4,
                        config =
                            HuntLocationGeneratorConfig(
                                horizontalRadius = 30.0,
                                minSeparation = 8.0,
                                maxAttemptsPerChest = 200,
                            ),
                        random = seededRandom(7),
                    )

                locations.size shouldBeGreaterThan 1
                locations.forEach { first ->
                    locations.filter { it != first }.forEach { second ->
                        first.distance(second) shouldBeGreaterThan 7.9
                    }
                }
            }
        }
    })

private fun buildFlatPlatform(
    world: World,
    centerX: Int,
    centerZ: Int,
    radius: Int,
    y: Int,
) {
    for (x in (centerX - radius)..(centerX + radius)) {
        for (z in (centerZ - radius)..(centerZ + radius)) {
            world.getBlockAt(x, y, z).type = Material.STONE
            world.getBlockAt(x, y + 1, z).type = Material.AIR
            world.getBlockAt(x, y + 2, z).type = Material.AIR
            world.getBlockAt(x, y + 3, z).type = Material.AIR
        }
    }
}

private fun seededRandom(seed: Long): () -> Double {
    var state = seed
    return {
        state = (state * 6364136223846793005L + 1442695040888963407L)
        ((state ushr 33).toInt() and 0x7FFFFFFF) / Int.MAX_VALUE.toDouble()
    }
}
