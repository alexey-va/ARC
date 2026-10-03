package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min

class OriginWorkshopTablesGeometryTest : FreeSpec({
    "default table stays grounded and has the authored top height" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val pieces = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 0,
            dimensions = dimensions,
        )
        val blocks = pieces.filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }
        val items = pieces.filter { it.kind == OriginWorkshopTablePieceKind.ITEM }

        blocks.size shouldBe 13
        items.size shouldBe 2
        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        blocks.maxOf { it.y + it.height / 2.0 } shouldBe dimensions.height
        blocks.single { it.key == "top" }.width shouldBe dimensions.width
        blocks.single { it.key == "top" }.depth shouldBe dimensions.depth
    }

    "right-angle yaw swaps the table footprint and rotates role props with it" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val straight = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 0,
            dimensions = dimensions,
        )
        val rotated = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 90,
            dimensions = dimensions,
        )
        val straightTop = straight.single { it.key == "top" }
        val rotatedTop = rotated.single { it.key == "top" }
        val straightTool = straight.first { it.key == "carpenter-prop-0" }
        val rotatedTool = rotated.first { it.key == "carpenter-prop-0" }

        rotatedTop.width shouldBe straightTop.depth
        rotatedTop.depth shouldBe straightTop.width
        rotatedTool.x shouldBe -straightTool.z
        rotatedTool.z shouldBe straightTool.x
    }

    "runtime dimensions preserve floor contact and top alignment" {
        val dimensions = OriginWorkshopTableDimensions(width = 2.4, depth = 1.1, height = 1.0)
        val blocks = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        )
            .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }

        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        blocks.maxOf { it.y + it.height / 2.0 } shouldBe dimensions.height
        max(blocks.maxOf { it.x + it.width / 2.0 }, blocks.maxOf { it.z + it.depth / 2.0 }) shouldBe 1.2
        min(blocks.minOf { it.x - it.width / 2.0 }, blocks.minOf { it.z - it.depth / 2.0 }) shouldBe -1.2
    }

    "runtime YAML schema loads four centered tables and shared dimensions" {
        val dataPath = Files.createTempDirectory("origin-workshop-tables-test")
        val modulesPath = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            modulesPath.resolve("origin-workshop-tables.yml"),
            """
            origin-workshop-tables:
              enabled: true
              world: rc_origin_spawn
              dimensions:
                width: 2.8
                depth: 1.25
                height: 1.04
              table-ids: [carpenter, upholsterer, assembler, finisher]
              tables:
                carpenter: { x: -36.5, floor-y: 71.0, z: -73.5, yaw: 90, role: carpenter }
                upholsterer: { x: -36.5, floor-y: 71.0, z: -58.5, yaw: 90, role: upholsterer }
                assembler: { x: -48.5, floor-y: 71.0, z: -46.5, yaw: 0, role: assembler }
                finisher: { x: -60.5, floor-y: 71.0, z: -46.5, yaw: 0, role: finisher }
            """.trimIndent(),
        )

        val settings = OriginWorkshopTablesSettings.load(dataPath)

        settings.enabled shouldBe true
        settings.tables.map { it.id } shouldBe listOf("carpenter", "upholsterer", "assembler", "finisher")
        settings.tables.map { it.yaw } shouldBe listOf(90, 90, 0, 0)
        settings.tables.first().floorY shouldBe 71.0
        settings.dimensions shouldBe OriginWorkshopTableDimensions.DEFAULT
    }
})
