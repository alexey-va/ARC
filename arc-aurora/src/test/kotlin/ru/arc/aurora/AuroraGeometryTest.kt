package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt

class AuroraGeometryTest : FreeSpec({
    "all shape samples remain finite and within the configured size and count" {
        for (shape in AuroraShape.entries) {
            for (size in listOf(0.1, 1.0, 8.0)) {
                for (points in listOf(1, 7, 24)) {
                    for (age in listOf(-1L, 0L, 10L, 39L, Long.MAX_VALUE)) {
                        val samples = AuroraGeometry.offsets(shape, size, points, age)
                        samples.size shouldBe points
                        samples.all { offset -> listOf(offset.x, offset.y, offset.z).all {
                            it.isFinite() && abs(it) <= size + 1e-10
                        } } shouldBe true
                    }
                }
            }
        }
    }

    "line endpoints, disk interior, and ring and sphere surfaces preserve their meanings" {
        AuroraGeometry.offsets(AuroraShape.POINT, 3.0, 7, 0).distinct() shouldBe listOf(AuroraOffset(0.0, 0.0, 0.0))
        AuroraGeometry.offsets(AuroraShape.LINE, 3.0, 3, 0) shouldBe listOf(
            AuroraOffset(-3.0, 0.0, 0.0), AuroraOffset(0.0, 0.0, 0.0), AuroraOffset(3.0, 0.0, 0.0),
        )
        AuroraGeometry.offsets(AuroraShape.LINE, 3.0, 1, 0).single() shouldBe AuroraOffset(0.0, 0.0, 0.0)
        AuroraGeometry.offsets(AuroraShape.RING, 3.0, 24, 0).all {
            abs(length(it) - 3.0) < 1e-10 && it.y == 0.0
        } shouldBe true
        val disk = AuroraGeometry.offsets(AuroraShape.CIRCLE, 3.0, 24, 0)
        disk.all { length(it) < 3.0 && it.y == 0.0 } shouldBe true
        (disk.minOf(::length) < 1.0) shouldBe true
        AuroraGeometry.offsets(AuroraShape.SPHERE, 3.0, 24, 0).all { abs(length(it) - 3.0) < 1e-10 } shouldBe true
        val cube = AuroraGeometry.offsets(AuroraShape.CUBE, 3.0, 24, 0)
        cube.distinct().size shouldBe 24
        cube.all { listOf(it.x, it.y, it.z).count { coordinate -> abs(abs(coordinate) - 3.0) < 1e-10 } == 2 } shouldBe true
    }

    "wave moves over age with bounded height and a forty tick period" {
        fun frame(age: Long) = AuroraGeometry.offsets(AuroraShape.WAVE, 4.0, 9, age)
        val first = frame(0)
        val quarter = frame(10)
        first.map { it.x } shouldBe quarter.map { it.x }
        (first != quarter) shouldBe true
        (first + quarter).all { abs(it.y) <= 1.0 && it.z == 0.0 } shouldBe true
        frame(40) shouldBe first
        frame(-1) shouldBe frame(39)
        val single = AuroraGeometry.offsets(AuroraShape.WAVE, 4.0, 1, 10).single()
        single.x shouldBe 0.0
        (abs(single.y - 1.0) < 1e-10) shouldBe true
        val helix = AuroraGeometry.offsets(AuroraShape.HELIX, 4.0, 9, 0)
        (helix != AuroraGeometry.offsets(AuroraShape.HELIX, 4.0, 9, 10)) shouldBe true
        AuroraGeometry.offsets(AuroraShape.HELIX, 4.0, 9, 40) shouldBe helix
        AuroraShape.entries.filter { it !in setOf(AuroraShape.WAVE, AuroraShape.HELIX) }.forEach {
            AuroraGeometry.offsets(it, 4.0, 9, 0) shouldBe AuroraGeometry.offsets(it, 4.0, 9, 10)
        }
    }

    "invalid geometry requests fail before allocating samples" {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, 8.01).forEach { size ->
            shouldThrow<IllegalArgumentException> { AuroraGeometry.offsets(AuroraShape.RING, size, 8, 0) }
        }
        listOf(0, 25).forEach { points ->
            shouldThrow<IllegalArgumentException> { AuroraGeometry.offsets(AuroraShape.RING, 1.0, points, 0) }
        }
    }
})

private fun length(offset: AuroraOffset) = sqrt(offset.x * offset.x + offset.y * offset.y + offset.z * offset.z)
