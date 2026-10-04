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

        (blocks.size <= 95) shouldBe true
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

        val saw = blocks.single { it.key == "carpenter-table-saw" }
        saw.material shouldBe Material.STONECUTTER
        saw.width shouldBe 0.92
        saw.height shouldBe 0.72
        val bladeRows = blocks.filter { it.key.startsWith("carpenter-saw-blade-row-") }.sortedBy { it.y }
        bladeRows.size shouldBe 9
        bladeRows.map { it.width } shouldBe listOf(0.25, 0.45, 0.60, 0.72, 0.78, 0.72, 0.60, 0.45, 0.25)
        (bladeRows.minOf { it.y - it.height / 2.0 } > dimensions.height) shouldBe true
        (bladeRows.maxOf { it.y + it.height / 2.0 } > saw.y + saw.height / 2.0) shouldBe true
        bladeRows.all {
            abs(saw.z - saw.depth / 2.0 - (it.z + it.depth / 2.0) - 0.01) < 1e-9
        } shouldBe true
        val bladeHub = blocks.single { it.key == "carpenter-saw-blade-hub" }
        bladeHub.material shouldBe Material.POLISHED_ANDESITE
        (bladeHub.y - dimensions.height > 0.5) shouldBe true
        val boardFeed = blocks.single { it.key == "carpenter-board-feed" }
        val boardInFeed = blocks.single { it.key == "carpenter-board-in-feed" }
        val ripFence = blocks.single { it.key == "carpenter-rip-fence" }
        val feedSupport = blocks.single { it.key == "carpenter-feed-support-rail" }
        val motor = blocks.single { it.key == "carpenter-drive-motor" }
        (abs(boardInFeed.y - boardInFeed.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        (abs(boardInFeed.z + boardInFeed.depth / 2.0 - OriginWorkshopMachineTuning().sawPivotZ) < 1e-9) shouldBe true
        (boardInFeed.z + boardInFeed.depth / 2.0 < saw.z - saw.depth / 2.0) shouldBe true
        (abs(boardFeed.z + boardFeed.depth / 2.0 - (saw.z - saw.depth / 2.0)) < 1e-9) shouldBe true
        (abs(ripFence.y - ripFence.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        (abs(feedSupport.y - feedSupport.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        // A small gap keeps the two separately-rendered casing faces from fighting.
        (abs(motor.z - motor.depth / 2.0 - (saw.z + saw.depth / 2.0) - 0.005) < 1e-9) shouldBe true
        val bearing = blocks.single { it.key == "carpenter-saw-bearing-post" }
        val axle = blocks.single { it.key == "carpenter-saw-axle" }
        (abs(bearing.y - bearing.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        (abs(bearing.y + bearing.height / 2.0 - bladeHub.y) < 1e-9) shouldBe true
        (abs(axle.y - bladeHub.y) < 1e-9) shouldBe true
        (abs(axle.z - axle.depth / 2.0 - bladeHub.z) < 1e-9) shouldBe true
        (abs(axle.z + axle.depth / 2.0 - bearing.z) < 1e-9) shouldBe true
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

    "craft-specific samples and fixtures meet their supports and stay inside the table" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val upholstery = originWorkshopTablePieces(
            OriginWorkshopTableRole.UPHOLSTERER,
            yaw = 0,
            dimensions = dimensions,
        )
            .associateBy { it.key }
        val cushionBase = upholstery.getValue("upholsterer-cushion-base")
        val padding = upholstery.getValue("upholsterer-cushion-padding")
        val cover = upholstery.getValue("upholsterer-cushion-cover")
        (abs(cushionBase.y - cushionBase.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        (abs(padding.y - padding.height / 2.0 - (cushionBase.y + cushionBase.height / 2.0)) < 1e-9) shouldBe true
        (abs(cover.y - cover.height / 2.0 - (padding.y + padding.height / 2.0)) < 1e-9) shouldBe true

        val assembly = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val viseBed = assembly.getValue("assembler-vise-bed")
        val leftPost = assembly.getValue("assembler-vise-post-left")
        val rightPost = assembly.getValue("assembler-vise-post-right")
        val crossbar = assembly.getValue("assembler-vise-crossbar")
        val board = assembly.getValue("assembler-board-sample")
        val leftClamp = assembly.getValue("assembler-clamp-left")
        val rightClamp = assembly.getValue("assembler-clamp-right")
        (abs(viseBed.y - viseBed.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        (abs(leftPost.y - leftPost.height / 2.0 - (viseBed.y + viseBed.height / 2.0)) < 1e-9) shouldBe true
        (abs(rightPost.y - rightPost.height / 2.0 - (viseBed.y + viseBed.height / 2.0)) < 1e-9) shouldBe true
        (abs(crossbar.y - crossbar.height / 2.0 - (leftPost.y + leftPost.height / 2.0)) < 1e-9) shouldBe true
        (abs(board.y - board.height / 2.0 - (viseBed.y + viseBed.height / 2.0) - 0.005) < 1e-9) shouldBe true
        (abs(leftClamp.x + leftClamp.width / 2.0 - (board.x - board.width / 2.0) + 0.10) < 1e-9) shouldBe true
        (abs(rightClamp.x - rightClamp.width / 2.0 - (board.x + board.width / 2.0) - 0.10) < 1e-9) shouldBe true

        val finishing = originWorkshopTablePieces(OriginWorkshopTableRole.FINISHER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val frontPost = finishing.getValue("finisher-drying-post-front-left")
        val lowerRail = finishing.getValue("finisher-drying-rail-lower-front")
        val upperRail = finishing.getValue("finisher-drying-rail-upper-front")
        val dryingPanel = finishing.getValue("finisher-drying-panel-center")
        val topRail = finishing.getValue("finisher-drying-rail-top-front")
        (abs(frontPost.y - frontPost.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        frontPost.height shouldBe 1.12
        (abs(dryingPanel.y - dryingPanel.height / 2.0 - (lowerRail.y + lowerRail.height / 2.0)) < 1e-9) shouldBe true
        (abs(dryingPanel.y + dryingPanel.height / 2.0 - (upperRail.y - upperRail.height / 2.0)) < 1e-9) shouldBe true
        (topRail.y + topRail.height / 2.0 > finishing.getValue("back-tool-board").y + finishing.getValue("back-tool-board").height / 2.0) shouldBe true
        (topRail.y + topRail.height / 2.0 < frontPost.y + frontPost.height / 2.0) shouldBe true

        for (role in OriginWorkshopTableRole.entries) for (yaw in listOf(0, 90, 180, 270)) {
            val pieces = originWorkshopTablePieces(role, yaw, dimensions)
            val halfX = if (yaw == 90 || yaw == 270) dimensions.depth / 2.0 else dimensions.width / 2.0
            val halfZ = if (yaw == 90 || yaw == 270) dimensions.width / 2.0 else dimensions.depth / 2.0
            (pieces.all {
                it.x - it.width / 2.0 >= -halfX - 1e-9 && it.x + it.width / 2.0 <= halfX + 1e-9 &&
                    it.z - it.depth / 2.0 >= -halfZ - 1e-9 && it.z + it.depth / 2.0 <= halfZ + 1e-9
            }) shouldBe true
        }
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
