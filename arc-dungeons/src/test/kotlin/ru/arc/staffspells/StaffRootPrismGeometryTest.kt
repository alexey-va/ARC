package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Vector3f

class StaffRootPrismGeometryTest : FreeSpec({
    "all sixteen root targets have a grounded network and individually keyed wraps within existing budgets" {
        val trunk = (0..12).map { Vector3f(0f, .12f, it.toFloat()) }
        val branches = (0 until 16).map { i ->
            StaffRootBranch("target-$i", listOf(Vector3f(0f, .12f, 12f),
                Vector3f((i % 4 - 2).toFloat(), .12f, 13f), Vector3f((i % 4 - 2).toFloat(), .12f, (14 + i / 4).toFloat())))
        }
        val network = staffNovaNetworkParts(30, 106, 30, trunk, branches)
        network.size shouldBe 44
        network.all { it.center.y == .12f } shouldBe true
        val targets = branches.map { StaffRootWrap(it.key, it.points.last(), .6, 1.8) }
        val wraps = targets.chunked(8).map { staffNovaWrapParts(8, 76, it) }
        wraps.map { it.size } shouldBe listOf(48, 48)
        (network.size + wraps.sumOf { it.size } <= StaffSpellDisplayEffects.MAX_PARTS_PER_CASTER) shouldBe true
        targets.forEach { target -> wraps.flatten().count { it.identity!!.startsWith("root:wrap:${target.key}:") } shouldBe 6 }
        (network + wraps.flatten()).all { part ->
            listOf(part.center.x, part.center.y, part.center.z, part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite)
        } shouldBe true
        staffNovaWrapParts(76, 76, targets).isEmpty() shouldBe true
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
