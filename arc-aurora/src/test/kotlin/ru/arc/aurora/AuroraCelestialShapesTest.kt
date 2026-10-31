package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class AuroraCelestialShapesTest : FreeSpec({
    "six finite celestial scenes retain their bounded identities and fade at both ends" {
        for (shape in AuroraShape.entries.filter { it.isCelestial }) {
            for (points in listOf(1, 80, 96, 160, 192)) {
                val frames = AuroraCelestialShapes.frames(shape, 1.0, points)
                frames.size shouldBe 33
                frames.all { it.size == points } shouldBe true
                frames.flatten().all { sample ->
                    sample.opacity in 0.0..1.0 && sample.paletteSlot in 0..1 &&
                        listOf(sample.offset.x, sample.offset.y, sample.offset.z).all(Double::isFinite) &&
                        celestialDistance(sample.offset, AuroraOffset(0.0, 0.0, 0.0)) < 1.75
                } shouldBe true
                frames.first().all { it.opacity == 0.0 } shouldBe true
                frames.last().all { it.opacity == 0.0 } shouldBe true
                frames.all { frame -> frame.map { it.paletteSlot } == frames.first().map { it.paletteSlot } } shouldBe true
                frames.zipWithNext().all { (a, b) -> a.zip(b).all { (p, q) ->
                    celestialDistance(p.offset, q.offset) < 0.42
                } } shouldBe true
                if (points == 160) exportCelestialPreview(shape, frames)
            }
        }
    }

    "jellyfish keeps bounded identities while its bell breathes and tentacles move" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, 1.0, 96)
        frames.size shouldBe 33
        frames.all { it.size == 96 } shouldBe true
        frames.flatten().all { sample ->
            sample.opacity in 0.0..1.0 && sample.paletteSlot in 0..1 &&
                listOf(sample.offset.x, sample.offset.y, sample.offset.z).all(Double::isFinite)
        } shouldBe true
        frames.first().all { it.opacity == 0.0 } shouldBe true
        frames.last().all { it.opacity == 0.0 } shouldBe true
        frames.all { frame -> frame.map { it.paletteSlot } == frames.first().map { it.paletteSlot } } shouldBe true
        fun bellWidth(frame: Int): Double = frames[frame].filter { it.paletteSlot == 0 }
            .let { points -> points.maxOf { it.offset.x } - points.minOf { it.offset.x } }
        (bellWidth(8) - bellWidth(16) > 0.3) shouldBe true
        frames.zipWithNext().all { (a, b) -> a.zip(b).all { (p, q) -> celestialDistance(p.offset, q.offset) < 0.16 } } shouldBe true
        (frames[16].maxOf { it.offset.y } - frames[16].minOf { it.offset.y } > 1.4) shouldBe true
    }

    "manta flaps its wings relative to its body instead of translating a frozen drawing" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.MANTA_RAY, 1.0, 96)
        val differences = frames[8].zip(frames[16]).map { (a, b) -> a.offset.y - b.offset.y }
        (differences.max() - differences.min() > 0.35) shouldBe true
        (frames[8].maxOf { it.offset.x } - frames[8].minOf { it.offset.x } > 1.9) shouldBe true
        (frames[8].minOf { it.offset.y } < -0.8) shouldBe true
    }

    "two light ribbons tighten in depth and unwind without inheriting the old knot path" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.LIGHT_KNOT, 1.0, 96)
        fun gap(frame: Int) = celestialDistance(frames[frame][46].offset, frames[frame][47].offset)
        (gap(4) > gap(16) + 0.2) shouldBe true
        (gap(28) > gap(16) + 0.25) shouldBe true
        (frames[16].maxOf { abs(it.offset.z) } > 0.15) shouldBe true
        frames.all { it.count { point -> point.paletteSlot == 0 } == 48 } shouldBe true
    }

    "reverse lightning retires every branch and lets exactly one drop leave the junction" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.REVERSE_LIGHTNING, 1.0, 96)
        (frames[8].count { it.paletteSlot == 0 && it.opacity > 0.0 } > 8) shouldBe true
        for (frame in 24..30) {
            frames[frame].count { it.opacity > 0.0 } shouldBe 1
            frames[frame].last().paletteSlot shouldBe 1
        }
        (frames[29].last().offset.y > frames[21].last().offset.y + 0.5) shouldBe true
    }

    "coral grows from trunk to tips and folds its connected branches on departure" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.SKY_CORAL, 1.0, 96)
        (frames[8].count { it.opacity > 0.0 } < frames[18].count { it.opacity > 0.0 }) shouldBe true
        frames[8].none { it.paletteSlot == 1 && it.opacity > 0.0 } shouldBe true
        frames[18].any { it.paletteSlot == 1 && it.opacity > 0.0 } shouldBe true
        fun width(frame: Int) = frames[frame].maxOf { it.offset.x } - frames[frame].minOf { it.offset.x }
        (width(28) < width(18) * 0.6) shouldBe true
    }

    "coral crown has a branched silhouette from every representative world azimuth" {
        val crown = AuroraCelestialShapes.frames(AuroraShape.SKY_CORAL, 1.0, 160)[19]
            .filter { it.paletteSlot == 1 && it.opacity > 0.9 }.map { it.offset }
        for (azimuth in 0 until 360 step 45) {
            val yaw = azimuth * PI / 180.0
            for (elevation in listOf(15, 35, 55)) {
                val pitch = elevation * PI / 180.0
                val horizontal = crown.map { it.x * cos(yaw) + it.z * sin(yaw) }
                val vertical = crown.map { it.y * cos(pitch) - (-it.x * sin(yaw) + it.z * cos(yaw)) * sin(pitch) }
                (horizontal.max() - horizontal.min() > 0.8) shouldBe true
                (vertical.max() - vertical.min() > 0.3) shouldBe true
            }
        }
    }

    "moon fan visibly turns edge-on then opens on its other side" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.MOON_FAN, 1.0, 96)
        fun width(frame: Int) = frames[frame].maxOf { it.offset.x } - frames[frame].minOf { it.offset.x }
        (width(8) > 1.5) shouldBe true
        (width(16) < 1e-12) shouldBe true
        (width(24) > 1.5) shouldBe true
        (frames[16].maxOf { it.offset.z } - frames[16].minOf { it.offset.z } > 1.5) shouldBe true
    }

    "sample interpolation and immutable posing preserve the authored coordinates" {
        val frames = AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, 1.0, 80)
        for (frame in frames.indices) {
            for (index in frames.first().indices) {
                AuroraCelestialShapes.pointAt(frames, frame.toDouble() / frames.lastIndex, index) shouldBe frames[frame][index]
            }
        }
        val mirror = AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, 4.0, 80, reverse = true)
        frames.flatten().zip(mirror.flatten()).all { (a, b) ->
            abs(a.offset.x * -4.0 - b.offset.x) < 1e-12 && abs(a.offset.y * 4.0 - b.offset.y) < 1e-12 &&
                abs(a.offset.z * 4.0 - b.offset.z) < 1e-12 && a.opacity == b.opacity && a.paletteSlot == b.paletteSlot
        } shouldBe true
    }

    "invalid celestial sampling requests fail before allocating frames" {
        listOf(0, 193).forEach { points ->
            shouldThrow<IllegalArgumentException> { AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, 1.0, points) }
        }
        listOf(1, 34).forEach { count ->
            shouldThrow<IllegalArgumentException> { AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, 1.0, 80, count) }
        }
        shouldThrow<IllegalArgumentException> { AuroraCelestialShapes.frames(AuroraShape.JELLYFISH, Double.NaN, 80) }
        shouldThrow<IllegalArgumentException> { AuroraCelestialShapes.frames(AuroraShape.WAVE, 1.0, 80) }
    }
})

private fun celestialDistance(a: AuroraOffset, b: AuroraOffset) =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))

/** Source-derived preview only. Rerun this exact test task when changing this undeclared export input. */
private fun exportCelestialPreview(shape: AuroraShape, frames: List<List<AuroraCelestialPoint>>) {
    val output = System.getenv("AURORA_CELESTIAL_PREVIEW_DIR") ?: return
    val directory = File(output).also { it.mkdirs() }
    File(directory, "${shape.name.lowercase()}.json").writeText(frames.joinToString(prefix = "[", postfix = "]") { frame ->
        frame.joinToString(prefix = "[", postfix = "]") { point ->
            "[${point.offset.x},${point.offset.y},${point.offset.z},${point.opacity},${point.paletteSlot}]"
        }
    })
}
