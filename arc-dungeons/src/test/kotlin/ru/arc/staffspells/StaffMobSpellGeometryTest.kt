package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.Vector
import org.joml.Vector3f
import kotlin.math.abs

class StaffMobSpellGeometryTest : FreeSpec({
    "mob Ember charge resolves to its real muzzle for horizontal vertical and pitched aim" {
        listOf(Vector(0.0, 0.0, 1.0), Vector(0.0, 1.0, 0.0), Vector(1.0, -.4, -1.0).normalize()).forEach { direction ->
            val muzzle = Location(null, 10.0, 65.4, -10.0)
            val eye = staffMobChargeEye(muzzle, direction, StaffSpell.EMBER)
            val offset = staffChargeOrientation(StaffSpell.EMBER, eye.yaw,
                Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())).transform(staffEmberChargeOffset())
            (eye.add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()).toVector()
                .distance(muzzle.toVector()) < 1e-5) shouldBe true
        }
    }
    "mark warning keeps its exact damage radius and four raised boundary cues through the final bright cue" {
        listOf(0, 8, 24, 32).forEach { age ->
            val points = staffMobMarkBoundary(age, 32, 1.6)
            points.size shouldBe 36
            points.all { abs(it.offset.x * it.offset.x + it.offset.z * it.offset.z - 1.6 * 1.6) < 1e-8 } shouldBe true
            points.count { it.offset.y == .06 } shouldBe 24
            val raised = points.filter { it.offset.y > .06 }
            raised.map { it.offset.x to it.offset.z }.toSet().size shouldBe 4
            raised.maxOf { it.offset.y } shouldBe .92
        }
        staffMobMarkBoundary(33, 32, 1.6) shouldBe emptyList()
        staffMobMarkBoundary(0, 32, Double.NaN) shouldBe emptyList()
    }
    "mob solar preparation stays at the muzzle within one scene budget" {
        listOf(0, 6, 12, 22).forEach { age ->
            val parts = staffMobLancePreparation(age, 30, Vector(0.0, 0.0, 1.0))
            (parts.isNotEmpty() && parts.size <= StaffSpellDisplayEffects.MAX_PARTS) shouldBe true
            parts.all { it.center.length() < 1.0 && it.scale.x > 0f && it.scale.y > 0f && it.scale.z > 0f } shouldBe true
            (parts.map { it.identity }.toSet().size == parts.size) shouldBe true
        }
    }
})
