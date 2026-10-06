package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Material

class OriginWorkshopWorkpieceTest : FreeSpec({
    "the sawn board stays centered in one cuboid before drilling" {
        originWorkshopBoardPieces(0).single().size shouldBe OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)
        originWorkshopBoardPieces(0).single().center shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
        originWorkshopBoardPieces(0).single().material shouldBe Material.OAK_PLANKS
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
        for (coating in listOf(false, true)) for (piece in originWorkshopPlacementMarker(coating)) {
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
