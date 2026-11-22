package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AuroraShowcaseLayoutTest : FreeSpec({
    "the five-effect overview retains its original anchors sizes pitch and timing" {
        val profiles = listOf(AuroraShape.SKY_STITCH, AuroraShape.AURORA_CURTAIN, AuroraShape.INVISIBLE_HARE,
            AuroraShape.PAPER_FLOTILLA, AuroraShape.TUMBLEWEED).map(::layoutProfile)
        val poses = profiles.indices.map(layoutShowcase(profiles)::pose)
        poses.map { it.anchor } shouldBe listOf(AuroraOffset(10.0, 15.0, 32.0), AuroraOffset(-10.0, 15.0, 32.0),
            AuroraOffset(13.0, 7.0, 32.0), AuroraOffset(0.0, 7.0, 32.0), AuroraOffset(-13.0, 7.0, 32.0))
        poses.map { it.size } shouldBe listOf(6.0, 7.0, 4.0, 4.8, 4.0)
        poses.map { it.pitch } shouldBe listOf(0.0, 0.0, 42.0, 42.0, 12.0).map(Math::toRadians)
        poses.all { it.phaseOffsetTicks == 0 } shouldBe true
        AuroraShowcase.DURATION_TICKS shouldBe 1_200L
    }

    "nine separate sky anchors rotate with the starting yaw and cover the cycle without synchronizing" {
        val profile = layoutProfile(AuroraShape.SKY_STITCH)
        val copies = List(AuroraShowcase.COPY_COUNT) { profile }
        val poses = copies.indices.map(layoutShowcase(copies)::pose)
        poses.map { it.anchor }.toSet().size shouldBe 9
        poses.all { it.anchor.y > 0.0 && hypot(it.anchor.x, it.anchor.z) < 43.0 } shouldBe true
        val quarterTurn = layoutShowcase(copies, 90f)
        for ((index, pose) in poses.withIndex()) {
            val rotated = quarterTurn.pose(index)
            (abs(rotated.anchor.x + pose.anchor.z) < 1e-10) shouldBe true
            (abs(rotated.anchor.z - pose.anchor.x) < 1e-10) shouldBe true
            rotated.anchor.y shouldBe pose.anchor.y
        }
        val period = profile.sceneDurationTicks
        val phases = poses.map { it.phaseOffsetTicks }
        phases.sorted() shouldBe (0 until 9).map { it * period / 9 }
        phases.zipWithNext().all { (a, b) -> abs(a - b) > period / 4 } shouldBe true
        AuroraShowcase.SELECTED_DURATION_TICKS shouldBe 3_600L
    }

    "the full silhouettes in adjacent rows and columns have visible space between them" {
        for (shape in listOf(AuroraShape.AURORA_CURTAIN, AuroraShape.PAPER_FLOTILLA, AuroraShape.JELLYFISH,
            AuroraShape.CUBE, AuroraShape.SKY_LANTERNS)) {
            val points = when (shape) {
                AuroraShape.CUBE -> (0L until 400L step 25).flatMap { AuroraSkyShapes.offsets(shape, 1.0, 96, it) }
                AuroraShape.SKY_LANTERNS -> AuroraLanternMotion.frames(6).flatten()
                    .filter { it.opacity > 0.1 }.map { it.offset }
                else -> AuroraCelestialShapes.frames(shape, 1.0, 112).flatten()
                    .filter { it.opacity > 0.1 }.map { it.offset }
            }
            val profile = layoutProfile(shape)
            val copies = List(AuroraShowcase.COPY_COUNT) { profile }
            val panel = layoutShowcase(copies)
            val boxes = copies.indices.map { index -> projectedBounds(panel.pose(index), points) }
            for (index in boxes.indices) {
                if (index % 3 < 2) (boxes[index].right + 0.005 < boxes[index + 1].left) shouldBe true
                if (index < 6) (boxes[index].top + 0.005 < boxes[index + 3].bottom) shouldBe true
            }
        }
    }
})

private fun layoutProfile(shape: AuroraShape): AuroraProfile = mockk<AuroraProfile>().also { profile ->
    every { profile.shape } returns shape
    every { profile.shapeSize } returns 7.0
    every { profile.sceneDurationTicks } returns 247
}

private fun layoutShowcase(profiles: List<AuroraProfile>, yaw: Float = 0f) =
    AuroraShowcase(UUID(0L, 1L), AuroraOffset(0.0, 0.0, 0.0), yaw, profiles, 3_600L)

private data class SkyBounds(val left: Double, val bottom: Double, val right: Double, val top: Double)

/** Perspective bounds from eye level looking 18 degrees above the horizon; includes every sampled pose. */
private fun projectedBounds(pose: AuroraShowcase.Pose, points: List<AuroraOffset>): SkyBounds {
    val angle = Math.toRadians(18.0)
    val projected = points.map { point ->
        val right = -pose.anchor.x + point.x * pose.size
        val up = pose.anchor.y + (point.y * cos(pose.pitch) - point.z * sin(pose.pitch)) * pose.size
        val forward = pose.anchor.z - (point.y * sin(pose.pitch) + point.z * cos(pose.pitch)) * pose.size
        val depth = forward * cos(angle) + up * sin(angle)
        right / depth to (up * cos(angle) - forward * sin(angle)) / depth
    }
    return SkyBounds(projected.minOf { it.first }, projected.minOf { it.second },
        projected.maxOf { it.first }, projected.maxOf { it.second })
}
