package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f

class StaffChargeGeometryTest : FreeSpec({
    "only ICICLE and EMBER expose charge geometry" {
        StaffSpell.entries.filterNot { it in setOf(StaffSpell.ICICLE, StaffSpell.EMBER) }.forEach { spell ->
            staffChargeParts(spell, 0.5, 18) shouldBe emptyList<StaffDisplayPart>()
        }
    }

    "icicle charge grows from no geometry to eleven real bodies at stable anchors" {
        val empty = staffChargeParts(StaffSpell.ICICLE, 0.0, 0)
        val half = staffChargeParts(StaffSpell.ICICLE, 0.5, 18)
        val full = staffChargeParts(StaffSpell.ICICLE, 1.0, 36)

        full.size shouldBe 88
        empty shouldBe emptyList()
        half.size shouldBe 48
        half.map { it.identity } shouldBe full.take(48).map { it.identity }
        full.map { it.identity }.distinct().size shouldBe full.size
        full.all { it.visible } shouldBe true
        (0..10).forEach { index ->
            full.count { it.identity!!.startsWith("icicle-charge:$index:") } shouldBe 8
        }
        (0..6).forEach { slot ->
            staffIcicleChargeAnchorOffset(slot) shouldBe staffIcicleAnchorOffset(slot, 7)
        }
        val anchors = (0..10).map(::staffIcicleChargeAnchorOffset)
        (anchors.map { it.z }.distinct().size >= 7) shouldBe true
        (anchors.maxOf { it.y } - anchors.minOf { it.y } > 2f) shouldBe true
        // Layout is stable over time, even though it is deliberately irregular.
        staffChargeParts(StaffSpell.ICICLE, 1.0, 80) shouldBe full
        staffIcicleCount(Double.NaN) shouldBe 0
        staffIcicleCount(Double.POSITIVE_INFINITY) shouldBe 0
        staffIcicleCount(-1.0) shouldBe 0
        staffIcicleCount(0.0) shouldBe 0
        staffIcicleCount(0.001) shouldBe 1
        staffIcicleCount(0.25) shouldBe 3
        staffIcicleCount(0.5) shouldBe 6
        staffIcicleCount(0.75) shouldBe 9
        staffIcicleCount(1.0) shouldBe 11
        staffIcicleCount(2.0) shouldBe 11

        (0..10).forEach { index ->
            val threshold = index / 11.0
            val before = staffIcicleChargeParts(threshold, index)
            val growing = staffIcicleChargeParts(threshold + 0.5 / 11.0, index)
            val complete = staffIcicleChargeParts(threshold + 1.0 / 11.0, index)
            before shouldBe emptyList()
            (growing.sumOf { it.scale.length().toDouble() } > before.sumOf { it.scale.length().toDouble() }) shouldBe true
            (growing.sumOf { it.scale.length().toDouble() } < complete.sumOf { it.scale.length().toDouble() }) shouldBe true
        }

        val body = staffIcicleDisplayParts(ageTicks = 8, impact = false)
        body.map { it.identity } shouldBe (0..7).map { "icicle:$it" }
        val localSlot = staffIcicleChargeParts(1.0, 0)
        localSlot.map { it.identity } shouldBe body.map { it.identity }
        localSlot.map { it.material } shouldBe body.map { it.material }
        (0..10).map { staffIcicleChargeParts(1.0, it).first().rotation }.distinct().size shouldBe 11
        val aggregatedFirst = full.filter { it.identity!!.startsWith("icicle-charge:0:") }
        aggregatedFirst.map { it.material } shouldBe localSlot.map { it.material }
        aggregatedFirst.zip(localSlot).all { (preview, charged) ->
            Vector3f(preview.center).sub(staffIcicleChargeAnchorOffset(0)).distance(charged.center) < 1.0e-6f
        } shouldBe true
        for (step in 0..100) {
            val charge = step / 100.0
            val visibleSlots = (0..10).count { staffIcicleChargeParts(charge, it).isNotEmpty() }
            visibleSlots shouldBe staffIcicleCount(charge)
        }
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

        val core = full.single { it.identity == "core:0" }
        core.material shouldBe Material.MAGMA_BLOCK
        core.center shouldBe staffEmberChargeOffset()
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

    "primary ember charge and flight are the same body at different local offsets" {
        val offset = staffEmberChargeOffset()
        for (charge in listOf(0.0, 0.37, 1.0)) {
            val held = staffChargeParts(StaffSpell.EMBER, charge, 10)
            val flight = staffDisplayParts(StaffSpell.EMBER, ageTicks = 0, durationTicks = 80,
                length = 24.0, radius = 0.85, impact = false, charge = charge)
            val laterFlight = staffDisplayParts(StaffSpell.EMBER, ageTicks = 20, durationTicks = 80,
                length = 24.0, radius = 0.85, impact = false, charge = charge)

            held.size shouldBe 21
            flight.map { it.identity } shouldBe held.map { it.identity }
            laterFlight shouldBe flight
            held.zip(flight).forEach { (charged, projectile) ->
                charged.material shouldBe projectile.material
                charged.scale shouldBe projectile.scale
                charged.rotation shouldBe projectile.rotation
                (Vector3f(charged.center).sub(offset).distance(projectile.center) < 1.0e-6f) shouldBe true
            }
        }
        val impact = staffDisplayParts(StaffSpell.EMBER, ageTicks = 0, durationTicks = 80,
            length = 24.0, radius = 0.85, impact = true)
        impact.size shouldBe 48
        impact.take(2).map { it.identity } shouldBe listOf("core:0", "core:1")

        val meteor = staffDisplayParts(StaffSpell.EMBER, ageTicks = 0, durationTicks = 80,
            length = 24.0, radius = 0.85, impact = false, secondary = true)
        meteor.size shouldBe 14
        meteor.take(4).map { it.identity } shouldBe (0..3).map { "core:$it" }
        meteor.drop(4).map { it.identity } shouldBe (0..9).map { "wake:$it" }
    }

    "the scattered crown stays in view and leaves the reticle clear throughout charge and camera pitch" {
        for (yaw in listOf(0f, 73f, -135f)) for (pitch in listOf(-75f, 0f, 60f)) {
            val camera = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
                .rotateX(Math.toRadians(pitch.toDouble()).toFloat())
            val direction = camera.transform(Vector3f(0f, 0f, 1f))
            val orientation = staffChargeOrientation(StaffSpell.ICICLE, yaw, direction)
            val toCamera = Quaternionf(camera).conjugate().mul(orientation)
            for (charge in listOf(0.0, 0.5, 1.0)) {
                staffChargeParts(StaffSpell.ICICLE, charge, 36).forEach { part ->
                    for (x in listOf(-0.5f, 0.5f)) for (y in listOf(-0.5f, 0.5f)) for (z in listOf(-0.5f, 0.5f)) {
                        val vertex = toCamera.transform(part.rotation.transform(Vector3f(x, y, z).mul(part.scale))
                            .add(part.center))
                        // Default 70-degree vertical FOV at 16:9, with a clear central aiming corridor.
                        (vertex.z > 1.5f) shouldBe true
                        (kotlin.math.abs(vertex.x / vertex.z) < 1.20f) shouldBe true
                        (vertex.y / vertex.z in -0.62f..0.68f) shouldBe true
                        (kotlin.math.abs(vertex.x / vertex.z) > 0.16f || kotlin.math.abs(vertex.y / vertex.z) > 0.16f) shouldBe true
                    }
                }
            }
        }
    }

    "charged crown follows pitch without rolling while legacy cues stay horizontal" {
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
            StaffSpell.ICICLE, StaffSpell.FAULT, StaffSpell.LANCE)
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
