package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.PI

class StaffConjurationGeometryTest : FreeSpec({
    "FAULT Q barrier is fully visible at admission and stays aligned to its virtual-wall footprint" {
        val first = staffFaultBarrierParts(0, PI / 5.0)
        val settled = staffFaultBarrierParts(12, PI / 5.0)
        val identities = first.mapNotNull { it.identity }

        first.size shouldBe 18
        settled.size shouldBe 18
        identities.distinct().size shouldBe first.size
        settled.mapNotNull { it.identity } shouldBe identities
        first.zip(settled).all { (a, b) ->
            a.center.distance(b.center) < 0.001f && a.scale.distance(b.scale) < 0.001f
        } shouldBe true
        settled.all { part ->
            val transform = Matrix4f().translationRotateScale(part.center, part.rotation, part.scale)
            listOf(part.center.x, part.center.y, part.center.z, part.scale.x, part.scale.y, part.scale.z,
                part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                transform.get(FloatArray(16)).all(Float::isFinite)
        } shouldBe true
        (settled.maxOf { it.center.y + it.scale.y / 2f } <= 2.9f) shouldBe true
        (settled.maxOf { kotlin.math.abs(it.center.x) + it.scale.x / 2f } <= 2.2f) shouldBe true
        (settled.minOf { it.center.y - it.scale.y / 2f } >= -0.1f) shouldBe true
    }

    "FAULT Q recast releases twelve uniquely keyed plates into the wall normal" {
        val first = staffFaultBarrierShatterParts(0, PI / 4.0)
        val next = staffFaultBarrierShatterParts(5, PI / 4.0)
        first.size shouldBe 12
        first.mapNotNull { it.identity }.distinct().size shouldBe 12
        next.mapNotNull { it.identity }.toSet() shouldBe first.mapNotNull { it.identity }.toSet()
        (next.maxOf { it.center.z } > first.maxOf { it.center.z }) shouldBe true
        first.zip(next).all { (a, b) ->
            val forward = Vector3f(kotlin.math.sin((PI / 4.0).toFloat()), 0f,
                kotlin.math.cos((PI / 4.0).toFloat()))
            Vector3f(b.center).sub(a.center).dot(forward) > 0f &&
                b.scale.x < a.scale.x && b.scale.y < a.scale.y && b.scale.z < a.scale.z
        } shouldBe true
    }

    "radial conjurations surround their anchors instead of rotating their centers into one column" {
        listOf(staffFrostThawParts(7), staffNovaSeedParts(12), staffRequiemImpactParts(5)).forEach { parts ->
            (parts.minOf { it.center.x } < -0.3f && parts.maxOf { it.center.x } > 0.3f) shouldBe true
            (parts.minOf { it.center.z } < -0.3f && parts.maxOf { it.center.z } > 0.3f) shouldBe true
        }
        val target = org.bukkit.util.Vector(1.0, 0.2, -3.0)
        staffMarkTetherParts(0, target).filter { it.identity!!.startsWith("mark:q:ring:") }
            .forEachIndexed { index, part ->
                val anchor = if (index >= 8) Vector3f(1f, 0.2f, -3f) else Vector3f()
                val delta = Vector3f(part.center).sub(anchor)
                (kotlin.math.sqrt(delta.x * delta.x + delta.z * delta.z) in 0.9f..1.01f) shouldBe true
            }
    }

    "TIDE capsule is a centered translucent shell with an empty target interior" {
        val initial = staffTideCapsuleParts(0)
        initial.size shouldBe 24
        listOf(0, 12, 24).forEach { age ->
            val parts = staffTideCapsuleParts(age)
            parts.map { it.identity } shouldBe initial.map { it.identity }
            parts.mapNotNull { it.identity }.distinct().size shouldBe 24
            parts.all { it.material.name.endsWith("STAINED_GLASS") } shouldBe true
            val body = parts.filter { it.identity!!.contains(":body:") }
            (kotlin.math.abs(body.sumOf { it.center.x.toDouble() }) < 0.001) shouldBe true
            (kotlin.math.abs(body.sumOf { it.center.z.toDouble() }) < 0.001) shouldBe true
            body.all { part ->
                val normal = part.rotation.transform(Vector3f(0f, 0f, 1f))
                normal.dot(Vector3f(part.center.x, 0f, part.center.z)) > 0.8f && part.scale.x > 0.68f
            } shouldBe true
            parts.all { kotlin.math.sqrt(it.center.x * it.center.x + it.center.z * it.center.z) > 0.68f } shouldBe true
        }
    }

    "Q aftermaths remain bounded and retain their authored identities while opening or recoiling" {
        val sequences = listOf(
            (0 until 12).map { staffMarkTetherBreakParts(it, org.bukkit.util.Vector(4.5, .2, 0.0)) },
            (0 until 18).map(::staffFrostThawParts),
            (0 until 14).map(::staffNovaSeedBloomParts),
            (0 until 10).map(::staffTideCapsuleReleaseParts),
            (0 until 14).map { staffRequiemImpactParts(it, 4.1) },
        )
        sequences.forEach { frames ->
            frames.forEach { parts ->
                (parts.size in 1..48) shouldBe true
                parts.map { it.identity } shouldBe frames.first().map { it.identity }
                parts.mapNotNull { it.identity }.distinct().size shouldBe parts.size
                parts.all { listOf(it.center.x, it.center.y, it.center.z, it.scale.x, it.scale.y, it.scale.z,
                    it.rotation.x, it.rotation.y, it.rotation.z, it.rotation.w).all(Float::isFinite) &&
                    it.scale.x > 0f && it.scale.y > 0f && it.scale.z > 0f } shouldBe true
            }
        }
        val flower = staffNovaSeedBloomParts(6)
        (flower.maxOf { it.center.y } > 2.3f) shouldBe true
        (flower.maxOf { it.center.x } - flower.minOf { it.center.x } > 3f) shouldBe true
        val bell = staffRequiemBellParts(18)
        bell.none { it.material == org.bukkit.Material.BELL } shouldBe true
        (bell.maxOf { it.center.x } - bell.minOf { it.center.x } > 3.4f) shouldBe true
        (bell.minOf { it.center.y - it.scale.y / 2 } > 2.4f) shouldBe true
        bell.any { it.identity!!.contains(":clapper:") } shouldBe true
        staffRequiemBellParts(64).map { it.center } shouldBe bell.map { it.center }
        val water = staffTideCapsuleReleaseParts(5)
        (water.maxOf { it.center.x } > staffTideCapsuleParts(12).maxOf { it.center.x } * 1.5f) shouldBe true
    }

    "OSSUARY jaw holds open from admission then hinges inward with its original stable parts" {
        val armed = staffOssuaryJawParts(18)
        val closeStart = staffOssuaryJawClosingParts(0)
        val closed = staffOssuaryJawClosingParts(7)
        armed.mapNotNull { it.identity } shouldBe closeStart.mapNotNull { it.identity }
        closeStart.mapNotNull { it.identity } shouldBe closed.mapNotNull { it.identity }
        staffOssuaryJawParts(0).map { it.center } shouldBe armed.map { it.center }
        (armed.zip(closeStart).all { (a, b) -> a.center.distance(b.center) < 0.001f }) shouldBe true
        (closed.maxOf { kotlin.math.abs(it.center.x) } < 0.5f) shouldBe true
        (closeStart.maxOf { kotlin.math.abs(it.center.x) } > 1.9f) shouldBe true
        (closed.maxOf { it.center.y } > closeStart.maxOf { it.center.y } + 1f) shouldBe true
        staffOssuaryJawClosingParts(5).map { it.center } shouldBe closed.map { it.center }
    }

    "GALE gust visibly crosses along local positive X through contact and dissipates inside its fixed corridor" {
        val initial = staffGaleCorridorParts(0)
        val contact = staffGaleCorridorParts(7)
        contact.map { it.identity } shouldBe initial.map { it.identity }
        (contact.map { it.center.x }.average() - initial.map { it.center.x }.average() > 1.8) shouldBe true
        listOf(0, 7, 10, 20).forEach { age ->
            val parts = staffGaleCorridorParts(age)
            parts.all { kotlin.math.abs(it.center.x) < 2f } shouldBe true
        }
        staffGaleCorridorParts(20).all { it.scale.length() < .002f } shouldBe true
    }
})
