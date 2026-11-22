package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.hypot

class AuroraSkyVignettesTest : FreeSpec({
    "sand drains through the throat and the loaded chamber turns over" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.SKY_HOURGLASS, 1.0, 80)
        (frames[5].visiblePart(1).maxOf { it.y } > 0.52) shouldBe true
        (frames[18].visiblePart(1).maxOf { it.y } < -0.30) shouldBe true
        (frames[29].visiblePart(1).minOf { it.y } > 0.43) shouldBe true
        val cap = frames[5].indexOfFirst { it.paletteSlot == 0 && it.offset.x < -0.66 && it.offset.y > 0.79 }
        (frames[30][cap].offset.x > 0.65 && frames[30][cap].offset.y < -0.79) shouldBe true
    }

    "a gust bends the stalk and strips successive seeds while the lee side stays attached" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.DANDELION_GUST, 1.0, 80)
        val stemTip = frames.first().indices.filter { frames.first()[it].paletteSlot == 0 }
            .maxBy { frames.first()[it].offset.y }
        fun displaced(frame: Int) = frames[frame].indices.count { i ->
            val seed = frames[frame][i]
            val start = frames.first()[i]
            seed.paletteSlot == 1 && seed.offset.x - frames[frame][stemTip].offset.x >
                start.offset.x - frames.first()[stemTip].offset.x + 0.32
        }
        (frames[12][stemTip].offset.x > frames[3][stemTip].offset.x + 0.19) shouldBe true
        displaced(8) shouldBe 0
        (0 < displaced(15) && displaced(15) < displaced(20) && displaced(20) < displaced(27)) shouldBe true
        val late = frames[27].visiblePart(1)
        (late.minOf { it.x } < -0.73 && late.maxOf { it.x } > 1.10) shouldBe true
    }

    "the diving bill reaches its fish and carries it through the climb" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.KINGFISHER_DIVE, 1.0, 72)
        val tip = frames[5].indices.filter { frames[5][it].paletteSlot == 1 }.maxBy { frames[5][it].offset.x }
        fun gap(frame: Int): Double {
            val beak = frames[frame][tip].offset
            return frames[frame].visiblePart(2).minOf { hypot(it.x - beak.x, it.y - beak.y) }
        }
        (gap(5) > 1.15) shouldBe true
        (frames[18][tip].offset.y < frames[5][tip].offset.y - 1.28) shouldBe true
        (18..28).all { gap(it) < 0.12 } shouldBe true
        (frames[26].visiblePart(2).minOf { it.y } > frames[18].visiblePart(2).maxOf { it.y } + 1.0) shouldBe true
    }

    "the beam reaches the prism before the coloured rays leave its far face" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.STELLAR_PRISM, 1.0, 72)
        frames[9].visiblePart(1).isEmpty() shouldBe true
        (frames[9].visiblePart(0).minOf { it.x } < -1.15) shouldBe true
        (frames[15].visiblePart(1).maxOf { it.x } < 0.66) shouldBe true
        val blue = frames[22].visiblePart(1)
        val amber = frames[22].visiblePart(2)
        (blue.maxOf { it.y } > 0.60 && amber.minOf { it.y } < -0.55) shouldBe true
        blue.first() shouldBe amber.first()
        (frames[29].visiblePart(1).minOf { it.x } > 0.75) shouldBe true
    }

    "the crater vents before its ejecta crest and fall and the lava reaches the foot later" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.EMBER_VOLCANO, 1.0, 80)
        frames[4].visiblePart(2).isEmpty() shouldBe true
        (frames[15].visiblePart(2).maxOf { it.y } > 1.0) shouldBe true
        (frames[15].visiblePart(1).minOf { it.y } > -0.25) shouldBe true
        frames[26].visiblePart(2).all { it.x > 0.68 && it.y < 0.26 } shouldBe true
        frames[26].visiblePart(2).isNotEmpty() shouldBe true
        (frames[26].visiblePart(1).minOf { it.y } < -0.63) shouldBe true
    }

    "the running fracture opens the shell and a star clears the lower cup" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.HATCHING_STAR, 1.0, 72)
        (frames[8].visiblePart(2).maxOf { it.x } < -0.2) shouldBe true
        (frames[14].visiblePart(2).maxOf { it.x } > 0.5) shouldBe true
        frames[14].visiblePart(1).isEmpty() shouldBe true
        (frames[28].visiblePart(0).minOf { it.x } < -1.0) shouldBe true
        val star = frames[28].visiblePart(1)
        (star.minOf { it.y } > 0.3 && star.maxOf { it.y } > 1.0) shouldBe true
        (star.maxOf { it.x } - star.minOf { it.x } > 0.56) shouldBe true
    }

    "the inverted pentagram draws its five crossing chords in order and holds the completed stroke" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.INFERNAL_PENTAGRAM, 1.0, 128)
        val stroke = frames[20].indices.filter { frames[20][it].paletteSlot == 0 }
        (16..27).all { f -> stroke.all { frames[f][it].opacity > 0.75 } } shouldBe true
        val visits = listOf(0, 2, 4, 1, 3, 0)
        val arrivals = visits.zipWithNext { a, b ->
            val from = pentagramVertices[a]
            val to = pentagramVertices[b]
            for (step in 0..5) {
                val target = AuroraCelestialShapes.mix(from, to, step / 5.0)
                stroke.any { frames[20][it].offset.distanceXY(target) < 0.07 } shouldBe true
            }
            val middle = AuroraCelestialShapes.mix(from, to, 0.5)
            val sample = stroke.minBy { frames[20][it].offset.distanceXY(middle) }
            frames.indexOfFirst { it[sample].opacity > 0.55 }
        }
        (arrivals.first() > 0 && arrivals.last() < 16 && arrivals.zipWithNext().all { (a, b) -> a < b }) shouldBe true
        val embers = frames[20].visiblePart(1)
        embers.size shouldBe 28
        embers.all { hypot(it.x, it.y) in 1.14..1.16 } shouldBe true
    }

    "five separate spearhead seals bridge the star tips to the ember ring" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.INFERNAL_PENTAGRAM, 1.0, 128)
        val order = mutableListOf<Int>()
        for (frame in frames) {
            val seals = frame.filter { it.paletteSlot == 2 && it.opacity > 0.55 }.map { it.offset }
            if (seals.isEmpty()) continue
            val vertices = seals.map { point -> pentagramVertices.indices.minBy { point.distanceXY(pentagramVertices[it]) } }
            vertices.distinct().size shouldBe 1
            if (seals.size == 8) {
                val vertex = vertices.first()
                if (order.lastOrNull() != vertex) order += vertex
                (seals.minOf { hypot(it.x, it.y) } < 1.01 && seals.maxOf { hypot(it.x, it.y) } > 1.26) shouldBe true
            }
        }
        order shouldBe pentagramVertices.indices.toList()
    }
})

private val pentagramVertices = listOf(AuroraOffset(0.0, -1.0, 0.0), AuroraOffset(0.9511, -0.3090, 0.0),
    AuroraOffset(0.5878, 0.8090, 0.0), AuroraOffset(-0.5878, 0.8090, 0.0), AuroraOffset(-0.9511, -0.3090, 0.0))

private fun AuroraOffset.distanceXY(other: AuroraOffset) = hypot(x - other.x, y - other.y)

private fun List<AuroraCelestialPoint>.visiblePart(slot: Int) =
    filter { it.paletteSlot == slot && it.opacity > 0.3 }.map { it.offset }
