package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class AuroraDecorativeShapesTest : FreeSpec({
    "the needle leaves three gold stitches and closes the silver opening behind it" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.SKY_STITCH, 1.0, 112)
        fun gap(frame: Int, x: Double): Double {
            val edges = frames[frame].filter { it.paletteSlot == 0 && abs(it.offset.x - x) < 0.065 }
            return edges.maxOf { it.offset.y } - edges.minOf { it.offset.y }
        }
        (gap(18, -0.37) < gap(6, -0.37) * 0.57) shouldBe true
        (gap(18, 0.45) > gap(18, -0.37) * 1.6) shouldBe true
        val early = frames[10].filter { it.paletteSlot == 1 && it.opacity > 0.5 }
        (early.maxOf { it.offset.x } < 0.15) shouldBe true
        val sewn = frames[25].filter { it.paletteSlot == 1 && it.opacity > 0.65 }
        for (left in listOf(-0.82, -0.22, 0.38)) {
            val stitch = sewn.filter { it.offset.x in left..left + 0.37 }
            (stitch.maxOf { it.offset.y } - stitch.minOf { it.offset.y } > 0.095) shouldBe true
        }
        frames[29].any { it.paletteSlot == 1 && it.opacity > 0.1 && it.offset.x > 1.07 } shouldBe true
    }

    "the violet underside washes across the seven green folds" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.AURORA_CURTAIN, 1.0, 112)
        fun foldX(frame: Int) = frames[frame].filter { it.paletteSlot == 1 && it.opacity > 0.45 }
            .map { it.offset.x }.average()
        (foldX(9) < foldX(16) - 0.55) shouldBe true
        (foldX(22) > foldX(16) + 0.55) shouldBe true
        frames[29].none { it.paletteSlot == 1 && it.opacity > 0.2 } shouldBe true
        val ribs = frames[16].filter { it.paletteSlot == 0 && it.opacity > 0.2 }
        (ribs.maxOf { it.offset.y } - ribs.minOf { it.offset.y } > 0.8) shouldBe true
    }

    "four paws appear together at each landing and the long hop shakes snow off the bough" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.INVISIBLE_HARE, 1.0, 96)
        val landings = listOf(-0.97, -0.49, -0.01, 0.88)
        val framesAfterLanding = listOf(5, 11, 17, 24)
        val positions = mutableListOf<Double>()
        for ((group, frame) in framesAfterLanding.withIndex()) {
            val prints = frames[frame].filter { it.paletteSlot == 0 && it.opacity > 0.3 && it.offset.y < 0.025 }
            val occupied = landings.filter { x -> prints.any { abs(it.offset.x - x) < 0.22 } }
            occupied shouldBe landings.take(group + 1)
            val centre = landings[group]
            val feet = prints.filter { abs(it.offset.x - centre) < 0.22 }.map { it.offset }
            val z = (group % 2) * 0.072
            for (side in listOf(-1, 1)) {
                feet.any { it.x > centre && (it.z - z) * side > 0.075 } shouldBe true
                feet.any { it.x < centre - 0.025 && (it.z - z) * side > 0.015 } shouldBe true
            }
            positions += feet.map { it.x }.average()
        }
        (positions[3] - positions[2] > 1.65 * (positions[2] - positions[1])) shouldBe true
        frames[25].indices.any { index ->
            val a = frames[25][index]
            val b = frames[28][index]
            a.paletteSlot == 1 && a.offset.x > 0.70 && a.opacity > 0.35 && b.opacity > 0.2 &&
                b.offset.y < a.offset.y - 0.13
        } shouldBe true
    }

    "the boats launch separately stay apart through the turn and release the caught bow stern first" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.PAPER_FLOTILLA, 1.0, 112)
        val paper = frames.first().indices.filter { frames.first()[it].paletteSlot == 0 }
        val (firstBoat, secondBoat) = paper.partition { frames.first()[it].offset.z < 0.0 }
        firstBoat.all { frames[5][it].opacity > 0.85 } shouldBe true
        secondBoat.all { frames[5][it].opacity == 0.0 && frames[7][it].opacity > 0.85 } shouldBe true
        for (frame in 7..19) {
            val frontOfFollower = secondBoat.maxOf { frames[frame][it].offset.x }
            val sternOfLeader = firstBoat.minOf { frames[frame][it].offset.x }
            (sternOfLeader - frontOfFollower > 0.23) shouldBe true
        }
        firstBoat.all { frames[20][it].opacity == 0.0 } shouldBe true
        secondBoat.all { frames[20][it].opacity > 0.85 } shouldBe true
        for (frame in 14..23) {
            val pin = paper.map { frames[frame][it].offset }
                .first { abs(it.x - 0.20) < 1e-10 && abs(it.z - 0.19) < 1e-10 }
            frames[frame].any { it.paletteSlot == 1 && squaredDistance(it.offset, pin) < 1e-15 } shouldBe true
        }
        val stern = paper.filter { frames[13][it].offset.z > 0.15 }.minBy { frames[13][it].offset.x }
        (frames[13][stern].offset.x < -0.28) shouldBe true
        (frames[18][stern].offset.z < -0.25) shouldBe true
        (frames[23][stern].offset.x > 0.64) shouldBe true
        (frames[29][stern].offset.x > frames[23][stern].offset.x + 0.29) shouldBe true
    }

    "the rolling tangle clears the sand crest and shivers around the caught twig" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.TUMBLEWEED, 1.0, 96)
        val twigs = frames.first().indices.filter {
            frames[6][it].paletteSlot == 0 && frames[19][it].offset.x - frames[6][it].offset.x > 0.45
        }
        fun lowest(frame: Int) = twigs.minOf { frames[frame][it].offset.y }
        (lowest(13) > lowest(6) + 0.18) shouldBe true
        (lowest(20) < lowest(13) - 0.18) shouldBe true
        val snag = AuroraOffset(0.918, 0.32, 0.0)
        val tip = twigs.minBy { squaredDistance(frames[25][it].offset, snag) }
        (23..30).all { squaredDistance(frames[it][tip].offset, snag) < 1e-15 } shouldBe true
        twigs.any { squaredDistance(frames[24][it].offset, frames[25][it].offset) > 0.0001 } shouldBe true
        for (shape in listOf(AuroraShape.INVISIBLE_HARE, AuroraShape.PAPER_FLOTILLA, AuroraShape.TUMBLEWEED)) {
            AuroraCelestialShapes.frames(shape, 1.0, 96).flatten().all { it.offset.y >= 0.0 } shouldBe true
        }
    }
})

private fun squaredDistance(a: AuroraOffset, b: AuroraOffset): Double {
    val x = a.x - b.x
    val y = a.y - b.y
    val z = a.z - b.z
    return x * x + y * y + z * z
}
