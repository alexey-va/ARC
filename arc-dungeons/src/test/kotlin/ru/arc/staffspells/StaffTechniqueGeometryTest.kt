package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.joml.Vector3f
import kotlin.math.abs

class StaffTechniqueGeometryTest : FreeSpec({
    "lunar boomerang keeps its twelve-block crescent while turning and returning" {
        val frames = (0..STAFF_LUNAR_TOTAL_TICKS).map { age ->
            staffLunarBoomerangParts(if (age < 11) 0f else Math.PI.toFloat(), age)
        }
        val identities = frames.first().map { it.identity }
        frames.forEach { parts ->
            parts.size shouldBe 12
            parts.map { it.identity } shouldBe identities
            parts.mapNotNull { it.identity }.distinct().size shouldBe 12
            parts.all { part ->
                listOf(part.center.x, part.center.y, part.center.z,
                    part.scale.x, part.scale.y, part.scale.z,
                    part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                    part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
            } shouldBe true
        }
        val initial = frames.first()
        val corners = initial.flatMap { part ->
            listOf(-0.5f, 0.5f).flatMap { x -> listOf(-0.5f, 0.5f).flatMap { y ->
                listOf(-0.5f, 0.5f).map { z ->
                    part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                        .add(part.center)
                }
            } }
        }
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        ((maxX - minX) in 1.8f..2.2f) shouldBe true
        (initial.map { it.center }.distinctBy { (it.x * 100).toInt() to (it.y * 100).toInt() }.size > 8) shouldBe true
        frames[STAFF_LUNAR_OUTBOUND_TICKS].map { it.identity } shouldBe identities
        frames[STAFF_LUNAR_OUTBOUND_TICKS + STAFF_LUNAR_TURN_TICKS].map { it.identity } shouldBe identities
    }

    "lunar path reaches its authored outbound apex, turns in place, and returns inside its travel budget" {
        val origin = Vector3f(2f, 64f, -3f)
        val forward = Vector3f(0.6f, 0.7f, 0.8f).normalize()
        forward.y = 0f
        forward.normalize()
        val caster = Vector3f(origin)
        val poses = (0..STAFF_LUNAR_TOTAL_TICKS).map { age ->
            staffLunarBoomerangPose(origin, forward, caster, age)
        }
        val apex = poses[STAFF_LUNAR_OUTBOUND_TICKS].position
        (apex.distance(origin) in 17.99f..18.01f) shouldBe true
        poses[STAFF_LUNAR_OUTBOUND_TICKS + 1].position.distance(apex) shouldBe 0f
        poses[STAFF_LUNAR_OUTBOUND_TICKS + STAFF_LUNAR_TURN_TICKS].position.distance(apex) shouldBe 0f
        poses.last().position.distance(caster) shouldBe 0f
        poses.last().complete shouldBe true
        poses.dropLast(1).all { !it.complete } shouldBe true
        val returnYaw = kotlin.math.atan2(forward.x, -forward.z).toFloat()
        (abs(wrapAngleForTest(poses.last().facingYaw - returnYaw)) < 1.0e-5f) shouldBe true
        (abs(wrapAngleForTest(poses[12].facingYaw - poses[11].facingYaw)) < (Math.PI / 2.0).toFloat()) shouldBe true
        val distance = poses.zipWithNext().sumOf { (a, b) -> a.position.distance(b.position).toDouble() }
        (distance <= STAFF_LUNAR_MAX_TRAVEL_BLOCKS) shouldBe true
        STAFF_LUNAR_TOTAL_TICKS shouldBe 26
        (abs(kotlin.math.sqrt(forward.x * forward.x + forward.z * forward.z) - 1f) < 1.0e-6f) shouldBe true
    }

    "the remaining technique geometries retain finite bounded pieces and stable identities" {
        val chain = staffChainTechniqueParts(.45f, 0)
        val chainIds = chain.map { it.identity }
        chain.size shouldBe 9
        listOf(1, 2, 4, 7, 12).forEach { pulse ->
            val frame = staffChainTechniqueParts(.45f, pulse)
            frame.map { it.identity } shouldBe chainIds
            assertFiniteParts(frame, 9)
        }

        val primaryIcicle = staffIcicleDisplayParts(8, impact = false, charge = .6)
        val recall = staffIcicleRecallParts(5, .6, .7f, -.25f)
        recall.size shouldBe 8
        recall.map { it.identity } shouldBe primaryIcicle.map { it.identity }
        assertFiniteParts(recall, 8)

        val prism = staffPrismTripwireParts(.35f, 3)
        assertFiniteParts(prism, 9)
        val origin = Location(null, 10.0, 64.0, -4.0)
        staffPrismTripwireContains(origin.clone().add(0.0, .9, 0.0), origin, 0f) shouldBe true
        staffPrismTripwireContains(origin.clone().add(4.0, .9, 0.0), origin, 0f) shouldBe false
        staffPrismTripwireParts(0f, 0).map { it.identity } shouldBe prism.map { it.identity }

        val heldGravity = staffGravityPinParts(4, thrown = false, yaw = .2f)
        val thrownGravity = staffGravityPinParts(5, thrown = true, yaw = .8f)
        heldGravity.map { it.identity } shouldBe thrownGravity.map { it.identity }
        assertFiniteParts(heldGravity, 8)
        assertFiniteParts(thrownGravity, 8)

        val spear = staffLancePinParts(3.5, .8f, held = true)
        assertFiniteParts(spear, 7)
        val spearIds = spear.map { it.identity }
        staffLancePinParts(3.5, .8f, held = false).map { it.identity } shouldBe spearIds

        val points = (0 until STAFF_EMBER_TRAIL_MAX_POINTS).map { index -> Vector3f(index * 1.25f, 63f, index * .4f) }
        val emberFrames = listOf(
            staffEmberTrailParts(emptyList(), Vector3f(0f, 63f, 0f), -1),
            staffEmberTrailParts(points.take(4), Vector3f(0f, 63f, 0f), -1),
            staffEmberTrailParts(points, Vector3f(0f, 63f, 0f), 7),
        )
        val emberIds = emberFrames.first().map { it.identity }
        emberFrames.forEach { frame ->
            frame.size shouldBe 24
            frame.map { it.identity } shouldBe emberIds
            assertFiniteParts(frame, 24)
        }
        emberFrames.last().filter { it.visible }.all { it.center.x in -0.01f..15.01f } shouldBe true
    }

    "EMBER ash segments connect captured ground points at their actual elevations" {
        val origin = Vector3f(0f, 63f, 0f)
        val points = listOf(Vector3f(0f, 63f, 0f), Vector3f(0f, 64f, 1f), Vector3f(0f, 65f, 2f))
        val parts = staffEmberTrailParts(points, origin, -1)
        val slope = parts.single { it.identity == "ember:q:ground:2" }
        (slope.center.y > 1.5f) shouldBe true
        (slope.scale.z > 1.4f) shouldBe true
        val along = slope.rotation.transform(Vector3f(0f, 0f, 1f))
        (along.y > .70f && along.z > .70f) shouldBe true
        (parts.single { it.identity == "ember:q:flame:2" }.center.y > 2f) shouldBe true
        parts.map { it.identity } shouldBe staffEmberTrailParts(points, origin, 2).map { it.identity }
    }
})

private fun assertFiniteParts(parts: List<StaffDisplayPart>, maximum: Int) {
    (parts.size <= maximum) shouldBe true
    parts.mapNotNull { it.identity }.distinct().size shouldBe parts.size
    parts.all { part ->
        listOf(part.center.x, part.center.y, part.center.z,
            part.scale.x, part.scale.y, part.scale.z,
            part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
            part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
    } shouldBe true
}

private fun wrapAngleForTest(value: Float): Float {
    var angle = value
    val pi = Math.PI.toFloat()
    while (angle > pi) angle -= (2.0 * Math.PI).toFloat()
    while (angle < -pi) angle += (2.0 * Math.PI).toFloat()
    return angle
}
