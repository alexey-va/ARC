package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt

class AuroraTrackShapesTest : FreeSpec({
    fun stroke(shape: AuroraShape, points: Int, reverse: Boolean = false, tracers: Int = 4,
        options: AuroraSkyOptions = AuroraSkyOptions()) =
        AuroraSkyShapes.stroke(shape, 7.0, points, 1, 400, 123, 0.0, reverse, tracers, options)

    "spiral advances at even spatial intervals toward a smaller final radius" {
        val path = stroke(AuroraShape.TRACK_SPIRAL, 96)
        path.size shouldBe 96
        val distances = path.zipWithNext(::trackDistance)
        (distances.max() / distances.min() < 1.04) shouldBe true
        path.zipWithNext().all { (a, b) -> trackLength(b) < trackLength(a) } shouldBe true
        (abs(trackLength(path.last()) - 7.0 * 0.18) < 1e-10) shouldBe true
        (path.minOf { it.y } < -2.0 && path.maxOf { it.y } > 4.0) shouldBe true
    }

    "pair heads start at one vertex and keep continuous opposite curvature" {
        val path = stroke(AuroraShape.TRACK_PAIR, 96)
        val a = path.filterIndexed { index, _ -> index % 2 == 0 }
        val b = path.filterIndexed { index, _ -> index % 2 == 1 }
        a.first() shouldBe AuroraOffset(0.0, 0.0, 0.0)
        b.first() shouldBe a.first()
        a.zip(b).all { (p, q) -> abs(p.x - q.x) < 1e-12 && abs(p.y + q.y) < 1e-12 } shouldBe true
        for (head in listOf(a, b)) {
            val steps = head.zipWithNext(::trackDistance)
            (steps.max() / steps.min() < 1.001) shouldBe true
            (steps.max() < 0.16) shouldBe true
        }
        (trackDistance(a.last(), b.last()) > 10.0) shouldBe true
    }

    "scatter reaches its vertex before three distinct continuous branches are born" {
        val path = stroke(AuroraShape.TRACK_SCATTER, 144, tracers = 3)
        val stem = path.take(36)
        stem.first() shouldBe AuroraOffset(-7.0, 0.0, 0.0)
        stem.last() shouldBe AuroraOffset(0.0, 0.0, 0.0)
        stem.zipWithNext().all { (a, b) -> b.x > a.x && b.y == 0.0 } shouldBe true
        val branches = (0 until 3).map { branch -> path.drop(36).filterIndexed { i, _ -> i % 3 == branch } }
        for (head in branches) {
            (trackDistance(stem.last(), head.first()) < 0.4) shouldBe true
            val steps = (listOf(stem.last()) + head).zipWithNext(::trackDistance)
            (steps.max() / steps.min() < 1.04) shouldBe true
            (steps.max() < 0.4) shouldBe true
        }
        (branches[0].last().y < -2.0) shouldBe true
        (branches[1].last().y > 4.0) shouldBe true
        // The third head curls back instead of extending as another straight fan ray.
        branches[2].zipWithNext().any { (a, b) -> b.x < a.x } shouldBe true
    }

    "track reverse mirrors space and tracer count never reorders birth or jumps a head" {
        for (shape in AuroraShape.entries.filter { it.isTrack }) {
            val normal = stroke(shape, 144)
            val reversed = stroke(shape, 144, reverse = true)
            normal.zip(reversed).all { (a, b) -> a.x == -b.x && a.y == b.y && a.z == b.z } shouldBe true
            stroke(shape, 144, tracers = 1) shouldBe normal
            val tuned = AuroraSkyOptions(trackTurns = 2.0, trackTightening = 0.4,
                trackBranchAngleDegrees = 40.0, trackBendDegrees = 110.0, trackSplit = 0.4)
            (stroke(shape, 144, options = tuned) != normal) shouldBe true
        }
    }

    "tracks keep all requested points within their configured extent at option boundaries" {
        for (options in listOf(
            AuroraSkyOptions(trackTurns = 0.5, trackTightening = 0.8, trackBranchAngleDegrees = 10.0,
                trackBendDegrees = 15.0, trackSplit = 0.1),
            AuroraSkyOptions(trackTurns = 3.0, trackTightening = 0.05, trackBranchAngleDegrees = 75.0,
                trackBendDegrees = 180.0, trackSplit = 0.6),
        )) for (shape in AuroraShape.entries.filter { it.isTrack }) for (points in listOf(1, 2, 7, 192)) {
            val path = stroke(shape, points, options = options)
            path.size shouldBe points
            path.all { p -> listOf(p.x, p.y, p.z).all { it.isFinite() && abs(it) <= 7.0 + 1e-10 } } shouldBe true
        }
    }
})

private fun trackLength(p: AuroraOffset) = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
private fun trackDistance(a: AuroraOffset, b: AuroraOffset) = trackLength(AuroraOffset(a.x - b.x, a.y - b.y, a.z - b.z))
