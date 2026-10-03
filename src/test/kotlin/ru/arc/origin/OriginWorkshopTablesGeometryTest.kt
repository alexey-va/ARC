package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files
import kotlin.math.abs

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

        blocks.size shouldBe 33
        items.size shouldBe 1
        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        val top = blocks.single { it.key == "top" }
        (abs(top.y + top.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        (abs(top.width + 0.20 - dimensions.width) < 1e-9) shouldBe true
        (abs(top.depth + 0.20 - dimensions.depth) < 1e-9) shouldBe true
        val positiveLongEdge = blocks.single { it.key == "long-edge-1.0" }
        (abs(positiveLongEdge.z - positiveLongEdge.depth / 2.0 - top.depth / 2.0) < 1e-9) shouldBe true
        val positiveEndEdge = blocks.single { it.key == "end-edge-1.0" }
        (abs(positiveEndEdge.x - positiveEndEdge.width / 2.0 - top.width / 2.0) < 1e-9) shouldBe true
        items.minOf { it.width } shouldBe 0.62
        items.single().material shouldBe Material.IRON_AXE
        items.all { it.flat } shouldBe true
        val boardSample = blocks.single { it.key == "carpenter-board-sample" }
        (abs(boardSample.y - boardSample.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
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
        val dimensions = OriginWorkshopTableDimensions(width = 4.4, depth = 2.0, height = 1.0)
        val blocks = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        )
            .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }

        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        (abs(blocks.single { it.key == "top" }.let { it.y + it.height / 2.0 } - dimensions.height) < 1e-9) shouldBe true
        (abs(maxOf(
            blocks.maxOf { it.x + it.width / 2.0 },
            blocks.maxOf { it.z + it.depth / 2.0 },
        ) - 2.2) < 1e-9) shouldBe true
        (abs(minOf(
            blocks.minOf { it.x - it.width / 2.0 },
            blocks.minOf { it.z - it.depth / 2.0 },
        ) + 2.2) < 1e-9) shouldBe true
        val props = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        ).filter { it.kind == OriginWorkshopTablePieceKind.ITEM }
        props.map { it.material } shouldBe listOf(Material.BRUSH)
        props.minOf { it.width } shouldBe 0.62
        props.all { it.flat && it.y > dimensions.height && it.y - dimensions.height <= 0.04 } shouldBe true
        val finishedBoard = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        ).single { it.key == "finisher-finished-board" }
        (abs(finishedBoard.y - finishedBoard.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
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
                width: 4.8
                depth: 2.05
                height: 1.08
              warehouse: { enabled: true, x: -49.5, floor-y: 71.0, z: -51.5 }
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
        settings.warehouse shouldBe OriginWorkshopWarehouseAnchor(-49.5, 71.0, -51.5)
    }
})
