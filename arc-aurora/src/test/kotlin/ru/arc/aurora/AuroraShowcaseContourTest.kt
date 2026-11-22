package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class AuroraShowcaseContourTest : FreeSpec({
    val settings = AuroraConfig.parse(YamlConfiguration().apply {
        for (shape in listOf(AuroraShape.CIRCLE, AuroraShape.CUBE)) {
            set("profiles.local_${shape.name}.shape", shape.name)
            set("profiles.local_${shape.name}.particle", "minecraft:smoke")
            set("profiles.local_${shape.name}.speed", 0.75)
        }
    })
    val effects = showcaseEffects(settings)

    "distant dots replace arbitrary biome particles while an authored sky profile keeps its material" {
        val disc = effects.getValue(AuroraShape.CIRCLE)
        val cube = effects.getValue(AuroraShape.CUBE)
        disc.particle shouldBe Particle.FLAME
        disc.particleData shouldBe null
        cube.particle shouldBe Particle.DUST
        val dust = cube.particleData as Particle.DustOptions
        dust.color.asRGB() shouldBe 0xEF72FF
        dust.size shouldBe 2.35f
        for (profile in listOf(disc, cube)) {
            profile.speed shouldBe 0.0
            (profile.points <= 4 * profile.skyRefreshTicks) shouldBe true
        }
        settings.profiles.all { it.particle == Particle.SMOKE && it.speed == 0.75 && !it.skyScene } shouldBe true
        val authored = settings.profiles.first().copy(skyScene = true, particle = Particle.END_ROD)
        showcaseEffects(settings.copy(eventPresets = mapOf("custom" to listOf(authored))))[authored.shape] shouldBe authored
    }

    "every slot presents a closed circular rim and punched face perpendicular to its own sightline" {
        val profile = effects.getValue(AuroraShape.CIRCLE)
        val panel = AuroraShowcase(UUID(0, 1), AuroraOffset(0.0, 0.0, 0.0), 0f, List(9) { profile }, 3600)
        for (index in panel.profiles.indices) {
            val pose = panel.pose(index)
            val slot = pose.viewerLocalSlot
            val points = checkNotNull(showcaseContour(profile.shape, pose.size, profile.points, slot))
            points.size shouldBe profile.points
            points.all { abs(-slot.x * it.x - slot.y * it.y + slot.z * it.z) < 1e-9 } shouldBe true
            val radii = points.map { length(it) / pose.size }
            radii.count { abs(it - 1.0) < 1e-10 } shouldBe profile.points / 2
            radii.count { it in 0.34..0.83 } shouldBe profile.points / 2
            points.toSet().size shouldBe points.size
        }
    }

    "the first cube samples mark all eight vertices and its held pose fills every edge" {
        val profile = effects.getValue(AuroraShape.CUBE)
        val points = checkNotNull(showcaseContour(profile.shape, 1.0, profile.points, AuroraOffset(-15.8, 28.0, 37.4)))
        val vertices = points.take(8).distinct()
        vertices.size shouldBe 8
        vertices.all { abs(length(it) - sqrt(3.0)) < 1e-10 } shouldBe true
        val edges = vertices.flatMapIndexed { index, a -> vertices.drop(index + 1)
            .filter { b -> abs(distance(a, b) - 2.0) < 1e-10 }.map { b -> a to b } }
        edges.size shouldBe 12
        for ((a, b) in edges) {
            for (step in 1..6) {
                val t = step / 7.0
                val expected = AuroraOffset(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z))
                points.any { distance(it, expected) < 1e-10 } shouldBe true
            }
        }
    }
})

private fun length(p: AuroraOffset) = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

private fun distance(a: AuroraOffset, b: AuroraOffset) = length(AuroraOffset(a.x - b.x, a.y - b.y, a.z - b.z))
