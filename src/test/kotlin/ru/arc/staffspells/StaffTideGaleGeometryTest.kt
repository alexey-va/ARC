package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Particle
import org.joml.Matrix4f

class StaffTideGaleGeometryTest : FreeSpec({
    "GALE and TIDE geometry stays finite at every animation age and charge" {
        fun isFinite(parts: List<StaffDisplayPart>): Boolean = parts.all { part ->
            val transform = Matrix4f().translationRotateScale(part.center, part.rotation, part.scale)
            listOf(part.center.x, part.center.y, part.center.z,
                part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                transform.get(FloatArray(16)).all(Float::isFinite)
        }

        listOf(StaffSpell.GALE to 52, StaffSpell.GALE to 40, StaffSpell.TIDE to 44,
            StaffSpell.TIDE to 44).forEachIndexed { index, (spell, duration) ->
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
            (0..16).forEach { age ->
                val impact = staffDisplayParts(StaffSpell.TIDE, age, 16, 20.0, 1.8, true, false,
                    input = StaffCastInput.PRIMARY, charge = charge)
                isFinite(impact) shouldBe true
            }
        }
    }

    "GALE primary is a tall twisting open funnel with no cloud particles" {
        val frame = staffDisplayParts(StaffSpell.GALE, 12, 52, 20.0, 3.0, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val next = staffDisplayParts(StaffSpell.GALE, 16, 52, 20.0, 3.0, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val bandIds = frame.mapNotNull { it.identity }.filter { it.startsWith("gale:tornado:band:") }
        frame.size shouldBe 39
        bandIds.size shouldBe 33
        frame.mapNotNull { it.identity }.distinct().size shouldBe frame.size
        next.mapNotNull { it.identity }.toSet() shouldBe frame.mapNotNull { it.identity }.toSet()
        (frame.maxOf { it.center.y } > 5.0f) shouldBe true
        (frame.map { it.center.x to it.center.z }.toSet().size > 20) shouldBe true
        (frame.map { it.center }.zip(next.map { it.center }).any { (a, b) -> a.distance(b) > .05f }) shouldBe true

        val motes = staffArsenalParticles(StaffSpell.GALE, 12, 52, 3.0, false, .5)
        motes.any { it.particle == Particle.GUST } shouldBe true
        motes.none { it.particle == Particle.CLOUD } shouldBe true
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

    "TIDE secondary is a five-block curled glass crest with a white foam lip" {
        val frame = staffDisplayParts(StaffSpell.TIDE, 12, 44, 20.0, 2.8, false, true,
            input = StaffCastInput.SECONDARY, charge = .5)
        val next = staffDisplayParts(StaffSpell.TIDE, 14, 44, 20.0, 2.8, false, true,
            input = StaffCastInput.SECONDARY, charge = .5)
        frame.size shouldBe 46
        frame.count { it.identity?.startsWith("tide:wave:skin:") == true } shouldBe 35
        frame.count { it.identity?.startsWith("tide:wave:foam:") == true } shouldBe 7
        val width = frame.maxOf { it.center.x } - frame.minOf { it.center.x }
        (width in 4.0f..5.2f) shouldBe true
        (frame.maxOf { it.center.y } in 2.5f..3.3f) shouldBe true
        frame.any { it.material == Material.WHITE_STAINED_GLASS } shouldBe true
        frame.all { it.material in setOf(Material.BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.LIGHT_BLUE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.SEA_LANTERN) } shouldBe true
        next.mapNotNull { it.identity }.toSet() shouldBe frame.mapNotNull { it.identity }.toSet()

        val motes = staffArsenalParticles(StaffSpell.TIDE, 12, 44, 3.0, true, .5)
        motes.any { it.particle == Particle.SPLASH } shouldBe true
        motes.none { it.particle == Particle.CLOUD } shouldBe true
    }

    "TIDE primary keeps a hollow glass shell continuous as the orb opens into a prison" {
        val flight = staffDisplayParts(StaffSpell.TIDE, 28, 44, 12.0, 2.7, false, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val arrival = staffDisplayParts(StaffSpell.TIDE, 0, 16, 12.0, 1.8, true, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        val prison = staffDisplayParts(StaffSpell.TIDE, 10, 16, 12.0, 1.8, true, false,
            input = StaffCastInput.PRIMARY, charge = .5)
        flight.size shouldBe 34
        arrival.size shouldBe 40
        flight.count { it.identity?.contains(":panel:") == true } shouldBe 32
        flight.count { it.identity?.contains(":pole:") == true } shouldBe 2
        flight.filter { it.identity?.contains(":panel:") == true }.all {
            it.scale.x > it.scale.z * 1.8f && it.scale.y > it.scale.z * 3.0f
        } shouldBe true
        prison.mapNotNull { it.identity }.toSet().containsAll(flight.mapNotNull { it.identity }.toSet()) shouldBe true
        flight.all { it.material in setOf(Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS) } shouldBe true
        val common = flight.associateBy { it.identity }
        arrival.filter { it.identity in common.keys }.all {
            val previous = common[it.identity]!!
            previous.center.distance(it.center) < .001f && previous.scale.distance(it.scale) < .001f
        } shouldBe true
        (prison.maxOf { it.center.length() } > flight.maxOf { it.center.length() } * 1.5f) shouldBe true

        val particles = staffArsenalParticles(StaffSpell.TIDE, 8, 16, 1.8, false, .5,
            impact = true)
        particles.any { it.particle == Particle.SPLASH } shouldBe true
        (particles.maxOf { it.offset.length() } > 1.0) shouldBe true
    }
})
