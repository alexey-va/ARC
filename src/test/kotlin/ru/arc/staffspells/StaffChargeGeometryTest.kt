package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Vector3f

class StaffChargeGeometryTest : FreeSpec({
    "all spell families assemble fixed charge slots into distinct formations" {
        val silhouettes = mutableSetOf<List<Triple<Float, Float, Float>>>()
        StaffSpell.entries.filterNot { it in setOf(StaffSpell.INFERNO, StaffSpell.GRAVITY) }.forEach { spell ->
            val empty = staffChargeParts(spell, 0.0, 0)
            val half = staffChargeParts(spell, 0.5, 18)
            val full = staffChargeParts(spell, 1.0, 36)

            full.size shouldBe (if (spell == StaffSpell.ICICLE) 33 else 21)
            empty.size shouldBe full.size
            empty.map { it.material } shouldBe full.map { it.material }
            empty.map { it.visible } shouldBe full.map { it.visible }
            empty.all { it.visible } shouldBe true
            (empty.sumOf { it.scale.length().toDouble() } < half.sumOf { it.scale.length().toDouble() }) shouldBe true
            (half.sumOf { it.scale.length().toDouble() } < full.sumOf { it.scale.length().toDouble() }) shouldBe true
            full.all { it.center.length() - it.scale.length() / 2 > 1.15f } shouldBe true
            silhouettes += full.map { Triple(it.center.x, it.center.y, it.center.z) }
        }
        silhouettes.size shouldBe StaffSpell.entries.size - 2
    }

    "full charge enlarges the localized projectile core as well as its wake" {
        listOf(StaffSpell.EMBER, StaffSpell.MARK).forEach { spell ->
            fun parts(charge: Double) = staffDisplayParts(spell, ageTicks = 10, durationTicks = 20,
                length = 12.0, radius = 3.0, impact = false, charge = charge)
            val weak = parts(0.0).first()
            val full = parts(1.0).first()
            full.center shouldBe weak.center
            (full.scale.x >= weak.scale.x * 1.39f) shouldBe true
            (full.scale.y >= weak.scale.y * 1.39f) shouldBe true
        }
    }

    "ember charge grows a stable native-block fireball ahead on the reticle" {
        val empty = staffChargeParts(StaffSpell.EMBER, 0.0, 0)
        val half = staffChargeParts(StaffSpell.EMBER, 0.5, 18)
        val full = staffChargeParts(StaffSpell.EMBER, 1.0, 36)

        full.size shouldBe 21
        empty.map { it.identity } shouldBe full.map { it.identity }
        full.map { it.identity }.distinct().size shouldBe full.size
        empty.map { it.material } shouldBe full.map { it.material }
        full.map { it.material }.toSet().containsAll(setOf(
            Material.MAGMA_BLOCK, Material.ORANGE_CONCRETE, Material.RED_CONCRETE,
            Material.GLOWSTONE, Material.YELLOW_CONCRETE,
        )) shouldBe true

        val weakById = empty.associateBy { it.identity }
        val halfById = half.associateBy { it.identity }
        val fullById = full.associateBy { it.identity }
        fullById.forEach { (identity, part) ->
            val weak = weakById.getValue(identity)
            val middle = halfById.getValue(identity)
            (weak.scale.x <= middle.scale.x && middle.scale.x <= part.scale.x) shouldBe true
            (weak.scale.y <= middle.scale.y && middle.scale.y <= part.scale.y) shouldBe true
            (weak.scale.z <= middle.scale.z && middle.scale.z <= part.scale.z) shouldBe true
        }

        val core = full.single { it.identity == "ember-charge:0:crust" }
        core.material shouldBe Material.MAGMA_BLOCK
        core.center shouldBe Vector3f(0f, -0.88f, 2.9f)
        full.all { part ->
            part.center.length() - part.scale.length() * 0.5f >
                staffEyeClearance(StaffSpell.EMBER, impact = false).toFloat() + 0.15f
        } shouldBe true
        full.all { part ->
            val xAxisY = part.rotation.transform(Vector3f(1f, 0f, 0f)).y
            val yAxisY = part.rotation.transform(Vector3f(0f, 1f, 0f)).y
            val zAxisY = part.rotation.transform(Vector3f(0f, 0f, 1f)).y
            val halfHeight = (kotlin.math.abs(xAxisY) * part.scale.x +
                kotlin.math.abs(yAxisY) * part.scale.y +
                kotlin.math.abs(zAxisY) * part.scale.z) * 0.5f
            part.center.y + halfHeight < -0.15f
        } shouldBe true
        // Keep every rotated vertex inside the lower edge of a default 70-degree vertical FOV.
        full.forEach { part ->
            for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
                val vertex = part.rotation.transform(Vector3f(x, y, z).mul(part.scale)).add(part.center)
                (vertex.z > 0f && vertex.y / vertex.z > -0.65f) shouldBe true
            }
        }
    }

    "only ember charge follows aim pitch" {
        val direction = Vector3f(-0.27f, 0.31f, 0.91f).normalize()
        val emberForward = staffChargeOrientation(StaffSpell.EMBER, yaw = 73f, direction = direction)
            .transform(Vector3f(0f, 0f, 1f))
        (emberForward.distance(direction) < 1.0e-5f) shouldBe true

        val chainForward = staffChargeOrientation(StaffSpell.CHAIN, yaw = 90f, direction = direction)
            .transform(Vector3f(0f, 0f, 1f))
        (kotlin.math.abs(chainForward.y) < 1.0e-6f) shouldBe true
        (chainForward.x < -0.999f) shouldBe true
    }

    "released legacy spell support grows with charge without moving attack geometry" {
        val legacy = listOf(StaffSpell.CHAIN, StaffSpell.MARK, StaffSpell.FROST,
            StaffSpell.ICICLE, StaffSpell.FAULT, StaffSpell.LANCE, StaffSpell.EMBER)
        legacy.forEach { spell ->
            fun parts(charge: Double) = staffDisplayParts(spell, ageTicks = 20, durationTicks = 48,
                length = 18.0, radius = 3.5, impact = false, charge = charge)

            val empty = parts(0.0)
            val half = parts(0.5)
            val full = parts(1.0)
            empty.size shouldBe full.size
            empty.map { it.material } shouldBe full.map { it.material }
            empty.map { it.center } shouldBe half.map { it.center }
            half.map { it.center } shouldBe full.map { it.center }
            empty.map { it.visible } shouldBe full.map { it.visible }
            (empty.sumOf { it.scale.length().toDouble() } < half.sumOf { it.scale.length().toDouble() }) shouldBe true
            (half.sumOf { it.scale.length().toDouble() } < full.sumOf { it.scale.length().toDouble() }) shouldBe true
        }
    }
})
