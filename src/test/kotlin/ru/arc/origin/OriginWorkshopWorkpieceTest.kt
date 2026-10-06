package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeLessThan
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f

class OriginWorkshopWorkpieceTest : FreeSpec({
    "workpiece renderer accepts the two config values and coarse board states match the cube preview" {
        OriginWorkshopWorkpieceRenderer.parse(" model ") shouldBe OriginWorkshopWorkpieceRenderer.MODEL
        OriginWorkshopWorkpieceRenderer.parse("CUBES") shouldBe OriginWorkshopWorkpieceRenderer.CUBES
        shouldThrow<IllegalArgumentException> { OriginWorkshopWorkpieceRenderer.parse("display") }

        val expected = linkedMapOf(
            OriginWorkshopBoardModel.RAW to (39 to 1.04),
            OriginWorkshopBoardModel.CUT_ONCE to (33 to 0.88),
            OriginWorkshopBoardModel.CUT to (27 to 0.72),
            OriginWorkshopBoardModel.DRILLED_1 to (26 to 0.72),
            OriginWorkshopBoardModel.DRILLED_2 to (25 to 0.72),
            OriginWorkshopBoardModel.DRILLED_3 to (24 to 0.72),
            OriginWorkshopBoardModel.OFFCUT to (6 to 0.16),
        )
        expected.forEach { (model, dimensions) ->
            val pieces = originWorkshopCoarseBoardPieces(model)
            pieces.size shouldBe dimensions.first
            pieces.all { it.material == Material.OAK_PLANKS } shouldBe true
            pieces.all { it.size == OriginWorkshopGamePartSize(0.08f, 0.08f, 0.08f) } shouldBe true
            pieces.map { it.center }.toSet().size shouldBe pieces.size
            (pieces.maxOf { it.center.x + it.size.x / 2.0 } - pieces.minOf { it.center.x - it.size.x / 2.0 })
                .near(dimensions.second) shouldBe true
            (pieces.maxOf { it.center.z + it.size.z / 2.0 } - pieces.minOf { it.center.z - it.size.z / 2.0 })
                .near(0.24) shouldBe true
            (pieces.maxOf { it.center.y + it.size.y / 2.0 } - pieces.minOf { it.center.y - it.size.y / 2.0 })
                .near(0.08) shouldBe true
        }

        val drilled = listOf(
            OriginWorkshopBoardModel.DRILLED_1 to 1,
            OriginWorkshopBoardModel.DRILLED_2 to 2,
            OriginWorkshopBoardModel.DRILLED_3 to 3,
        )
        drilled.forEach { (model, holes) ->
            val pieces = originWorkshopCoarseBoardPieces(model)
            listOf(-0.24, 0.0, 0.24).take(holes).forEach { holeX ->
                pieces.any { it.center.x.near(holeX) && it.center.y.near(0.0) && it.center.z.near(0.0) } shouldBe false
                for (sideZ in listOf(-0.08, 0.08)) {
                    pieces.any { it.center.x.near(holeX) && it.center.z.near(sideZ) } shouldBe true
                }
            }
        }
    }

    "the sawn board stays centered in one cuboid before drilling" {
        originWorkshopBoardPieces(0).single().size shouldBe OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)
        originWorkshopBoardPieces(0).single().center shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
        originWorkshopBoardPieces(0).single().material shouldBe Material.OAK_PLANKS
    }

    "fixed ItemDisplay compensation cancels the native half turn for every workpiece pose" {
        for (angle in listOf(-Math.PI, -Math.PI / 2, 0.0, Math.PI / 3, Math.PI)) {
            val desired = Quaternionf().rotationY(angle.toFloat())
            val entity = originWorkshopBoardItemDisplayRotation(desired)
            val rendered = Quaternionf(entity).rotateY(Math.PI.toFloat())
            for (point in listOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f))) {
                val expected = Vector3f(point).rotate(desired)
                val actual = Vector3f(point).rotate(rendered)
                (actual.distance(expected) < 1.0e-5f) shouldBe true
            }
        }
    }

    "pure board cuboid hitboxes stay centered and match rotated piece dimensions" {
        val center = OriginWorkshopPoint(5.25, 72.0, -8.5)
        val piece = OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(0.18, 0.03, -0.02),
            OriginWorkshopGamePartSize(0.24f, 0.08f, 0.075f),
            Material.OAK_PLANKS,
        )
        for (angle in listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2)) {
            val rotation = Quaternionf().rotationY(angle.toFloat())
            val matrix = originWorkshopWorkpieceHitboxMatrix(center, rotation, piece)
            val corners = buildList {
                for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) for (z in listOf(0f, 1f)) {
                    add(matrix.transformPosition(Vector3f(x, y, z)))
                }
            }
            val localCenter = rotation.transform(Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat()))
            val half = rotation.transform(Vector3f(piece.size.x / 2f, piece.size.y / 2f, piece.size.z / 2f))
            val expected = Vector3f(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()).add(localCenter)
            val actualCenter = Vector3f(
                (corners.minOf { it.x } + corners.maxOf { it.x }) / 2f,
                (corners.minOf { it.y } + corners.maxOf { it.y }) / 2f,
                (corners.minOf { it.z } + corners.maxOf { it.z }) / 2f,
            )
            actualCenter.distance(expected) shouldBeLessThan 1.0e-5f
            (corners.maxOf { it.x } - corners.minOf { it.x }).near(2f * abs(half.x)) shouldBe true
            (corners.maxOf { it.y } - corners.minOf { it.y }).near(2f * abs(half.y)) shouldBe true
            (corners.maxOf { it.z } - corners.minOf { it.z }).near(2f * abs(half.z)) shouldBe true
        }
    }

    "progressive drilling makes one through-hole at a time without overlapping wood pieces" {
        val holePositions = listOf(-0.23, 0.0, 0.23)
        for (holes in 0..3) {
            val pieces = originWorkshopBoardPieces(holes)
            val removedVolume = holes * 0.075f.toDouble() * 0.08f.toDouble() * 0.075f.toDouble()
            val boardVolume = 0.72f.toDouble() * 0.08f.toDouble() * 0.22f.toDouble()

            pieces.sumOf(::volume).near(boardVolume - removedVolume) shouldBe true
            pieces.all { it.material == Material.OAK_PLANKS } shouldBe true
            pieces.all { it.center.x - it.size.x / 2.0 >= -0.36 - EPSILON && it.center.x + it.size.x / 2.0 <= 0.36 + EPSILON } shouldBe true
            pieces.all { it.center.z - it.size.z / 2.0 >= -0.11 - EPSILON && it.center.z + it.size.z / 2.0 <= 0.11 + EPSILON } shouldBe true
            pieces.indices.forEach { first ->
                for (second in first + 1 until pieces.size) cuboidsOverlap(pieces[first], pieces[second]) shouldBe false
            }

            holePositions.take(holes).forEach { holeX -> contains(pieces, holeX, 0.0, 0.0) shouldBe false }
            holePositions.drop(holes).forEach { uncutX -> contains(pieces, uncutX, 0.0, 0.0) shouldBe true }
            holePositions.take(holes).forEach { holeX -> contains(pieces, holeX, 0.0, 0.09) shouldBe true }
        }
        originWorkshopBoardPieces(1).size shouldBe 4
        originWorkshopBoardPieces(2).size shouldBe 7
        originWorkshopBoardPieces(3).size shouldBe 10
        shouldThrow<IllegalArgumentException> { originWorkshopBoardPieces(-1) }
        shouldThrow<IllegalArgumentException> { originWorkshopBoardPieces(4) }
    }

    "all visible corners of a placement marker stay inside the click radius" {
        for (action in OriginWorkshopGameAction.entries) for (piece in originWorkshopPlacementMarker(action)) {
            val x = abs(piece.center.x) + piece.size.x / 2
            val y = abs(piece.center.y) + piece.size.y / 2
            val z = abs(piece.center.z) + piece.size.z / 2
            (x * x + y * y + z * z <= 0.36 * 0.36) shouldBe true
        }
    }

    "shoulder board follows horizontal forward at cardinal yaws and lowers while sneaking" {
        for (yawDegrees in listOf(0.0, 90.0, 180.0, 270.0)) {
            val yaw = Math.toRadians(yawDegrees)
            val forwardX = -sin(yaw)
            val forwardZ = cos(yaw)
            val rightX = -cos(yaw)
            val rightZ = -sin(yaw)
            val standing = originWorkshopShoulderPose(yawDegrees, sneaking = false)
            val sneaking = originWorkshopShoulderPose(yawDegrees, sneaking = true)

            cos(standing.rotationRadians).near(forwardX) shouldBe true
            (-sin(standing.rotationRadians)).near(forwardZ) shouldBe true
            standing.center.x.near(forwardX * 0.12 + rightX * 0.36) shouldBe true
            standing.center.z.near(forwardZ * 0.12 + rightZ * 0.36) shouldBe true
            standing.center.y shouldBe 1.43
            sneaking.center.y shouldBe 1.18
        }
        shouldThrow<IllegalArgumentException> { originWorkshopShoulderPose(Double.NaN, sneaking = false) }
    }
})

