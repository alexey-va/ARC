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
                trackBranchAngleDegrees = 40.0, trackBendDegrees = 110.0, trackSplit = 0.4,
                lensSpread = 0.7, knotRadius = 0.4, flybyGap = 0.5, flockSpread = 0.2,
                kinkAngleDegrees = 90.0, rippleSpacing = 0.44)
            (stroke(shape, 144, options = tuned) != normal) shouldBe true
        }
    }

    "lens keeps three continuous heads through a shared waist and reverses their lanes" {
        val path = stroke(AuroraShape.TRACK_LENS, 147, tracers = 3)
        val heads = (0 until 3).map { h -> path.filterIndexed { i, _ -> i % 3 == h } }
        for (head in heads) {
            head.first().x shouldBe -7.0
            head.last().x shouldBe 7.0
            (trackLength(head[24]) < 1e-10) shouldBe true
            head.zipWithNext().all { (a, b) -> b.x > a.x && trackDistance(a, b) < 0.35 } shouldBe true
        }
        (heads[0].first().y < -3.0 && heads[0].last().y > 3.0) shouldBe true
        (heads[2].first().y > 3.0 && heads[2].last().y < -3.0) shouldBe true
    }

    "knot crosses its live earlier trail then escapes without a coordinate jump" {
        val path = stroke(AuroraShape.TRACK_KNOT, 72)
        path.first().x shouldBe -7.0
        (abs(path.last().x - 7.0) < 1e-10) shouldBe true
        path.zipWithNext().any { (a, b) -> b.x < a.x } shouldBe true
        val steps = path.zipWithNext(::trackDistance)
        (steps.max() / steps.min() < 1.06) shouldBe true
        // Find a genuine transverse segment intersection, not two adjacent samples touching.
        fun cross(a: AuroraOffset, b: AuroraOffset, p: AuroraOffset) =
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        val crossings = (0 until path.size - 1).flatMap { i ->
            (i + 3 until path.size - 1).filter { j ->
                cross(path[i], path[i + 1], path[j]) * cross(path[i], path[i + 1], path[j + 1]) < 0 &&
                    cross(path[j], path[j + 1], path[i]) * cross(path[j], path[j + 1], path[i + 1]) < 0
            }.map { j -> i to j }
        }
        crossings.size shouldBe 1
        val (first, second) = crossings.single()
        // Four adjacent samples per visit: the crossing happens before FLAME's 12-tick minimum expiry.
        (second / 4 - first / 4 < 12) shouldBe true
        (path.last().x > path[second].x + 3.0) shouldBe true
    }

    "tracks keep all requested points within their configured extent at option boundaries" {
        for (options in listOf(
            AuroraSkyOptions(trackTurns = 0.5, trackTightening = 0.8, trackBranchAngleDegrees = 10.0,
                trackBendDegrees = 15.0, trackSplit = 0.1, lensSpread = 0.15, knotRadius = 0.15,
                flybyGap = 0.1, flockSpread = 0.1, kinkAngleDegrees = 35.0, rippleSpacing = 0.2),
            AuroraSkyOptions(trackTurns = 3.0, trackTightening = 0.05, trackBranchAngleDegrees = 75.0,
                trackBendDegrees = 180.0, trackSplit = 0.6, lensSpread = 0.9, knotRadius = 0.45,
                flybyGap = 0.7, flockSpread = 0.4, kinkAngleDegrees = 120.0, rippleSpacing = 0.45),
        )) for (shape in AuroraShape.entries.filter { it.isTrack }) for (points in listOf(1, 2, 7, 192)) {
            val path = stroke(shape, points, options = options)
            path.size shouldBe points
            path.all { p -> listOf(p.x, p.y, p.z).all { it.isFinite() && abs(it) <= 7.0 + 1e-10 } } shouldBe true
        }
    }

    "opposing flyby heads approach bow around one another and leave on opposite sides" {
        val path = stroke(AuroraShape.TRACK_FLYBY, 128)
        val a = path.filterIndexed { i, _ -> i % 2 == 0 }
        val b = path.filterIndexed { i, _ -> i % 2 == 1 }
        a.first().x shouldBe -7.0
        b.first().x shouldBe 7.0
        a.last().x shouldBe 7.0
        b.last().x shouldBe -7.0
        a.zip(b).all { (p, q) -> abs(p.x + q.x) < 1e-12 && abs(p.y + q.y) < 1e-12 } shouldBe true
        (a.zip(b, ::trackDistance).min() > 3.8) shouldBe true
        a.zipWithNext().all { (p, q) -> q.x > p.x && trackDistance(p, q) < 0.25 } shouldBe true
    }

    "flock trades lanes first then one head lags while two rise" {
        val path = stroke(AuroraShape.TRACK_FLOCK, 144, tracers = 3)
        val heads = (0 until 3).map { head -> path.filterIndexed { i, _ -> i % 3 == head } }
        for (head in heads) head.zipWithNext().all { (a, b) -> trackDistance(a, b) < 0.5 } shouldBe true
        (heads[1].first().y > heads[2].first().y) shouldBe true
        (heads[1][27].y < heads[2][27].y) shouldBe true
        (heads[0].last().x + 3.0 < heads[1].last().x) shouldBe true
        (heads[0].last().y < heads[0][27].y) shouldBe true
        (heads[0].last().x - heads[0][27].x in 0.65..0.75) shouldBe true
        for (leader in heads.drop(1)) {
            val rise = leader.last().y - leader[27].y
            val forward = leader.last().x - leader[27].x
            (rise > 4.5) shouldBe true
            // The exit is primarily upward; forward travel must not hide the two-leader gesture.
            (rise > forward * 1.1) shouldBe true
        }
    }

    "kink keeps its vertex and changes heading without a gap" {
        val options = AuroraSkyOptions(trackSplit = 0.45)
        val path = stroke(AuroraShape.TRACK_KINK, 96, tracers = 3, options = options)
        val vertex = (96 * options.trackSplit).toInt() - 1
        val before = path[vertex - 1]; val at = path[vertex]; val after = path[vertex + 1]
        val ax = at.x - before.x; val ay = at.y - before.y
        val bx = after.x - at.x; val by = after.y - at.y
        val turn = kotlin.math.acos((ax * bx + ay * by) / sqrt((ax * ax + ay * ay) * (bx * bx + by * by)))
        (turn * 180.0 / Math.PI in 55.0..65.0) shouldBe true
        path.zipWithNext().all { (a, b) -> trackDistance(a, b) < 0.2 } shouldBe true
    }

    "ripple births four separate short curves in successive forward positions" {
        val path = stroke(AuroraShape.TRACK_RIPPLE, 96, tracers = 2)
        val ripples = path.chunked(24)
        ripples.size shouldBe 4
        for (ripple in ripples) {
            ripple.zipWithNext().all { (a, b) -> b.x > a.x && trackDistance(a, b) < 0.25 } shouldBe true
            (ripple.maxOf { it.y } > ripple.first().y + 1.3) shouldBe true
            (abs(ripple.first().y - ripple.last().y) < 1e-12) shouldBe true
        }
        ripples.zipWithNext().all { (a, b) -> b.first().x > a.first().x + 2.0 && b.first().y > a.first().y } shouldBe true
    }
})

private fun trackLength(p: AuroraOffset) = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
private fun trackDistance(a: AuroraOffset, b: AuroraOffset) = trackLength(AuroraOffset(a.x - b.x, a.y - b.y, a.z - b.z))
