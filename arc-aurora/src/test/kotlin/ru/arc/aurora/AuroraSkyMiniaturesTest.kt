package ru.arc.aurora

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f
import kotlin.math.abs

class AuroraSkyMiniaturesTest : FreeSpec({
    val glider = AuroraShape.FEATHER_GLIDER
    val materials = listOf(Material.DIAMOND, Material.FEATHER, Material.FEATHER, Material.PRISMARINE_SHARD)

    "the four glider slots keep their vanilla items, finite poses and swept radius throughout the flight" {
        AuroraSkyMiniatures.partCount(glider) shouldBe materials.size
        AuroraSkyMiniatures.partRadius(glider) shouldBe 0.86
        val frames = AuroraSkyMiniatures.frames(glider)
        frames.size shouldBe AuroraCelestialShapes.MAX_FRAMES
        frames.forEachIndexed { frame, points ->
            val t = frame.toDouble() / frames.lastIndex
            points.size shouldBe materials.size
            points.forEachIndexed { index, point ->
                val part = AuroraSkyMiniatures.part(glider, index, t)
                part.material shouldBe materials[index]
                point.offset shouldBe part.center
                val visible = frame in 1 until frames.lastIndex
                (point.opacity > 0.0) shouldBe visible
                (part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f) shouldBe visible
                point.offset.vector().isFinite shouldBe true
                (abs(part.rotation.lengthSquared() - 1f) < 1e-5f) shouldBe true
                // The four generated items are centered sprites with caps at Z ±1/32 in NONE.
                val radius = Vector3f(0.5f, 0.5f, 1f / 32f).mul(part.scale).length()
                (radius <= AuroraSkyMiniatures.partRadius(glider)) shouldBe true
            }
        }
    }

    "the glider spreads its wings during the bank and tucks them for the dive" {
        fun wingspan(t: Double) = AuroraSkyMiniatures.part(glider, 1, t).center.vector()
            .distance(AuroraSkyMiniatures.part(glider, 2, t).center.vector())
        val full = wingspan(0.46)
        (full > wingspan(0.11) * 1.5) shouldBe true
        (full > wingspan(0.94) * 1.5) shouldBe true
    }

    for (shape in storyShapes + listOf(AuroraShape.INFERNAL_PENTAGRAM, AuroraShape.FROG_RAIN)) {
        "$shape keeps stable item slots and moves a visible part" {
            val samples = (0..64).map { storyPose(shape, it / 64.0) }
            val materials = samples.first().map { it.material }
            materials.isNotEmpty() shouldBe true
            samples.forEachIndexed { frame, parts ->
                withClue("frame $frame") {
                    parts.map { it.material } shouldBe materials
                    parts.forEachIndexed { slot, part ->
                        withClue("slot $slot") {
                            part.center.vector().isFinite shouldBe true
                            part.scale.isFinite shouldBe true
                            (minOf(part.scale.x, part.scale.y, part.scale.z) >= 0f) shouldBe true
                            (abs(part.rotation.lengthSquared() - 1f) < 1e-5f) shouldBe true
                        }
                    }
                }
            }
            withClue("movement while the same part is visible") {
                materials.indices.any { slot ->
                    val visible = samples.map { it[slot] }.filter { it.visible && it.scale.length() > 0.10f }
                    val start = visible.firstOrNull() ?: return@any false
                    visible.any { it.center.vector().distance(start.center.vector()) > 0.10f }
                } shouldBe true
            }
            storyPose(shape, -0.01) shouldBe samples.first()
            storyPose(shape, 1.01) shouldBe samples.last()
        }
    }

    "looping figures hold their poses across the loop boundary" {
        for (shape in listOf(AuroraShape.CTHULHU, AuroraShape.DAGON, AuroraShape.HASTUR, AuroraShape.AZATHOTH,
            AuroraShape.YOG_SOTHOTH, AuroraShape.NYARLATHOTEP, AuroraShape.SHUB_NIGGURATH,
            AuroraShape.DEATH_STAR, AuroraShape.IMPERIAL_STAR_DESTROYER, AuroraShape.ENDLESS_TENTACLES,
            AuroraShape.BLINKING_EYE)) {
            val start = storyPose(shape, 0.0)
            val end = storyPose(shape, 1.0)
            withClue(shape) {
                (start.count { it.visible } > start.size / 2) shouldBe true
                for ((a, b) in start.zip(end)) {
                    b.visible shouldBe a.visible
                    if (a.visible) {
                        (a.center.vector().distance(b.center.vector()) < 0.001f) shouldBe true
                        (a.scale.distance(b.scale) < 0.001f) shouldBe true
                        (abs(a.rotation.dot(b.rotation)) > 0.999f) shouldBe true
                    }
                }
            }
        }
    }

    "the giant eye closes both lids over the center and opens them again" {
        val shape = AuroraShape.BLINKING_EYE
        fun lidGap(progress: Double): Double {
            val upper = shape.part(38, progress)
            val lower = shape.part(39, progress)
            upper.visible shouldBe true
            lower.visible shouldBe true
            return abs(upper.center.y - lower.center.y)
        }

        (lidGap(0.22) > 0.45) shouldBe true
        (lidGap(0.265) < 0.09) shouldBe true
        (lidGap(0.35) > 0.45) shouldBe true
    }

    "the five forged strokes join into a downward star with an overpass and an underpass per stroke" {
        val shape = AuroraShape.INFERNAL_PENTAGRAM
        fun end(part: AuroraMiniaturePart, side: Float) =
            part.rotation.transform(Vector3f(side * part.scale.x / 2f, 0f, 0f)).add(part.center.vector())
        val held = storyPose(shape, 0.75)
        held.all { it.visible } shouldBe true
        val earlier = storyPose(shape, 0.16)
        (earlier.count { it.visible } < held.size) shouldBe true
        for (chord in 0 until 5) {
            val start = chord * 6
            val first = held[start]
            val middle = held[start + 2]
            val last = held[start + 4]
            val next = held[(start + 6) % 30]
            (end(first, 1f).distance(end(middle, -1f)) < 0.0001f) shouldBe true
            (end(middle, 1f).distance(end(last, -1f)) < 0.0001f) shouldBe true
            (end(last, 1f).distance(end(next, -1f)) < 0.0001f) shouldBe true
            (end(first, 1f).z > 0.05f && end(last, -1f).z < -0.05f) shouldBe true
        }
        (end(held.first(), -1f).y < -0.95f) shouldBe true
        held.filterIndexed { index, _ -> index in 55..69 && (index - 55) % 3 == 2 }.map { it.material }.distinct().size shouldBe 5
        // All five relics burn before the amber slit opens, and stay lit after it closes.
        for (phase in listOf(0.665, 0.83)) {
            val seal = storyPose(shape, phase)
            (0 until 5).all { seal[57 + it * 3].visible } shouldBe true
            seal.last().visible shouldBe false
        }
        held.last().material shouldBe Material.HONEYCOMB
        storyPose(shape, 0.0).none { it.visible } shouldBe true
        storyPose(shape, 1.0).none { it.visible } shouldBe true
    }

    "all three frog colours fall independently and stay present across the full shower loop" {
        val shape = AuroraShape.FROG_RAIN
        val bodies = listOf(0, 11, 22)
        bodies.map { shape.part(it, 0.25).material } shouldBe
            listOf(Material.ORANGE_TERRACOTTA, Material.BONE_BLOCK, Material.MOSS_BLOCK)
        val ticks = shape.storyDurationTicks
        val poses = (0 until ticks).map { age -> bodies.map { shape.part(it, age.toDouble() / (ticks - 1)) } }
        poses.all { frame -> frame.count { it.visible } >= 2 } shouldBe true
        for (frog in bodies.indices) {
            val track = poses.map { it[frog] }
            val heights = track.filter { it.visible }.map { it.center.y }
            (heights.max() - heights.min() > 2.0) shouldBe true
            track.zipWithNext().none { (a, b) -> a.visible && b.visible && b.center.y - a.center.y > 0.5 } shouldBe true
        }
        (shape.part(0, 0.25).center.y < shape.part(0, 0.20).center.y) shouldBe true
    }

    "the fox dives into the cloud and comes back with berries" {
        val shape = AuroraShape.CLOUD_FOX
        shape.part(8, 5.4 / 12.0).visible shouldBe false
        shape.part(19, 5.4 / 12.0).visible shouldBe true
        shape.part(21, 5.4 / 12.0).visible shouldBe false
        val berries = shape.part(21, 8.77 / 12.0)
        berries.material shouldBe Material.SWEET_BERRIES
        berries.visible shouldBe true
    }


})

