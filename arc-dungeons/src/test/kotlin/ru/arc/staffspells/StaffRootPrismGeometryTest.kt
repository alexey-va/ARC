package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Vector3f

class StaffRootPrismGeometryTest : FreeSpec({
    "both root fields use stable thick ground paths and only accepted victims receive wraps" {
        val fanPaths = staffNovaFanPaths(STAFF_NOVA_FAN_REACH)
        val snarePaths = staffNovaSnarePaths(4.5)
        val fan = staffNovaFanParts(STAFF_NOVA_CONTACT_TICK, STAFF_NOVA_FIELD_TICKS, STAFF_NOVA_FAN_REACH, fanPaths)
        val snare = staffNovaSnareParts(STAFF_NOVA_CONTACT_TICK, STAFF_NOVA_FIELD_TICKS, snarePaths)
        fan.size shouldBe 42
        snare.size shouldBe 40
        fanPaths.size shouldBe 7
        (fan.maxOf { it.center.x } - fan.minOf { it.center.x } > 15f) shouldBe true
        fan.filter { it.identity!!.endsWith(":0") }.all { it.scale.x >= .23f } shouldBe true
        fan.map { it.identity } shouldBe staffNovaFanParts(6, STAFF_NOVA_FIELD_TICKS, STAFF_NOVA_FAN_REACH, fanPaths).map { it.identity }
        snare.map { it.identity } shouldBe staffNovaSnareParts(8, STAFF_NOVA_FIELD_TICKS, snarePaths).map { it.identity }
        staffNovaFanFront(2, 18.0) shouldBe .75
        staffNovaFanFront(STAFF_NOVA_CONTACT_TICK, 18.0) shouldBe 18.0
        staffNovaFanParts(STAFF_NOVA_FIELD_TICKS, STAFF_NOVA_FIELD_TICKS, 18.0, fanPaths) shouldBe emptyList()
        staffNovaSnareParts(STAFF_NOVA_FIELD_TICKS, STAFF_NOVA_FIELD_TICKS, snarePaths) shouldBe emptyList()
        (fan + snare).none { it.identity!!.startsWith("root:wrap:") } shouldBe true

        val targets = (0 until 16).map { StaffRootWrap("target-$it", Vector3f(it.toFloat(), 0f, 12f), .6, 1.8) }
        val wraps = targets.chunked(8).map { staffNovaWrapParts(8, STAFF_NOVA_HOLD_TICKS, it) }
        wraps.map { it.size } shouldBe listOf(48, 48)
        (fan.size + wraps.sumOf { it.size } <= StaffSpellDisplayEffects.MAX_PARTS_PER_CASTER) shouldBe true
        targets.forEach { target -> wraps.flatten().count { it.identity!!.startsWith("root:wrap:${target.key}:") } shouldBe 6 }
        (fan + snare + wraps.flatten()).all { part ->
            listOf(part.center.x, part.center.y, part.center.z, part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite)
        } shouldBe true
        val fading = staffNovaWrapParts(68, STAFF_NOVA_HOLD_TICKS, targets.take(8))
        fading.map { it.identity } shouldBe wraps.first().map { it.identity }
        fading.zip(wraps.first()).all { (late, held) -> late.scale.x < held.scale.x * .3f } shouldBe true
        staffNovaWrapParts(STAFF_NOVA_HOLD_TICKS, STAFF_NOVA_HOLD_TICKS, targets).isEmpty() shouldBe true
        val later = targets.first().copy(ageOffset = 10)
        staffNovaWrapParts(8, STAFF_NOVA_HOLD_TICKS, listOf(later)) shouldBe emptyList()
        staffNovaWrapParts(70, STAFF_NOVA_HOLD_TICKS, listOf(later)).size shouldBe 6
        staffNovaWrapParts(80, STAFF_NOVA_HOLD_TICKS, listOf(later)) shouldBe emptyList()
    }

    "refraction grows serially through fixed visible mirrors and keeps stable part identities" {
        val points = listOf(Vector3f(0f, 1.6f, 0f), Vector3f(2f, 1.6f, 4f),
            Vector3f(-2f, 1.6f, 8f), Vector3f(1f, 1.6f, 11f))
        val early = staffPrismRefractionParts(2, 30, points)
        val full = staffPrismRefractionParts(14, 30, points)
        early.map { it.identity } shouldBe full.map { it.identity }
        early.count { it.identity!!.contains(":mirror:") } shouldBe 2
        val earlyBeams = early.filter { it.identity!!.contains(":beam:") }
        (earlyBeams.first().scale.z > 1f) shouldBe true
        (earlyBeams.last().scale.z <= .0011f) shouldBe true
        full.filter { it.identity!!.contains(":beam:") }.all { it.scale.z > 4f } shouldBe true
        (full.size <= 48) shouldBe true
        staffPrismRefractionParts(30, 30, points).isEmpty() shouldBe true
    }
})
