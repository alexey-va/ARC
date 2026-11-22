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

    "the station and destroyer keep their hulls visible across the loop boundary" {
        for (shape in listOf(AuroraShape.DEATH_STAR, AuroraShape.IMPERIAL_STAR_DESTROYER)) {
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

    "the enderman's eyes appear before it takes the sky tile and carries it away" {
        val shape = AuroraShape.ENDERMAN_SKY_THEFT
        shape.part(1, 0.67 / 13.0).visible shouldBe true
        shape.part(0, 0.67 / 13.0).visible shouldBe false
        val attached = shape.part(14, 4.2 / 13.0)
        val carried = shape.part(14, 8.2 / 13.0)
        attached.material shouldBe Material.OBSIDIAN
        carried.visible shouldBe true
        (attached.center.x - carried.center.x > 0.4) shouldBe true
        (carried.center.z - attached.center.z > 0.2) shouldBe true
    }

    "the fishing float dives and pulls up an island attached to the hook" {
        val shape = AuroraShape.SKY_FISHING
        fun at(slot: Int, seconds: Double) = shape.part(slot, seconds / 8.4)
        val dip = at(5, 2.65)
        (at(5, 2.0).center.y - dip.center.y > 0.2) shouldBe true
        at(9, 2.0).visible shouldBe false
        val island = at(9, 3.3)
        island.material shouldBe Material.GRASS_BLOCK
        island.visible shouldBe true
        (island.center.y - at(9, 2.65).center.y > 0.2) shouldBe true
        (island.center.vector().distance(at(8, 3.3).center.vector()) < 0.5f) shouldBe true
    }

    "frost forms ahead of the landing boot as an old ice tile breaks away" {
        val shape = AuroraShape.FROST_WALKER
        fun at(slot: Int, seconds: Double) = shape.part(slot, seconds / 7.8)
        at(12, 1.25).visible shouldBe false
        val newIce = at(12, 1.60)
        newIce.material shouldBe Material.PACKED_ICE
        newIce.visible shouldBe true
        (at(0, 1.25).center.y - at(0, 1.74).center.y > 0.25) shouldBe true
        val falling = at(8, 1.57)
        falling.visible shouldBe true
        (at(8, 0.95).center.y - falling.center.y > 0.10) shouldBe true
        at(8, 1.83).visible shouldBe false
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

    "the totem restores the missing heart lobe and the whole heart pulses twice" {
        val shape = AuroraShape.TOTEM_HEART
        fun lobe(t: Double) = shape.part(13, t)
        lobe(0.1).visible shouldBe false
        val totem = shape.part(19, 0.39)
        totem.material shouldBe Material.TOTEM_OF_UNDYING
        totem.visible shouldBe true
        shape.part(19, 0.57).visible shouldBe false
        lobe(0.57).visible shouldBe true
        (lobe(0.62).scale.x > lobe(0.70).scale.x * 1.2f) shouldBe true
        (lobe(0.775).scale.x > lobe(0.70).scale.x * 1.2f) shouldBe true
    }

    "the ghast's body goes through the portal before the last tentacle" {
        val shape = AuroraShape.GHAST_PORTAL
        val crossing = 9.65 / 11.8
        shape.part(4, crossing).visible shouldBe true
        shape.part(5, crossing).visible shouldBe false
        shape.part(21, crossing).visible shouldBe true
        shape.part(21, 11.5 / 11.8).visible shouldBe false
    }

    "lightning reveals three horses again over the gap and they land on the far cloud" {
        val shape = AuroraShape.STORM_HERD
        for (spine in listOf(0, 13, 26)) {
            val first = shape.part(spine, 0.16)
            val airborne = shape.part(spine, 0.51)
            val landed = shape.part(spine, 0.70)
            first.visible shouldBe true
            shape.part(spine, 0.34).visible shouldBe false
            airborne.visible shouldBe true
            landed.visible shouldBe true
            (airborne.center.y - first.center.y > 0.12) shouldBe true
            (airborne.center.y - landed.center.y > 0.12) shouldBe true
        }
        val firstFlash = shape.part(45, 0.135)
        val secondFlash = shape.part(45, 0.43)
        firstFlash.visible shouldBe true
        secondFlash.visible shouldBe true
        (secondFlash.center.x - firstFlash.center.x > 1.0) shouldBe true
    }

    "the anvil stays against the squashed slime and flies off after the rebound" {
        val shape = AuroraShape.SLIME_ANVIL
        fun at(slot: Int, seconds: Double) = shape.part(slot, seconds / 6.3)
        val rest = at(0, 1.25)
        val compressed = at(0, 2.25)
        val anvil = at(4, 2.25)
        val airborne = at(4, 3.55)
        anvil.material shouldBe Material.ANVIL
        (compressed.scale.y < rest.scale.y * 0.25f) shouldBe true
        (compressed.scale.x > rest.scale.x * 1.3f) shouldBe true
        val bodyTop = compressed.center.y + compressed.scale.y / 2
        val anvilBottom = anvil.center.y - anvil.scale.y / 2
        (abs(bodyTop - anvilBottom) < 1e-5) shouldBe true
        airborne.visible shouldBe true
        (airborne.center.y - anvil.center.y > 0.75) shouldBe true
        at(4, 5.2).visible shouldBe false
    }

    "separate sword shards close to the hilt before the sword rises as a whole" {
        val shape = AuroraShape.SHATTERED_SWORD
        fun sword(t: Double) = shape.part(9, t)
        val approaching = shape.part(4, 0.11)
        val connected = shape.part(4, 0.32)
        approaching.visible shouldBe true
        connected.visible shouldBe true
        (connected.center.x - approaching.center.x > 0.45) shouldBe true
        sword(0.32).visible shouldBe false
        shape.part(4, 0.70).visible shouldBe false
        sword(0.70).material shouldBe Material.DIAMOND_SWORD
        sword(0.70).visible shouldBe true
        (sword(0.94).center.y - sword(0.70).center.y > 0.25) shouldBe true
    }

    "firework rays converge before the rebuilt rocket drops" {
        val shape = AuroraShape.REVERSE_FIREWORK
        fun span(t: Double) = shape.part(0, t).center.vector().distance(shape.part(7, t).center.vector())
        fun rocket(t: Double) = shape.part(16, t)
        (span(0.44) < span(0.14) * 0.55f) shouldBe true
        rocket(0.44).visible shouldBe false
        rocket(0.67).material shouldBe Material.FIREWORK_ROCKET
        rocket(0.67).visible shouldBe true
        rocket(0.93).visible shouldBe true
        (rocket(0.67).center.y - rocket(0.93).center.y > 0.45) shouldBe true
    }
})

internal val storyShapes = listOf(
    AuroraShape.ENDERMAN_SKY_THEFT,
    AuroraShape.SKY_FISHING,
    AuroraShape.FROST_WALKER,
    AuroraShape.CLOUD_FOX,
    AuroraShape.TOTEM_HEART,
    AuroraShape.GHAST_PORTAL,
    AuroraShape.STORM_HERD,
    AuroraShape.SLIME_ANVIL,
    AuroraShape.SHATTERED_SWORD,
    AuroraShape.REVERSE_FIREWORK,
    AuroraShape.VOID_CATHEDRAL,
    AuroraShape.SOLAR_CORSAIR,
    AuroraShape.ORK_KROOZER,
    AuroraShape.IMPERIAL_AQUILA,
    AuroraShape.MECHANICUS_SIGIL,
    AuroraShape.KRAKEN_DRAG,
    AuroraShape.RLYEH_RISING,
    AuroraShape.DAGON_TIDE,
    AuroraShape.NIGHT_GAUNT,
    AuroraShape.COLOUR_FROM_SPACE,
    AuroraShape.SHOGGOTH_ESCAPE,
    AuroraShape.SILVER_KEY,
    AuroraShape.DEATH_STAR,
    AuroraShape.IMPERIAL_STAR_DESTROYER,
)

private fun storyPose(shape: AuroraShape, progress: Double) =
    List(AuroraSkyMiniatures.partCount(shape)) { slot -> shape.part(slot, progress) }

private fun AuroraShape.part(slot: Int, progress: Double) = AuroraSkyMiniatures.part(this, slot, progress)

private val AuroraMiniaturePart.visible: Boolean
    get() = minOf(scale.x, scale.y, scale.z) > 0f

private fun AuroraOffset.vector() = Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
