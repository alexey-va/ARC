package ru.arc.staffspells

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.joml.Vector3f
import kotlin.math.abs

class StaffTechniqueGeometryTest : FreeSpec({
    "lunar boomerang keeps its solid crescent and separate wake while turning and returning" {
        val frames = (0..STAFF_LUNAR_TOTAL_TICKS).map { age ->
            staffLunarBoomerangParts(if (age < 11) 0f else Math.PI.toFloat(), age)
        }
        val identities = frames.first().map { it.identity }
        frames.forEach { parts ->
            parts.size shouldBe 24
            parts.map { it.identity } shouldBe identities
            parts.mapNotNull { it.identity }.distinct().size shouldBe 24
            parts.all { part ->
                listOf(part.center.x, part.center.y, part.center.z,
                    part.scale.x, part.scale.y, part.scale.z,
                    part.rotation.x, part.rotation.y, part.rotation.z, part.rotation.w).all(Float::isFinite) &&
                    part.scale.x > 0f && part.scale.y > 0f && part.scale.z > 0f
            } shouldBe true
        }
        val initial = frames.first()
        val corners = initial.filter { !it.identity!!.contains(":wake:") }.flatMap { part ->
            listOf(-0.5f, 0.5f).flatMap { x -> listOf(-0.5f, 0.5f).flatMap { y ->
                listOf(-0.5f, 0.5f).map { z ->
                    part.rotation.transform(Vector3f(x * part.scale.x, y * part.scale.y, z * part.scale.z))
                        .add(part.center)
                }
            } }
        }
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        ((maxX - minX) in 2.0f..2.5f) shouldBe true
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

        val burst = staffIcicleRadialParts(4)
        burst.size shouldBe 36
        assertFiniteParts(burst, 36)
        staffIcicleRadialParts(8).map { it.identity } shouldBe burst.map { it.identity }
        burst.all { kotlin.math.hypot(it.center.x.toDouble(), it.center.z.toDouble()) + it.scale.z / 2 <= 4.51 } shouldBe true
        burst.all { it.center.y + it.scale.length() * .5 < 1.5f } shouldBe true
        staffIcicleRadialParts(STAFF_ICICLE_RADIAL_TICKS) shouldBe emptyList()

        val prism = staffPrismTripwireParts(.35f, 3)
        assertFiniteParts(prism, 24)
        val origin = Location(null, 10.0, 64.0, -4.0)
        staffPrismTripwireContains(origin.clone().add(0.0, .9, 0.0), origin, 0f) shouldBe true
        staffPrismTripwireContains(origin.clone().add(4.0, .9, 0.0), origin, 0f) shouldBe false
        staffPrismTripwireParts(0f, 0).map { it.identity } shouldBe prism.map { it.identity }

        val heldGravity = staffGravityPinParts(4, thrown = false, yaw = .2f)
        val thrownGravity = staffGravityPinParts(5, thrown = true, yaw = .8f)
        heldGravity.map { it.identity } shouldBe staffGravityPinParts(18, false, .2f).map { it.identity }
        thrownGravity.map { it.identity } shouldBe staffGravityPinParts(9, true, .8f).map { it.identity }
        assertFiniteParts(heldGravity, 24)
        assertFiniteParts(thrownGravity, 12)

        val spear = staffLancePinParts(3.5, .8f, held = true)
        assertFiniteParts(spear, 19)
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
            frame.size shouldBe 36
            frame.map { it.identity } shouldBe emberIds
            assertFiniteParts(frame, 36)
        }
        emberFrames.last().filter { it.visible }.all { it.center.x in -0.3f..15.01f } shouldBe true
    }

    "Q silhouettes expose their targets and their climaxes differ from their preparation" {
        val lunarWake = staffLunarBoomerangParts(0f, 10).filter { it.identity!!.contains(":wake:") }
        lunarWake.size shouldBe 12
        lunarWake.all { it.material.name.endsWith("STAINED_GLASS") && it.center.z < -.8f } shouldBe true
        val prismBefore = staffPrismTripwireParts(0f, 5).filter { it.identity!!.contains(":cut:") }
        val prismCut = staffPrismTripwireParts(0f, 5, 4).filter { it.identity!!.contains(":cut:") }
        prismBefore.zip(prismCut).all { (a, b) ->
            Vector3f(b.center.x, 0f, b.center.z).length() < Vector3f(a.center.x, 0f, a.center.z).length() * .65f &&
                b.scale.z >= 2f && b.scale.z > a.scale.z * .55f && b.material != a.material
        } shouldBe true
        (prismCut.maxOf { it.center.y } > 2.4f) shouldBe true
        prismCut.all { it.material.name.endsWith("STAINED_GLASS") } shouldBe true
        val idlePylons = staffPrismTripwireParts(0f, 5).filter { !it.identity!!.contains(":cut:") }
        val activePylons = staffPrismTripwireParts(0f, 5, 4).filter { !it.identity!!.contains(":cut:") }
        activePylons.map { it.center } shouldBe idlePylons.map { it.center }
        activePylons.map { it.scale } shouldBe idlePylons.map { it.scale }
        val held = staffGravityPinParts(10, false, 0f)
        // There is no solid block in the middle of the held victim's torso.
        held.all { kotlin.math.abs(it.center.x) > .6f || kotlin.math.abs(it.center.y) > 1.2f } shouldBe true
        val spear = staffLancePinParts(2.5, 0f, true, 5)
        (spear.maxOf { it.center.x } - spear.minOf { it.center.x } > 2.5f) shouldBe true
        val order = staffNecromancerCommandParts(12)
        assertFiniteParts(order, 27)
        (order.maxOf { it.center.y + it.scale.y / 2 } > 3.15f) shouldBe true
        order.map { it.identity } shouldBe staffNecromancerCommandParts(28).map { it.identity }
        staffNecromancerCommandParts(32) shouldBe emptyList()
        val points = listOf(Vector3f(0f, 63f, 0f), Vector3f(0f, 63f, 1.4f))
        val cold = staffEmberTrailParts(points, Vector3f(0f, 63f, 0f), -1, 4)
        val burning = staffEmberTrailParts(points, Vector3f(0f, 63f, 0f), 1, 4)
        (burning.filter { it.visible }.maxOf { it.center.y + it.scale.y / 2 } > 1.3f) shouldBe true
        (cold.filter { it.visible }.maxOf { it.center.y + it.scale.y / 2 } < .3f) shouldBe true
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

    "CHAIN area fracture covers its radius in volume and keeps one shrinking core through the seam" {
        val orbCore = staffChainTechniqueParts(0f, 4).single { it.identity == "chain:q:core" }
        val frames = (0 until STAFF_CHAIN_BURST_TICKS).map(::staffChainBurstParts)
        frames.forEach { frame ->
            assertFiniteParts(frame, 25)
            frame.map { it.identity } shouldBe frames.first().map { it.identity }
            frame.single { it.identity == orbCore.identity }.center shouldBe orbCore.center
        }
        val first = frames.first()
        val endpoints = first.filter { it.identity?.endsWith(":outer") == true }.map { part ->
            part.rotation.transform(Vector3f(0f, 0f, part.scale.z * .5f)).add(part.center)
        }
        endpoints.all { abs(it.length() - STAFF_CHAIN_DETONATION_RADIUS.toFloat()) < .01f } shouldBe true
        (endpoints.maxOf { it.y } > 3.5f && endpoints.minOf { it.y } < -3.5f) shouldBe true
        (frames.last().first().scale.x < first.first().scale.x * .1f) shouldBe true
        staffChainBurstParts(STAFF_CHAIN_BURST_TICKS) shouldBe emptyList()
    }

    "GALE ultimate retains its hollow eye and broad footprint through buildup circulation and dissipation" {
        val frames = (0 until STAFF_GALE_HURRICANE_TICKS).map(::staffGaleHurricaneParts)
        val identities = frames.first().map { it.identity }
        frames.forEach { parts ->
            parts.size shouldBe 48
            assertFiniteParts(parts, 48)
            parts.map { it.identity } shouldBe identities
            parts.forEach { part ->
                val relativeEye = org.joml.Quaternionf(part.rotation).conjugate()
                    .transform(Vector3f(0f, 1.6f, 0f).sub(part.center))
                val nearest = Vector3f(relativeEye.x.coerceIn(-part.scale.x * .5f, part.scale.x * .5f),
                    relativeEye.y.coerceIn(-part.scale.y * .5f, part.scale.y * .5f),
                    relativeEye.z.coerceIn(-part.scale.z * .5f, part.scale.z * .5f))
                (relativeEye.distance(nearest) > 1.25f) shouldBe true
            }
        }
        val active = frames[STAFF_GALE_HURRICANE_BUILD_TICKS]
        (active.maxOf { it.center.y } > 12.0f) shouldBe true
        repeat(3) { strand ->
            val band = (0 until 13).map { index ->
                active.single { it.identity == "gale:q:hurricane:band:$strand:$index" }
            }
            band.zipWithNext().forEach { (a, b) ->
                val end = a.rotation.transform(Vector3f(0f, 0f, a.scale.z / (2f * 1.16f))).add(a.center)
                val start = b.rotation.transform(Vector3f(0f, 0f, -b.scale.z / (2f * 1.16f))).add(b.center)
                (end.distance(start) < .01f) shouldBe true
            }
        }
        active.filter { it.identity?.contains(":ground:") == true }.forEach { part ->
            (kotlin.math.hypot(part.center.x.toDouble(), part.center.z.toDouble()) in 6.2..6.9) shouldBe true
            (part.center.y < .6f) shouldBe true
        }
        (frames[1].maxOf { it.center.y } < 1f) shouldBe true
        (frames.last().sumOf { it.scale.length().toDouble() } < active.sumOf { it.scale.length().toDouble() } * .1) shouldBe true
        staffGaleHurricaneParts(STAFF_GALE_HURRICANE_TICKS) shouldBe emptyList()
    }

    "GALE leaves target silhouettes readable through standing and crouching eye views around the full circle" {
        // Count even translucent glass as an obstruction: a clear cavity alone does not protect aim.
        for (age in 0 until STAFF_GALE_HURRICANE_TICKS step 4) {
            val parts = staffGaleHurricaneParts(age)
            for (eyeHeight in listOf(1.27f, 1.62f)) {
                val eye = Vector3f(0f, eyeHeight, 0f)
                val boxes = parts.map { TechniqueSightBox(it, eye) }
                for (distance in listOf(6f, 10f)) for (direction in 0 until 12) {
                    val angle = direction * Math.PI / 6
                    val forward = Vector3f(kotlin.math.sin(angle).toFloat(), 0f, kotlin.math.cos(angle).toFloat())
                    val right = Vector3f(forward.z, 0f, -forward.x)
                    val rays = (0 until 7).flatMap { column -> (0 until 12).map { row ->
                        Vector3f(forward).mul(distance).fma(-.25f + column / 12f, right)
                            .add(0f, .65f + row * (1.15f / 11), 0f).sub(eye)
                    } }
                    val obscured = rays.count { ray -> boxes.any { it.intersects(ray) } }
                    (obscured.toDouble() / rays.size <= .35) shouldBe true
                }
            }
        }
        val crown = staffGaleHurricaneParts(40).filter { it.identity?.contains(":band:") == true && it.center.y > 6f }
        (crown.size >= 18 && crown.all { it.scale.x > 1.5f }) shouldBe true
    }
})

/** Segment/OBB intersection with the eye transformed once per box and camera height. */
private class TechniqueSightBox(part: StaffDisplayPart, eye: Vector3f) {
    private val inverse = org.joml.Quaternionf(part.rotation).conjugate()
    private val start = inverse.transform(Vector3f(eye).sub(part.center))
    private val half = Vector3f(part.scale).mul(.5f)

    fun intersects(ray: Vector3f): Boolean {
        val delta = inverse.transform(Vector3f(ray))
        var enter = 0f
        var exit = 1f
        for (axis in 0..2) {
            val origin = start.get(axis)
            val direction = delta.get(axis)
            val extent = half.get(axis)
            if (abs(direction) < 1e-7f) {
                if (abs(origin) > extent) return false
            } else {
                val a = (-extent - origin) / direction
                val b = (extent - origin) / direction
                enter = maxOf(enter, minOf(a, b))
                exit = minOf(exit, maxOf(a, b))
                if (enter > exit) return false
            }
        }
        return true
    }
}

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