private const val EPSILON = 1.0e-7

private fun volume(piece: OriginWorkshopWorkpiecePiece): Double =
    piece.size.x.toDouble() * piece.size.y.toDouble() * piece.size.z.toDouble()

private fun Double.near(expected: Double): Boolean = abs(this - expected) <= 1.0e-8

private fun Float.near(expected: Float): Boolean = abs(this - expected) <= 1.0e-5f

private fun contains(pieces: List<OriginWorkshopWorkpiecePiece>, x: Double, y: Double, z: Double): Boolean =
    pieces.any { piece ->
        abs(x - piece.center.x) < piece.size.x / 2.0 - EPSILON &&
            abs(y - piece.center.y) < piece.size.y / 2.0 - EPSILON &&
            abs(z - piece.center.z) < piece.size.z / 2.0 - EPSILON
    }

private fun cuboidsOverlap(first: OriginWorkshopWorkpiecePiece, second: OriginWorkshopWorkpiecePiece): Boolean =
    overlap(first.center.x, first.size.x, second.center.x, second.size.x) &&
        overlap(first.center.y, first.size.y, second.center.y, second.size.y) &&
        overlap(first.center.z, first.size.z, second.center.z, second.size.z)

private fun overlap(firstCenter: Double, firstSize: Float, secondCenter: Double, secondSize: Float): Boolean =
    minOf(firstCenter + firstSize / 2.0, secondCenter + secondSize / 2.0) -
        maxOf(firstCenter - firstSize / 2.0, secondCenter - secondSize / 2.0) > EPSILON
