package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs

class AuroraSkyMiniaturesTest : FreeSpec({
    val glider = AuroraShape.FEATHER_GLIDER
    val clock = AuroraShape.ASTRAL_PENDULUM
    val seeds = AuroraShape.TWIN_SAMARAS
    val player = AuroraShape.SKY_PHONOGRAPH

    "every packet slot keeps one vanilla model, a finite pose and an envelope covering the item" {
        val materials = mapOf(
            glider to listOf(Material.DIAMOND, Material.FEATHER, Material.FEATHER, Material.PRISMARINE_SHARD),
            clock to listOf(Material.CLOCK, Material.GOLD_NUGGET, Material.LIGHTNING_ROD, Material.HONEYCOMB),
            seeds to listOf(Material.GLOW_BERRIES, Material.WHEAT, Material.GLOW_BERRIES, Material.WHEAT),
            player to listOf(Material.JUKEBOX, Material.MUSIC_DISC_CREATOR, Material.GOAT_HORN, Material.SUNFLOWER,
                Material.END_ROD, Material.IRON_NUGGET),
        )
        for ((shape, expected) in materials) {
            AuroraSkyMiniatures.partCount(shape) shouldBe expected.size
            val frames = AuroraSkyMiniatures.frames(shape)
            frames.size shouldBe AuroraCelestialShapes.MAX_FRAMES
            frames.forEachIndexed { frame, points ->
                val t = frame.toDouble() / frames.lastIndex
                points.size shouldBe expected.size
                points.forEachIndexed { index, point ->
                    val part = AuroraSkyMiniatures.part(shape, index, t)
                    part.material shouldBe expected[index]
                    point.offset shouldBe part.center
                    val visible = frame in 1 until frames.lastIndex
                    (point.opacity > 0.0) shouldBe visible
                    (part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f) shouldBe visible
                    point.offset.vector().isFinite shouldBe true
                    (abs(part.rotation.lengthSquared() - 1f) < 1e-5f) shouldBe true
                    // NONE uses a centered unit cube for the jukebox, a 4/16-wide cap for rods, and extruded sprites.
                    val radii = when (part.material) {
                        Material.JUKEBOX -> Vector3f(0.5f)
                        Material.LIGHTNING_ROD, Material.END_ROD -> Vector3f(0.125f, 0.5f, 0.125f)
                        else -> Vector3f(0.5f, 0.5f, 1f / 32f)
                    }
                    (radii.mul(part.scale).length() <= AuroraSkyMiniatures.partRadius(shape)) shouldBe true
                }
            }
        }
    }

    "the glider unfolds then tucks, and the two released seeds turn in opposite directions" {
        fun width(shape: AuroraShape, first: Int, second: Int, t: Double) =
            AuroraSkyMiniatures.part(shape, first, t).center.vector().distance(AuroraSkyMiniatures.part(shape, second, t).center.vector())
        val full = width(glider, 1, 2, 0.46)
        (full > width(glider, 1, 2, 0.11) * 1.5) shouldBe true
        (full > width(glider, 1, 2, 0.94) * 1.5) shouldBe true
        (width(seeds, 0, 2, 0.16) < 0.25) shouldBe true
        (width(seeds, 0, 2, 0.73) > 1.0) shouldBe true
        fun sweep(head: Int): Float {
            fun vane(t: Double) = AuroraSkyMiniatures.part(seeds, head + 1, t).center.vector()
                .sub(AuroraSkyMiniatures.part(seeds, head, t).center.vector())
            return vane(0.51).cross(vane(0.55)).y
        }
        (sweep(0) < -0.03 && sweep(2) > 0.03) shouldBe true
        for (head in listOf(0, 2)) {
            val fall = AuroraSkyMiniatures.part(seeds, head, 0.18).center.y - AuroraSkyMiniatures.part(seeds, head, 0.82).center.y
            (fall > 0.9) shouldBe true
        }
    }

    "the pendulum bob stays on the rod, and the pickup lowers onto the turning record then lifts" {
        fun extremity(part: AuroraMiniaturePart, end: Float) =
            part.rotation.transform(Vector3f(0f, end * part.scale.y / 2, 0f)).add(part.center.vector())
        for (sample in 0..100) {
            val t = sample / 100.0
            val rod = AuroraSkyMiniatures.part(clock, 2, t)
            val bob = AuroraSkyMiniatures.part(clock, 3, t)
            // The bob face sits slightly ahead of the rod so its copper tip stays behind the gold disk.
            (extremity(rod, -1f).distance(bob.center.vector()) < 0.076) shouldBe true
            val arm = AuroraSkyMiniatures.part(player, 4, t)
            val pickup = AuroraSkyMiniatures.part(player, 5, t)
            (extremity(arm, 1f).distance(pickup.center.vector()) < 1e-5) shouldBe true
        }
        fun gap(t: Double): Float {
            val deck = AuroraSkyMiniatures.part(player, 0, t)
            val record = AuroraSkyMiniatures.part(player, 1, t)
            val pickup = AuroraSkyMiniatures.part(player, 5, t)
            val inverse = Quaternionf(deck.rotation).conjugate()
            val delta = inverse.transform(pickup.center.vector().sub(record.center.vector()))
            return delta.y - pickup.scale.y * 3f / 16f - record.scale.z / 32f
        }
        (gap(0.29) > 0.32) shouldBe true
        for (sample in 46..76 step 2) (abs(gap(sample / 100.0)) < 1e-5) shouldBe true
        (gap(0.89) > 0.28) shouldBe true
        val before = AuroraSkyMiniatures.part(player, 1, 0.52)
        val after = AuroraSkyMiniatures.part(player, 1, 0.70)
        fun spindle(record: AuroraMiniaturePart) =
            record.rotation.transform(Vector3f(record.scale.x / 32f, 0f, 0f)).add(record.center.vector())
        (spindle(before).distance(spindle(after)) < 1e-5) shouldBe true
        (abs(before.rotation.dot(after.rotation)) < 0.8f) shouldBe true
        (abs(before.scale.x * 15.0 / 16 - before.scale.y * 10.0 / 16) < 1e-6) shouldBe true
    }
})

private fun AuroraOffset.vector() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
