package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt

class AuroraSkyShapesTest : FreeSpec({
    "sky frames retain every sample and stay finite within their rigid-rotation bound" {
        for (shape in AuroraShape.entries) {
            for (size in listOf(0.1, 8.0, 32.0)) {
                for (points in listOf(1, 48, 192)) {
                    for (age in listOf(-1L, 0L, 199L, Long.MAX_VALUE)) {
                        val samples = AuroraSkyShapes.offsets(shape, size, points, age)
                        samples.size shouldBe points
                        samples.all { listOf(it.x, it.y, it.z).all(Double::isFinite) } shouldBe true
                        samples.all { magnitude(it) <= size * sqrt(3.0) + 1e-10 } shouldBe true
                    }
                }
            }
        }
    }

    "wide sky wave stays a legible ribbon and changes smoothly over its configured period" {
        fun frame(age: Long) = AuroraSkyShapes.offsets(AuroraShape.WAVE, 8.0, 48, age, 800)
        val first = frame(0)
        first.first().x shouldBe -8.0
        first.last().x shouldBe 8.0
        (first.maxOf { it.y } - first.minOf { it.y } > 4.0) shouldBe true
        first.zipWithNext().all { (a, b) -> distance(a, b) < 0.5 } shouldBe true
        first.zip(frame(4)).all { (a, b) -> distance(a, b) < 0.06 } shouldBe true
        (frame(200) != first) shouldBe true
        frame(800) shouldBe first
        frame(-1) shouldBe frame(799)
    }

    "ring tilt preserves a ten-block contour without becoming edge-on" {
        for (age in 0L..600L step 25) {
            val ring = AuroraSkyShapes.offsets(AuroraShape.RING, 5.0, 48, age, 600)
            ring.all { abs(magnitude(it) - 5.0) < 1e-10 } shouldBe true
            (ring.maxOf { it.x } - ring.minOf { it.x } > 9.5) shouldBe true
            (ring.maxOf { it.y } - ring.minOf { it.y } > 8.5) shouldBe true
            (ring + ring.first()).zipWithNext().all { (a, b) -> distance(a, b) < 0.67 } shouldBe true
        }
    }

    "cube head follows one continuous walk covering all twelve edges" {
        val first = AuroraSkyShapes.offsets(AuroraShape.CUBE, 3.0, 46, 0)
        val later = AuroraSkyShapes.offsets(AuroraShape.CUBE, 3.0, 46, 100)
        (first != later) shouldBe true
        for (frame in listOf(first, later)) {
            frame.zipWithNext().all { (a, b) -> abs(distance(a, b) - 2.0) < 1e-10 } shouldBe true
            fun key(point: AuroraOffset) = listOf(point.x, point.y, point.z).map { kotlin.math.round(it * 1e6).toLong() }
            val edges = (0 until 15).map { edge -> setOf(key(frame[edge * 3]), key(frame[(edge + 1) * 3])) }
            edges.toSet().size shouldBe 12
        }
        first.zip(later).all { (a, b) -> abs(magnitude(a) - magnitude(b)) < 1e-10 } shouldBe true
    }

    "prepared wave grows through adjacent points and never repeats a static full pose" {
        val wave = AuroraSkyShapes.stroke(AuroraShape.WAVE, 10.0, 56, 1, 800, 180, 0.22, false)
        wave.size shouldBe 56
        wave.distinct().size shouldBe 56
        wave.zipWithNext().all { (a, b) -> distance(a, b) in 0.2..0.65 } shouldBe true
        val reversed = AuroraSkyShapes.stroke(AuroraShape.WAVE, 10.0, 56, 1, 800, 180, 0.22, true)
        (reversed.first().x > reversed.last().x) shouldBe true
        (wave.first().x < wave.last().x) shouldBe true
    }

    "sky motion is deterministic and invalid requests fail before sampling" {
        AuroraShape.entries.forEach { shape ->
            AuroraSkyShapes.offsets(shape, 8.0, 48, 80, 600) shouldBe AuroraSkyShapes.offsets(shape, 8.0, 48, 680, 600)
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, 0.0, 32.01).forEach {
            shouldThrow<IllegalArgumentException> { AuroraSkyShapes.offsets(AuroraShape.RING, it, 48, 0) }
        }
        listOf(0, 193).forEach {
            shouldThrow<IllegalArgumentException> { AuroraSkyShapes.offsets(AuroraShape.RING, 5.0, it, 0) }
        }
        listOf(39, 2401).forEach {
            shouldThrow<IllegalArgumentException> { AuroraSkyShapes.offsets(AuroraShape.RING, 5.0, 48, 0, it) }
        }
    }
})

private fun magnitude(offset: AuroraOffset) = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)
private fun distance(a: AuroraOffset, b: AuroraOffset) = magnitude(AuroraOffset(a.x - b.x, a.y - b.y, a.z - b.z))
