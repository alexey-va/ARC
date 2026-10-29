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

    "custom crystal has a closed continuous three dimensional contour and two advancing heads" {
        val frame = AuroraSkyShapes.offsets(AuroraShape.CRYSTAL, 5.0, 160, 0)
        (distance(frame.first(), frame.last()) < 1e-12) shouldBe true
        (frame.maxOf { it.y } - frame.minOf { it.y } > 9.5) shouldBe true
        (frame.maxOf { it.x } - frame.minOf { it.x } > 5.0) shouldBe true
        (frame.maxOf { it.z } - frame.minOf { it.z } > 5.0) shouldBe true
        frame.zipWithNext().all { (a, b) -> distance(a, b) <= 0.39 } shouldBe true
        val options = AuroraSkyOptions(strokeDrift = 0.0, strokeLift = 0.0)
        val stroke = AuroraSkyShapes.stroke(AuroraShape.CRYSTAL, 5.0, 160, 1, 900, 217, 0.0, false, 2, options)
        stroke.filterIndexed { i, _ -> i % 2 == 0 } shouldBe frame.take(80)
        stroke.filterIndexed { i, _ -> i % 2 == 1 } shouldBe frame.drop(80)
    }

    "custom star closes five sharp points without a seam or a filled disk" {
        val star = AuroraSkyShapes.offsets(AuroraShape.STAR, 5.5, 101, 0)
        (distance(star.first(), star.last()) < 1e-12) shouldBe true
        val corners = (0 until 10).map { star[it * 10] }
        corners.filterIndexed { i, _ -> i % 2 == 0 }.all { abs(magnitude(it) - 5.5) < 1e-12 } shouldBe true
        corners.filterIndexed { i, _ -> i % 2 == 1 }.all { abs(magnitude(it) - 5.5 * 0.43) < 1e-12 } shouldBe true
        star.all { it.z == 0.0 && magnitude(it) >= 5.5 * 0.42 } shouldBe true
        val options = AuroraSkyOptions(strokeDrift = 0.0, strokeLift = 0.0)
        val stroke = AuroraSkyShapes.stroke(AuroraShape.STAR, 5.5, 120, 1, 1000, 103, 0.2, false, 2, options)
        // Tracers traverse contiguous halves. Interleaving does not tear the closed contour.
        (distance(stroke.first(), stroke.last()) < 1e-12) shouldBe true
        stroke.filterIndexed { i, _ -> i % 2 == 0 }.zipWithNext().all { (a, b) -> distance(a, b) < 0.33 } shouldBe true
    }

    "meteor flies first and then splits into four outward moving sparks even when reversed" {
        fun stroke(reverse: Boolean, options: AuroraSkyOptions = AuroraSkyOptions()) =
            AuroraSkyShapes.stroke(AuroraShape.METEOR, 8.0, 80, 1, 800, 0, 0.0, reverse, 4, options)
        val normal = stroke(false)
        val reversed = stroke(true)
        normal.first().x shouldBe -8.0
        normal[47].x shouldBe 8.0
        normal.take(48).zipWithNext().all { (a, b) -> b.x > a.x } shouldBe true
        normal.zip(reversed).all { (a, b) -> a.x == -b.x && a.y == b.y && a.z == b.z } shouldBe true
        val impact = normal[47]
        for (branch in 0 until 4) {
            val fragment = (0 until 8).map { normal[48 + it * 4 + branch] }
            (distance(fragment.first(), impact) < 0.5) shouldBe true
            fragment.zipWithNext().all { (a, b) -> distance(b, impact) > distance(a, impact) } shouldBe true
            (distance(fragment.last(), impact) in 2.0..3.5) shouldBe true
        }
        normal.takeLast(4).map { Pair(it.y, it.z) }.distinct().size shouldBe 4
        val wider = stroke(false, AuroraSkyOptions(meteorSplit = 0.4, meteorSpread = 0.7))
        wider[31].x shouldBe 8.0
        (distance(wider.last(), impact) > distance(normal.last(), impact) * 1.9) shouldBe true
    }

    "default options preserve coordinates captured from the accepted pre-options implementation" {
        // Captured from the previous compiled implementation, before exposing these constants in YAML.
        val expected = mapOf(
            AuroraShape.WAVE to listOf(
                AuroraOffset(-5.4792116866275995, -2.991579609866963, -0.04693033951206926),
                AuroraOffset(-1.4701652012487842, 0.8001086134371277, 0.2869919517618746),
                AuroraOffset(6.723243970416363, 0.6481682897326809, -0.1666710699058807)),
            AuroraShape.CUBE to listOf(
                AuroraOffset(7.634701587671902, 6.770178397864841, 1.9687600484275443),
                AuroraOffset(-0.14174240671297955, -4.2562618225628555, 8.259195644669521),
                AuroraOffset(1.4166318018482764, 3.8814181344519643, -9.61505576677541)),
            AuroraShape.RING to listOf(
                AuroraOffset(5.73904997278363, 1.415653122906609, -1.0291898005212519),
                AuroraOffset(-4.093938708766312, 3.3566537536188097, 2.7384516387770743),
                AuroraOffset(6.596816788028461, 0.8427280155500212, -0.8676600323594361)),
        )
        expected.forEach { (shape, reference) ->
            val points = AuroraSkyShapes.stroke(shape, 6.0, 96, 1, 800, 180, 0.22, false,
                if (shape == AuroraShape.CUBE) 3 else 1)
            listOf(0, 35, 95).zip(reference).forEach { (index, point) ->
                (distance(points[index], point) < 1e-12) shouldBe true
            }
        }
        val held = AuroraSkyShapes.heldMotion(5.5, 320, 600)
        (distance(held[80], AuroraOffset(0.23334523779156063, 0.10218241350314171, 0.0)) < 1e-12) shouldBe true
        (distance(held[160], AuroraOffset(0.32999999999999996, 0.1367467606131376, 0.0)) < 1e-12) shouldBe true
    }

    "wave and movement options change the prepared path rather than only the parsed settings" {
        val flat = AuroraSkyOptions(waveAmplitude = 0.0, waveSlope = 0.0, waveDepth = 0.0,
            strokeDrift = 0.0, strokeLift = 0.0, heldDrift = 0.0, heldLift = 0.0)
        val line = AuroraSkyShapes.stroke(AuroraShape.WAVE, 6.0, 96, 1, 800, 180, 0.0, false, 1, flat)
        line.first().x shouldBe -6.0
        line.last().x shouldBe 6.0
        line.all { it.y == 0.0 && it.z == 0.0 } shouldBe true
        val tuned = flat.copy(waveAmplitude = 0.5, waveCycles = 2.0, waveSlope = 0.1, waveDepth = 0.2)
        val wave = AuroraSkyShapes.offsets(AuroraShape.WAVE, 6.0, 9, 0, 800, tuned)
        (abs(wave[1].y - 2.55) < 1e-12) shouldBe true
        (abs(wave[1].z + 0.8485281374238571) < 1e-12) shouldBe true
        val travel = AuroraSkyShapes.stroke(AuroraShape.LINE, 6.0, 3, 1, 800, 0, 0.0, false, 1,
            flat.copy(strokeDrift = 0.5, strokeLift = 0.2))
        travel[1] shouldBe AuroraOffset(1.5, 1.2000000000000002, 0.0)
        (abs(travel.last().x - 9.0) < 1e-12) shouldBe true
        AuroraSkyShapes.heldMotion(6.0, 40, 40, flat).all { it.x == 0.0 && it.y == 0.0 && it.z == 0.0 } shouldBe true
        val held = AuroraSkyShapes.heldMotion(6.0, 40, 40, flat.copy(heldDrift = 0.2, heldLift = 0.1))
        (abs(held[20].x - 1.2) < 1e-12) shouldBe true
        (abs(held[10].y - 0.6) < 1e-12) shouldBe true
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
