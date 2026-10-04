package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import kotlin.math.floor

class OriginFurnitureWorkshopConfigTest :
    FreeSpec({
        lateinit var paper: MockBukkitTestRuntime
        beforeEach { paper = MockBukkitTestRuntime.open() }
        afterEach {
            ConfigManager.clear()
            paper.close()
        }

        "negative endpoint search radius rejects an operator override before runtime replacement" {
            val dataPath = Files.createTempDirectory("origin-furniture-workshop-invalid")
            try {
                val modulePath = dataPath.resolve("modules/origin-furniture-workshop.yml")
                Files.createDirectories(modulePath.parent)
                val bundled = OriginFurnitureWorkshopConfigTest::class.java.classLoader
                    .getResourceAsStream("modules/origin-furniture-workshop.yml")!!
                    .bufferedReader().use { it.readText() }
                Files.writeString(modulePath, bundled.replace("snap-radius: 3", "snap-radius: -1"))

                shouldThrow<IllegalArgumentException> {
                    OriginFurnitureWorkshopSettings.load(dataPath)
                }.message shouldBe "workshop route snap-radius must be within 0..8 cells"
            } finally {
                dataPath.toFile().deleteRecursively()
            }
        }

        "bundled workshop config keeps the three existing actors on safe centered routes" {
            val dataPath = Files.createTempDirectory("origin-furniture-workshop-test")
            try {
                val settings = OriginFurnitureWorkshopSettings.load(dataPath)

                settings.enabled shouldBe false
                settings.world shouldBe "rc_origin_spawn"
                settings.workers.map { it.role.npcId } shouldBe listOf(430, 458, 459)
                settings.workers.map { it.productId } shouldBe listOf(
                    "furnituresplus:white_wooden_chair",
                    "furnituresplus:red_wooden_sofa_single",
                    "furnituresplus:white_wooden_diningtable",
                )
                settings.workers.map { Triple(it.output.x, it.output.y, it.output.z) } shouldBe listOf(
                    Triple(-52.0, 71.18, -51.1),
                    Triple(-49.5, 71.18, -51.1),
                    Triple(-46.5, 71.18, -51.1),
                )

                settings.workers.forEach { worker ->
                    worker.parts.size shouldBe when (worker.role) {
                        OriginFurnitureWorkshopRole.ASSEMBLER -> 3
                        else -> 2
                    }
                    worker.beats.isNotEmpty() shouldBe true
                    listOf(
                        worker.home to (worker.pickupRoute to worker.pickup),
                        worker.pickup to (worker.workReturnRoute to worker.workApproach),
                        worker.workApproach to (worker.outputRoute to worker.outputApproach),
                        worker.outputApproach to (worker.homeReturnRoute to worker.home),
                    ).forEach { (route, target) ->
                        val legs = target.first
                        val endpoint = target.second
                        legs.last().point.sameCell(endpoint) shouldBe true
                        val points = listOf(route) + legs.map { it.point }
                        points.zipWithNext().forEach { (from, to) ->
                            (from.x == to.x || from.z == to.z) shouldBe true
                        }
                        legs.forEach { leg ->
                            isWorkshopCellCenter(leg.point.x) shouldBe true
                            isWorkshopCellCenter(leg.point.z) shouldBe true
                            floor(leg.point.y).toInt() shouldBe settings.routeProfile.floorY
                            settings.routeProfile.allows(leg.point.cell()) shouldBe true
                        }
                    }
                }
            } finally {
                dataPath.toFile().deleteRecursively()
            }
        }
    })
