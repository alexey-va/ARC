package ru.arc.aurora

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
})

private fun AuroraOffset.vector() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
