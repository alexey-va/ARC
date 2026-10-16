package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle
import org.joml.Matrix4f
import kotlin.math.abs

class StaffTideGaleGeometryTest : FreeSpec({
    "GALE and TIDE geometry stays finite at every animation age and charge" {
        fun isFinite(parts: List<StaffDisplayPart>): Boolean = parts.all { part ->
            val transform = Matrix4f().translationRotateScale(part.center, part.rotation, part.scale)
            listOf(part.center.x, part.center.y, part.center.z,
                part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                transform.get(FloatArray(16)).all(Float::isFinite)
        }

        listOf(StaffSpell.GALE to 52, StaffSpell.GALE to 40,
            StaffSpell.TIDE to STAFF_TIDE_PRIMARY_ATTACK_TICKS, StaffSpell.TIDE to 44).forEachIndexed { index, (spell, duration) ->
            val secondary = index % 2 == 1
            val input = StaffCastInput.of(secondary, false)
            listOf(0.0, .5, 1.0).forEach { charge ->
                (0..duration).forEach { age ->
                    val parts = staffDisplayParts(spell, age, duration, 20.0, 3.0, false,
                        secondary, input = input, charge = charge)
                    isFinite(parts) shouldBe true
                }
            }
        }

        listOf(0.0, .5, 1.0).forEach { charge ->
            (0..STAFF_TIDE_PRIMARY_IMPACT_TICKS).forEach { age ->
                val impact = staffDisplayParts(StaffSpell.TIDE, age, STAFF_TIDE_PRIMARY_IMPACT_TICKS, 20.0, 2.3, true, false,
                    input = StaffCastInput.PRIMARY, charge = charge)
                isFinite(impact) shouldBe true
            }
        }
    }

    "GALE primary is a tall twisting open funnel without smoke or burst sprites" {
        val frame = staffDisplayParts(StaffSpell.GALE, 12, 52, 20.0, 3.0, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val next = staffDisplayParts(StaffSpell.GALE, 16, 52, 20.0, 3.0, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val bandIds = frame.mapNotNull { it.identity }.filter { it.startsWith("gale:tornado:band:") }
        val bands = frame.filter { it.identity?.startsWith("gale:tornado:band:") == true }
        frame.size shouldBe 45
        bandIds.size shouldBe 39
        bands.all { it.scale.x in 1.00f..1.12f && it.scale.x > it.scale.y * 4.0f } shouldBe true
        frame.mapNotNull { it.identity }.distinct().size shouldBe frame.size
        next.mapNotNull { it.identity }.toSet() shouldBe frame.mapNotNull { it.identity }.toSet()
        (frame.maxOf { it.center.y } > 7.5f) shouldBe true
        (frame.map { it.center.x to it.center.z }.toSet().size > 20) shouldBe true
        (frame.map { it.center }.zip(next.map { it.center }).any { (a, b) -> a.distance(b) > .8f }) shouldBe true

        val motes = staffArsenalParticles(StaffSpell.GALE, 12, 52, 3.0, false, .5)
        (0..52).flatMap { age -> staffArsenalParticles(StaffSpell.GALE, age, 52, 3.0, false, .5) }
            .none { it.particle in setOf(Particle.GUST, Particle.GUST_EMITTER_LARGE,
                Particle.GUST_EMITTER_SMALL, Particle.EXPLOSION, Particle.EXPLOSION_EMITTER, Particle.CLOUD) } shouldBe true
        motes.count { it.particle == Particle.CRIT } shouldBe 4
        (motes.size <= 96) shouldBe true
    }

    "GALE secondary is a stable double-helical ram that travels along local positive Z" {
        val frame = staffDisplayParts(StaffSpell.GALE, 12, 40, 20.0, 3.0, false, true,
            input = StaffCastInput.SECONDARY, charge = .4)
        val next = staffDisplayParts(StaffSpell.GALE, 14, 40, 20.0, 3.0, false, true,
            input = StaffCastInput.SECONDARY, charge = .4)
        frame.size shouldBe 34
        frame.count { it.identity?.startsWith("gale:ram:helix:0:") == true } shouldBe 12
        frame.count { it.identity?.startsWith("gale:ram:helix:1:") == true } shouldBe 12
        next.mapNotNull { it.identity }.toSet() shouldBe frame.mapNotNull { it.identity }.toSet()
        ((frame.maxOf { it.center.z } - frame.minOf { it.center.z }) > 1.5f) shouldBe true

        val motes = staffArsenalParticles(StaffSpell.GALE, 12, 40, 2.5, true, .4)
        motes.any { it.particle == Particle.GUST } shouldBe true
        motes.none { it.particle == Particle.CLOUD } shouldBe true
    }

    "TIDE secondary is a broad curled glass crest with a white foam lip" {
        val frame = staffDisplayParts(StaffSpell.TIDE, 12, 44, 20.0, 2.8, false, true,
            input = StaffCastInput.SECONDARY, charge = .5)
        val next = staffDisplayParts(StaffSpell.TIDE, 14, 44, 20.0, 2.8, false, true,
            input = StaffCastInput.SECONDARY, charge = .5)
        frame.size shouldBe 46
        frame.count { it.identity?.startsWith("tide:wave:skin:") == true } shouldBe 35
        frame.count { it.identity?.startsWith("tide:wave:foam:") == true } shouldBe 7
        val width = frame.maxOf { it.center.x } - frame.minOf { it.center.x }
        (width in 5.2f..5.6f) shouldBe true
        staffTideWaveHalfWidth(0.0) shouldBe 3.0
        staffTideWaveHalfWidth(.5) shouldBe 3.15
        staffTideWaveHalfWidth(1.0) shouldBe 3.3
        (frame.maxOf { it.center.y } in 2.5f..3.3f) shouldBe true
        frame.filter { it.identity?.startsWith("tide:wave:skin:") == true }
            .all { it.scale.y in .85f..0.87f } shouldBe true
        val unchargedSkin = staffDisplayParts(StaffSpell.TIDE, 12, 44, 20.0, 2.8, false, true,
            input = StaffCastInput.SECONDARY, charge = 0.0)
            .filter { it.identity?.startsWith("tide:wave:skin:") == true }
        unchargedSkin.all { abs(it.scale.y - .86f) < .001f } shouldBe true
        val rows = (0..4).map { row ->
            unchargedSkin.filter { it.identity?.startsWith("tide:wave:skin:$row:") == true }
        }
        (0..3).all { row -> (0..6).all { index ->
            abs(rows[row][index].center.y - rows[row + 1][index].center.y) <= .72f
        } } shouldBe true
        val lip = frame.filter { it.identity?.startsWith("tide:wave:foam:") == true }
        (lip.minOf { it.center.z } > .8f) shouldBe true
        val lastSkin = frame.filter { it.identity?.startsWith("tide:wave:skin:4:") == true }
        lip.zip(lastSkin).all { (foam, skin) -> foam.center.distance(skin.center) < .1f && foam.center.z > skin.center.z }
            .shouldBe(true)
        frame.any { it.material == Material.WHITE_STAINED_GLASS } shouldBe true
        frame.all { it.material in setOf(Material.BLUE_CONCRETE, Material.BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.SEA_LANTERN) } shouldBe true
        next.mapNotNull { it.identity }.toSet() shouldBe frame.mapNotNull { it.identity }.toSet()

        val motes = staffArsenalParticles(StaffSpell.TIDE, 12, 44, 3.0, true, .5)
        motes.any { it.particle == Particle.SPLASH } shouldBe true
        motes.none { it.particle == Particle.CLOUD } shouldBe true
    }

    "GALE ram impact fans twelve arcing sheets outward without smoke" {
        val charge = .4
        val burstRadius = 2.3 + charge * .45
        val frame = staffTideGaleParts(StaffSpell.GALE, 3, 12, burstRadius,
            impact = true, secondary = true, charge = charge)
        frame.size shouldBe 36
        frame.count { it.identity?.startsWith("gale:burst:petal:") == true } shouldBe 36
        (frame.maxOf { it.center.y } > .8f) shouldBe true
        frame.mapNotNull { it.identity }.distinct().size shouldBe frame.size
        val edge = frame.maxOf { kotlin.math.sqrt((it.center.x * it.center.x + it.center.z * it.center.z).toDouble()) }
        // Arc centers sit inside the outer ring because each display is a chord, not a point on the edge.
        (edge in (burstRadius * .80)..(burstRadius + .03)) shouldBe true

        val release = staffTideGaleParticleSamples(StaffSpell.GALE, 0, 12, burstRadius,
            secondary = true, charge = charge, impact = true)
        release.count { it.particle == Particle.GUST } shouldBe 1
        release.size shouldBe 37
        release.none { it.particle in setOf(Particle.CLOUD, Particle.EXPLOSION, Particle.EXPLOSION_EMITTER) } shouldBe true
        staffTideGaleParticleSamples(StaffSpell.GALE, 12, 12, burstRadius,
            secondary = true, charge = charge, impact = true) shouldBe emptyList()
    }

    "TIDE primary reaches its clipped endpoint by tick 14 then opens into a hollow splash crown" {
        STAFF_TIDE_PRIMARY_LAUNCH_TICK shouldBe 2
        STAFF_TIDE_PRIMARY_TRAVEL_TICKS shouldBe 12
        STAFF_TIDE_PRIMARY_ARRIVAL_TICK shouldBe 14
        STAFF_TIDE_PRIMARY_IMPACT_TICKS shouldBe 12
        STAFF_TIDE_PRIMARY_ATTACK_TICKS shouldBe 26
        staffArsenalLifetime(StaffSpell.TIDE, secondary = false) shouldBe 26
        staffArsenalLifetime(StaffSpell.TIDE, secondary = true) shouldBe 44

        val flight = staffDisplayParts(StaffSpell.TIDE, 12, STAFF_TIDE_PRIMARY_ATTACK_TICKS, 12.0, 2.7, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val arrival = staffDisplayParts(StaffSpell.TIDE, 0, STAFF_TIDE_PRIMARY_IMPACT_TICKS, 12.0, 2.3, true, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val crown = staffDisplayParts(StaffSpell.TIDE, 6, STAFF_TIDE_PRIMARY_IMPACT_TICKS, 12.0, 2.3, true, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        flight.size shouldBe 34
        arrival.size shouldBe 32
        crown.size shouldBe 32
        flight.count { it.identity?.contains(":panel:") == true } shouldBe 32
        flight.count { it.identity?.contains(":pole:") == true } shouldBe 2
        flight.filter { it.identity?.contains(":panel:") == true }.all {
            it.scale.x > it.scale.z * 1.8f && it.scale.y > it.scale.z * 3.0f
        } shouldBe true
        arrival.mapNotNull { it.identity }.all { it.startsWith("tide:burst:") } shouldBe true
        crown.mapNotNull { it.identity }.distinct().size shouldBe crown.size
        crown.count { it.identity?.startsWith("tide:burst:crown:") == true } shouldBe 24
        crown.count { it.identity?.startsWith("tide:burst:drop:") == true } shouldBe 8
        crown.any { it.material == Material.SEA_LANTERN } shouldBe true
        crown.any { it.material == Material.CYAN_STAINED_GLASS } shouldBe true
        crown.none { it.identity?.startsWith("tide:prison:") == true } shouldBe true
        flight.all { it.material in setOf(Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS) } shouldBe true
        (crown.maxOf { it.center.x * it.center.x + it.center.z * it.center.z } > 2.5f) shouldBe true
        (crown.maxOf { it.center.y } > 1.2f) shouldBe true
        (arrival.maxOf { it.center.y } < crown.maxOf { it.center.y }) shouldBe true

        val particles = staffArsenalParticles(StaffSpell.TIDE, 0, STAFF_TIDE_PRIMARY_IMPACT_TICKS, 2.3, false, .5,
            impact = true)
        particles.size shouldBe 32
        particles.all { it.particle in setOf(Particle.SPLASH, Particle.FALLING_WATER) } shouldBe true
        particles.none { it.particle in setOf(Particle.EXPLOSION, Particle.EXPLOSION_EMITTER, Particle.CLOUD) } shouldBe true
        val expandedParticles = staffArsenalParticles(StaffSpell.TIDE, 6, STAFF_TIDE_PRIMARY_IMPACT_TICKS,
            2.3, false, .5, impact = true)
        (expandedParticles.maxOf { it.offset.length() } > 2.0) shouldBe true
        (expandedParticles.maxOf { it.offset.length() } > particles.maxOf { it.offset.length() } * 2) shouldBe true
        staffArsenalParticles(StaffSpell.TIDE, 10, STAFF_TIDE_PRIMARY_IMPACT_TICKS,
            2.3, false, .5, impact = true) shouldBe emptyList()
    }

    "wind accents follow the expanding display funnel instead of an inverted second cone" {
        listOf(0.0, .5, 1.0).forEach { charge ->
            listOf(6, 12, 24).forEach { age ->
                val ribbons = staffTideGaleParts(StaffSpell.GALE, age, 52, 3.0, false, false, charge)
                    .filter { it.identity?.startsWith("gale:tornado:band:") == true }
                val motes = staffTideGaleParticleSamples(StaffSpell.GALE, age, 52, 3.0, false, charge)
                    .filter { it.particle == Particle.DUST_COLOR_TRANSITION }
                motes.size shouldBe ribbons.size
                motes.zip(ribbons).all { (mote, ribbon) ->
                    mote.offset.distance(org.bukkit.util.Vector(ribbon.center.x, ribbon.center.y, ribbon.center.z)) < .12
                } shouldBe true
                val bottom = motes.filter { it.offset.y < 1.0 }.maxOf { it.offset.clone().apply { y = 0.0 }.length() }
                val top = motes.filter { it.offset.y > 4.5 }.minOf { it.offset.clone().apply { y = 0.0 }.length() }
                (top > bottom * 1.5) shouldBe true
            }
        }
    }

    "water spray curls forward with the foam lip at every charge level" {
        listOf(0.0, .5, 1.0).forEach { charge ->
            val lip = staffTideGaleParts(StaffSpell.TIDE, 12, 44, 3.0, false, true, charge)
                .filter { it.identity?.startsWith("tide:wave:foam:") == true }
            val spray = staffTideGaleParticleSamples(StaffSpell.TIDE, 12, 44, 3.0, true, charge)
                .filter { it.particle == Particle.SPLASH }
            spray.size shouldBe 14
            spray.all { mote ->
                mote.offset.z > .6 && lip.minOf { foam ->
                    mote.offset.distance(org.bukkit.util.Vector(foam.center.x, foam.center.y, foam.center.z))
                } < .55
            } shouldBe true
        }
    }

    "water orb flight changes into bounded splash and falling-water burst particles" {
        listOf(0.0, .5, 1.0).forEach { charge ->
            val flying = staffTideGaleParticleSamples(StaffSpell.TIDE, 12,
                STAFF_TIDE_PRIMARY_ATTACK_TICKS, 1.0, false, charge)
            val arrived = staffTideGaleParticleSamples(StaffSpell.TIDE, 6,
                STAFF_TIDE_PRIMARY_IMPACT_TICKS, 2.3, false, charge, impact = true)
            flying.size shouldBe 32
            arrived.size shouldBe 32
            flying.all { it.particle in setOf(Particle.BUBBLE, Particle.DUST_COLOR_TRANSITION, Particle.SPLASH) } shouldBe true
            flying.any { it.particle == Particle.DUST_COLOR_TRANSITION } shouldBe true
            flying.any { it.particle == Particle.BUBBLE } shouldBe true
            arrived.all { it.particle in setOf(Particle.SPLASH, Particle.FALLING_WATER) } shouldBe true
            (arrived.maxOf { it.offset.length() } > flying.maxOf { it.offset.length() } * 1.4) shouldBe true
        }
    }

    "exit stops new particles without pulling their spawn positions back into the center" {
        val steady = staffTideGaleParticleSamples(StaffSpell.GALE, 36, 52, 3.0, false, .5)
            .filter { it.particle == Particle.DUST_COLOR_TRANSITION }
        val last = staffTideGaleParticleSamples(StaffSpell.GALE, 44, 52, 3.0, false, .5)
            .filter { it.particle == Particle.DUST_COLOR_TRANSITION }
        steady.size shouldBe 39
        last.size shouldBe 39
        val steadyMeanRadius = steady.map { it.offset.length() }.average()
        val exitMeanRadius = last.map { it.offset.length() }.average()
        (exitMeanRadius / steadyMeanRadius in .97..1.03) shouldBe true
        staffTideGaleParticleSamples(StaffSpell.GALE, 46, 52, 3.0, false, .5) shouldBe emptyList()
        staffTideGaleParticleSamples(StaffSpell.TIDE, 38, 44, 3.0, true, .5) shouldBe emptyList()
    }
})