internal val storyShapes = listOf(
    AuroraShape.CLOUD_FOX,
    AuroraShape.VOID_CATHEDRAL,
    AuroraShape.SOLAR_CORSAIR,
    AuroraShape.ORK_KROOZER,
    AuroraShape.IMPERIAL_AQUILA,
    AuroraShape.MECHANICUS_SIGIL,
    AuroraShape.CTHULHU,
    AuroraShape.DAGON,
    AuroraShape.HASTUR,
    AuroraShape.AZATHOTH,
    AuroraShape.YOG_SOTHOTH,
    AuroraShape.NYARLATHOTEP,
    AuroraShape.SHUB_NIGGURATH,
    AuroraShape.ENDLESS_TENTACLES,
    AuroraShape.BLINKING_EYE,
    AuroraShape.JU_FUFU,
    AuroraShape.JU_FUFU_2D,
    AuroraShape.DEATH_STAR,
    AuroraShape.IMPERIAL_STAR_DESTROYER,
)

private fun storyPose(shape: AuroraShape, progress: Double) =
    List(AuroraSkyMiniatures.partCount(shape)) { slot -> shape.part(slot, progress) }

private fun AuroraShape.part(slot: Int, progress: Double) = AuroraSkyMiniatures.part(this, slot, progress)

private val AuroraMiniaturePart.visible: Boolean
    get() = minOf(scale.x, scale.y, scale.z) > 0f

private fun AuroraOffset.vector() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
