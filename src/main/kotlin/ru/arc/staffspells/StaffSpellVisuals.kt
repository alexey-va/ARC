package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Sparse accents around the display silhouettes; all delayed phases run on Paper's main thread. */
internal class StaffSpellVisuals(private val tasks: LifecycleTaskScope) {
    fun lightning(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val points = boltPoints(start, end, distance)
        points.forEachIndexed { i, point ->
            emit(point, Particle.ELECTRIC_SPARK)
            if (i % 4 == 0) emit(point.clone().add(0.0, 0.035, 0.0), Particle.END_ROD)
        }
        val frame = frame(direction.clone().multiply(-1.0)) ?: return@dispatch
        listOf(0.31, 0.68).forEachIndexed { branch, fraction ->
            val anchor = points[(fraction * (points.lastIndex)).toInt()]
            val sign = if (branch == 1) -1.0 else 1.0
            for (step in 1..2) {
                val t = step / 2.0
                emit(anchor.clone()
                    .add(frame.first.clone().multiply(sign * t * 0.52))
                    .add(frame.second.clone().multiply(t * (if (branch == 1) -0.22 else 0.18))),
                    Particle.ELECTRIC_SPARK)
            }
        }
        localSound(start.clone().add(direction.normalize().multiply(min(0.8, distance * 0.6))),
            Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.45f, 1.7f)
        localSound(end, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.95f, 1.15f)
        // A compact charged crown makes the contact point read brighter than the travel line.
        emit(end.clone().add(0.0, 0.12, 0.0), Particle.FLASH)
        val impactFrame = frame(direction) ?: return@dispatch
        repeat(6) { i ->
            val angle = i * PI / 3.0
            emit(end.clone()
                .add(impactFrame.first.clone().multiply(cos(angle) * 0.22))
                .add(impactFrame.second.clone().multiply(sin(angle) * 0.22)),
                if (i % 2 == 0) Particle.END_ROD else Particle.ELECTRIC_SPARK)
        }
        tasks.runLater(1) {
            lineSamples(start, end, (distance * 0.55).toInt().coerceIn(6, 16)).forEachIndexed { i, point ->
                if (i % 3 == 0) emit(point, Particle.ELECTRIC_SPARK)
            }
            repeat(3) { i ->
                val side = if (i == 1) -1.0 else 1.0
                emit(end.clone().add(frame.first.clone().multiply(side * (0.12 + i * 0.08))), Particle.ELECTRIC_SPARK)
            }
            localSound(end, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.68f, 1.35f)
        }
    }

    /** Stepwise homing-bolt trail: at most five small sparks and no head flash or repeated sound. */
    fun lightningTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val basis = frame(direction) ?: return@dispatch
        // Controller steps are at most two blocks; show only the route segment just traveled.
        val segments = 2
        val samples = lineSamples(start, end, segments)
        samples.forEach { point ->
            emit(point, Particle.ELECTRIC_SPARK, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        repeat(2) { i ->
            val side = if (i == 0) -1.0 else 1.0
            emit(interpolate(start, end, 0.75).add(basis.first.clone().multiply(side * 0.09)),
                Particle.ELECTRIC_SPARK, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    fun markLaunch(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        if (segmentLength(start, end) == null) return@dispatch
        val offset = end.toVector().subtract(start.toVector())
        val basis = frame(offset) ?: return@dispatch
        localSound(start.clone().add(offset.normalize().multiply(0.75)), Sound.ENTITY_ENDERMAN_TELEPORT, 0.68f, 1.15f)
        for (phase in 0..3) {
            val draw = { markTearStage(start, end, basis.first, basis.second, phase) }
            if (phase == 0) draw() else tasks.runLater(phase.toLong()) { draw() }
        }
    }

    /** Called every two ticks: a rotating violet vortex with one restrained cast cue. */
    fun markCharge(at: Location, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val reach = 0.9 * (1.0 - charge) + 0.12
        repeat(3) { i ->
            val angle = i * PI * 2 / 3 - PI / 2 + charge * PI * 2.0
            val point = origin.clone().add(cos(angle) * reach, 0.14 + (i % 2) * 0.08, sin(angle) * reach)
            emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(point.clone().add(0.0, 0.12, 0.0), Particle.WITCH, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        if (charge > 0.35) emit(origin.clone().add(0.0, 0.18, 0.0), Particle.DUST, VIOLET,
            minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    fun markBurst(at: Location, radius: Double, blackhole: Boolean = false) = dispatch {
        val origin = at.clone().add(0.0, if (blackhole) BLACKHOLE_CORE_HEIGHT else 0.0, 0.0)
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.82f, 0.82f)
        emit(origin.clone().add(0.0, 0.82, 0.0), Particle.FLASH)
        emit(origin.clone().add(0.0, 0.42, 0.0),
            if (blackhole) Particle.REVERSE_PORTAL else Particle.END_ROD)
        val burstRadius = min(reach * 0.58, 2.6)
        repeat(8) { i ->
            val angle = i * PI / 4.0
            val point = origin.clone().add(cos(angle) * burstRadius,
                0.24 + (i % 3) * 0.18, sin(angle) * burstRadius)
            if (blackhole && i % 2 == 0) emit(point, Particle.REVERSE_PORTAL)
            else emit(point, Particle.DUST, VIOLET_BURST)
        }
        // One restrained outward follow-through; no repeated wisps or long particle tail.
        tasks.runLater(2) {
            val outerRadius = min(reach * 0.88, 3.8)
            repeat(6) { i ->
                val angle = i * PI / 3.0 + PI / 6.0
                val point = origin.clone().add(cos(angle) * outerRadius,
                    0.18 + (i % 2) * 0.22, sin(angle) * outerRadius)
                emit(point, if (blackhole) Particle.REVERSE_PORTAL else Particle.DUST,
                    if (blackhole) null else VIOLET_BURST)
            }
        }
    }

    /** A short floor-and-crest front, or a broad radial ring, over ticks 2 through 18. */
    fun wave(origin: Location, end: Location, radius: Double, frost: Boolean, directional: Boolean) = dispatch {
        val start = origin.clone()
        val endpoint = end.clone()
        if (!drawable(start) || endpoint.world !== start.world || !valid(endpoint)) return@dispatch

        if (directional && !frost) {
            val offset = endpoint.toVector().subtract(start.toVector()).apply { y = 0.0 }
            val length = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
            val width = boundedRadius(radius, 0.5, 5.5) ?: return@dispatch
            val basis = frame(offset) ?: return@dispatch
            val forward = offset.normalize()
            val reach = min(length, 12.0)
            repeat(10) { phase ->
                val age = 2 + phase * 2
                tasks.runLater(age.toLong()) {
                    if (phase == 0) localSound(start.clone().apply { y = start.blockY + 0.08 },
                        Sound.BLOCK_BEACON_ACTIVATE, 0.74f, 1.42f)
                    novaWaveFrame(start, forward, basis.first, reach, width, age)
                }
            }
        } else if (directional) {
            val offset = endpoint.toVector().subtract(start.toVector()).apply { y = 0.0 }
            val length = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
            val width = boundedRadius(radius, 0.5, 5.5) ?: return@dispatch
            val basis = frame(offset) ?: return@dispatch
            val forward = offset.normalize()
            val reach = min(length, 12.0)
            repeat(9) { phase ->
                tasks.runLater((2 + phase * 2).toLong()) {
                    val front = start.clone().add(forward.clone().multiply(1.8 + (reach - 1.8) * phase / 8.0))
                    val floorY = start.blockY + 0.06
                    repeat(7) { i ->
                        val side = (i - 3) / 3.0 * width * (1.8 + (reach - 1.8) * phase / 8.0) / reach
                        val floor = front.clone().add(basis.first.clone().multiply(side)).apply { y = floorY }
                        val crestHeight = if (frost) 0.34 else 1.0 + sin(PI * phase / 8.0) * 1.1
                        val crest = floor.clone().add(0.0, crestHeight + (i % 2) * 0.08, 0.0)
                        if (frost) {
                            emit(floor, Particle.SNOWFLAKE)
                            emit(crest, Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
                        } else {
                            emit(floor, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                            emit(crest, Particle.CLOUD)
                            if (i % 3 == 0) emit(crest.clone().add(0.0, 0.22, 0.0), Particle.END_ROD)
                        }
                    }
                }
            }
        } else {
            val reach = boundedRadius(radius, 0.5, 8.0) ?: return@dispatch
            repeat(9) { phase ->
                tasks.runLater((2 + phase * 2).toLong()) {
                    val ringRadius = 1.8 + (reach - 1.8) * phase / 8.0
                    val floorY = start.blockY + 0.06
                    repeat(12) { i ->
                        val angle = i * PI * 2 / 12 + phase * 0.09
                        val point = start.clone().add(cos(angle) * ringRadius, 0.0, sin(angle) * ringRadius)
                            .apply { y = floorY }
                        if (frost) emit(point, Particle.SNOWFLAKE)
                        else emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                        if (i % 2 == 0) {
                            val crest = point.clone().add(0.0, 0.34, 0.0)
                            if (frost) emit(crest, Particle.END_ROD)
                            else emit(crest, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                        }
                    }
                }
            }
        }
    }

    /** Three inward-spiraling arms and a bright core; one low cast cue, no repeated sound. */
    fun gravity(at: Location, radius: Double, progress: Double) = dispatch {
        val origin = at.clone().add(0.0, BLACKHOLE_CORE_HEIGHT, 0.0)
        if (!drawable(origin)) return@dispatch
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (charge <= 0.100001) localSound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.2f, 0.55f)
        val orbit = 0.16 + (reach - 0.16) * (1.0 - charge)
        // Three dense, continuously rotating arms collapse inward across the two-second charge.
        repeat(3) { arm ->
            repeat(9) { step ->
                val progressAlongArm = step / 8.0
                val angle = arm * PI * 2.0 / 3.0 + charge * PI * 5.0 + progressAlongArm * PI * 2.2
                val radiusAt = 0.18 + (orbit - 0.18) * (1.0 - progressAlongArm)
                val height = sin(progressAlongArm * PI * 2.0 + arm * 0.72) * 0.68 + progressAlongArm * 0.10
                val point = origin.clone().add(cos(angle) * radiusAt, height, sin(angle) * radiusAt)
                if ((arm + step) % 2 == 0) emit(point, Particle.DUST_COLOR_TRANSITION, ACCRETION_DUST,
                    minimumEyeDistance = MARK_CAMERA_CLEARANCE)
                else emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            }
        }
        repeat(8) { i ->
            val angle = i * PI / 4.0 - charge * PI * 2.0
            val coreRadius = 0.12 + (i % 3) * 0.06
            val point = origin.clone().add(cos(angle) * coreRadius, -0.28 + (i % 4) * 0.18,
                sin(angle) * coreRadius)
            if (i % 2 == 0) emit(point, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            else emit(point, Particle.DUST_COLOR_TRANSITION, ACCRETION_DUST,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        emit(origin, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    fun frost(originFeet: Location, horizontalEnd: Location, halfAngleDegrees: Double,
        startDelayTicks: Int = 2, travelTicks: Int = 12) = dispatch {
        val origin = originFeet.clone()
        val endpoint = horizontalEnd.clone()
        if (origin.world == null || endpoint.world !== origin.world || !drawable(origin) || !valid(endpoint)) return@dispatch
        val offset = endpoint.toVector().subtract(origin.toVector()).apply { y = 0.0 }
        val range = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
        val spread = halfAngleDegrees.takeIf(Double::isFinite)?.coerceIn(5.0, 85.0) ?: return@dispatch
        val basis = frame(offset) ?: return@dispatch
        val reach = range.coerceAtMost(14.0)
        val delay = startDelayTicks.coerceIn(0, 8)
        val travel = travelTicks.coerceIn(1, 24)
        val steps = ((travel + 1) / 2).coerceIn(1, 12)
        repeat(steps + 1) { phase ->
            val atTick = delay + phase * travel / steps
            tasks.runLater(atTick.toLong()) {
                if (phase == 0) localSound(origin.clone().apply { y = origin.blockY + 0.08 }, Sound.BLOCK_GLASS_BREAK, 0.76f, 0.86f)
                frostFront(origin, offset.clone().normalize(), basis.first, reach, spread, phase, steps)
            }
        }
    }

    fun lance(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val offset = end.toVector().subtract(start.toVector())
        val orientation = staffLanceOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        fun axis(x: Float, y: Float, z: Float): Vector {
            val rotated = orientation.transform(Vector3f(x, y, z))
            return Vector(rotated.x.toDouble(), rotated.y.toDouble(), rotated.z.toDouble())
        }
        val forward = axis(0f, 0f, 1f)
        val right = axis(-1f, 0f, 0f)
        val up = axis(0f, 1f, 0f)
        val reach = distance.coerceIn(0.1, 48.0)
        val launchFront = min(2.0, reach)
        val charge = start.clone().add(forward.clone().multiply(launchFront))
            .add(up.clone().multiply(staffLanceCenterOffset(launchFront, distance)))
        localSound(charge, Sound.ENTITY_ARROW_SHOOT, 0.78f, 0.92f)
        // The default impact-camera cutoff keeps this cue out of the caster's near eye view.
        emit(charge.clone().add(0.0, 0.08, 0.0), Particle.FLASH)
        repeat(3) { i ->
            val side = (i - 1) * 0.075
            emit(charge.clone().add(right.clone().multiply(side)), Particle.DUST, GOLD,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        val flightTicks = staffLanceFlightTicks(distance)
        for (age in 1..flightTicks) {
            tasks.runLater(age.toLong()) {
                lanceStage(start, forward, right, up, distance, age, flightTicks)
            }
        }
        tasks.runLater((flightTicks + 3).toLong()) {
            lanceAfterglow(start, forward, right, up, distance, 4)
        }
        tasks.runLater((flightTicks + 6).toLong()) {
            lanceAfterglow(start, forward, right, up, distance, 2)
        }
    }

    /** Per-tick trail: at most three particles, no smoke or repeated sound. */
    fun emberTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        if (start.world == null || end.world !== start.world || !valid(start) || !valid(end)) return@dispatch
        if (start.toVector().distanceSquared(end.toVector()) < 0.0001) {
            // Cast cue only: keep the moving orb's trail off the player's face.
            localSound(end, Sound.ENTITY_BLAZE_SHOOT, 0.72f, 0.95f)
            return@dispatch
        }
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector()).normalize()
        val trailEnd = end.clone().subtract(direction.clone().multiply(min(0.24, distance * 0.75)))
        val samples = if (distance > 0.55) 2 else 1
        lineSamples(start, trailEnd, samples).forEach { point ->
            emit(point, Particle.FLAME, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    fun emberBurst(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.72f, 1.05f)
        // Fixed phases cap the burst at 132 particle positions per impact.
        emit(origin.clone().add(0.0, 0.6, 0.0), Particle.EXPLOSION)
        repeat(8) { i ->
            val angle = i * PI * 2 / 8
            val point = origin.clone().add(cos(angle) * reach * 0.12, 0.08 + (i % 2) * 0.12, sin(angle) * reach * 0.12)
            emit(point, Particle.FLAME)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.12, 0.0), Particle.END_ROD)
        }
        emit(origin.clone().add(0.0, 0.12, 0.0), Particle.FLAME)
        emit(origin.clone().add(0.0, 0.3, 0.0), Particle.END_ROD)
        tasks.runLater(2) {
            emit(origin.clone().add(0.0, 0.9, 0.0), Particle.EXPLOSION)
            repeat(20) { i ->
                val angle = i * PI * 2 / 20
                val ring = reach * (0.25 + (i % 3) * 0.035)
                emit(origin.clone().add(cos(angle) * ring, 0.06 + (i % 4) * 0.08, sin(angle) * ring), Particle.FLAME)
            }
        }
        tasks.runLater(4) {
            repeat(32) { i ->
                val angle = i * PI * 2 / 32
                emit(origin.clone().add(cos(angle) * reach * 0.88, 0.07, sin(angle) * reach * 0.88), Particle.DUST, GOLD)
            }
        }
        tasks.runLater(7) {
            repeat(24) { i ->
                val angle = i * PI * 2 / 24
                val ring = reach * (0.3 + (i % 4) * 0.15)
                emit(origin.clone().add(cos(angle) * ring, 0.1 + (i % 4) * 0.15, sin(angle) * ring), Particle.FLAME)
            }
            repeat(8) { i ->
                val angle = i * PI * 2 / 8
                emit(origin.clone().add(cos(angle) * reach * 0.46, 0.48 + (i % 3) * 0.12, sin(angle) * reach * 0.46), Particle.SMOKE)
            }
        }
        tasks.runLater(11) {
            repeat(32) { i ->
                val angle = i * PI * 2 / 32 + 0.08
                val ring = reach * (0.45 + (i % 5) * 0.1)
                val point = origin.clone().add(cos(angle) * ring, 0.24 + (i % 7) * 0.16, sin(angle) * ring)
                if (i % 4 == 0) emit(point, Particle.DUST, GOLD_TAIL) else emit(point, Particle.FLAME)
            }
        }
    }

    fun nova(at: Location, radius: Double, secondary: Boolean = false) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 2.0, 10.0) ?: return@dispatch
        repeat(10) { phase ->
            val age = 2 + phase * 2
            tasks.runLater(age.toLong()) {
                if (phase == 0) localSound(origin, Sound.BLOCK_BEACON_ACTIVATE, 0.74f, 1.42f)
                novaFrame(origin, reach, age, secondary)
            }
        }
    }

    private fun markTearStage(from: Location, to: Location, right: Vector, up: Vector, phase: Int) {
        val progress = (phase + 1) / 4.0
        val tip = interpolate(from, to, progress)
        val slash = 0.08 + phase * 0.015
        repeat(3) { i ->
            val side = i - 1
            emit(tip.clone()
                .add(right.clone().multiply(side * slash))
                .add(up.clone().multiply(-side * slash * 0.7)), Particle.DUST, VIOLET)
        }
        if (phase % 2 == 1) emit(tip, Particle.REVERSE_PORTAL)
    }

    private fun frostFront(origin: Location, forward: Vector, right: Vector, range: Double,
        halfAngle: Double, phase: Int, steps: Int) {
        val distance = range * phase / steps
        val floorY = origin.blockY + 0.06
        if (phase == 0) {
            val start = origin.clone().apply { y = floorY }
            emit(start, Particle.SNOWFLAKE)
            emit(start.clone().add(0.0, 0.03, 0.0), Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
            return
        }
        val halfAngleRadians = Math.toRadians(halfAngle)
        val ice = Material.PACKED_ICE.createBlockData()
        for (i in 0..4) {
            val angle = (i / 2.0 - 1.0) * halfAngleRadians
            val radial = forward.clone().multiply(cos(angle)).add(right.clone().multiply(sin(angle)))
            val point = origin.clone().add(radial.multiply(distance)).apply { y = floorY }
            emit(point, Particle.SNOWFLAKE)
            if (i == 2) {
                emit(point.clone().add(0.0, 0.035, 0.0), Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
                repeat(2) { side ->
                    val sign = if (side == 0) -1.0 else 1.0
                    emit(point.clone().add(right.clone().multiply(sign * 0.2)).add(0.0, 0.12, 0.0), Particle.END_ROD)
                }
            }
            if (i == 1 || i == 3) emit(point.clone().add(0.0, 0.045, 0.0), Particle.BLOCK, ice)
            if (i == 0 || i == 4) emit(point.clone().add(0.0, 0.1, 0.0), Particle.END_ROD)
        }
        if (phase > 1 && phase < steps && phase % 2 == 0) {
            val frost = origin.clone().add(forward.clone().multiply(distance * 0.72)).apply { y = floorY }
            repeat(2) { i ->
                val side = if (i == 0) -1.0 else 1.0
                emit(frost.clone().add(right.clone().multiply(side * min(distance * 0.12, 0.55))), Particle.SNOWFLAKE)
            }
        }
    }

    private fun lanceStage(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, age: Int, flightTicks: Int) {
        val reach = length.coerceIn(0.1, 48.0)
        val front = staffLanceFront(age, length)
        val launchFront = min(2.0, reach)
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        // Four sparks span the same expanding helix as the display, not only its last few blocks.
        repeat(4) { index ->
            val distance = launchFront + (front - launchFront) * index / 3.0
            val offset = staffLanceOrbitOffset(distance, length)
            val point = start.clone()
                .add(forward.clone().multiply(distance))
                .add(right.clone().multiply(-offset.x.toDouble()))
                .add(up.clone().multiply(staffLanceCenterOffset(distance, length) + offset.y.toDouble()))
            emit(point, Particle.DUST, GOLD_TAIL, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            if (index == 1 || index == 3) {
                emit(point.clone().add(right.clone().multiply(if (index == 1) -0.11 else 0.11))
                    .add(up.clone().multiply(0.08)), Particle.FLAME,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
        emit(tip, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (age == flightTicks) {
            emit(tip.clone().add(up.clone().multiply(0.10)), Particle.FLASH)
            repeat(6) { index ->
                val angle = index * PI / 3.0
                emit(tip.clone()
                    .add(right.clone().multiply(cos(angle) * 0.28))
                    .add(up.clone().multiply(sin(angle) * 0.22)),
                    if (index % 2 == 0) Particle.END_ROD else Particle.FLAME,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
    }

    private fun lanceAfterglow(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, count: Int) {
        val front = staffLanceFront(staffLanceFlightTicks(length), length)
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        repeat(count) { index ->
            val angle = index * PI * 2.0 / count
            emit(tip.clone()
                .add(right.clone().multiply(cos(angle) * 0.18))
                .add(up.clone().multiply(sin(angle) * 0.14)),
                if (index % 2 == 0) Particle.END_ROD else Particle.ELECTRIC_SPARK,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    private fun novaFrame(at: Location, radius: Double, age: Int, secondary: Boolean) {
        val front = 1.8 + staffWaveFront(age, radius - 1.8, 16)
        val travel = ((age - 2) / 16.0).coerceIn(0.0, 1.0)
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            emit(at.clone().add(front, 0.52, 0.0).apply { y = floorY + 0.52 }, Particle.END_ROD)
            return
        }
        val columns = when {
            age <= 12 -> 8
            age == 14 -> 6
            age == 16 -> 4
            else -> 2
        }
        repeat(columns) { index ->
            val angle = index * PI * 2.0 / columns + travel * 0.08
            val point = at.clone().add(cos(angle) * front, 0.0, sin(angle) * front).apply { y = floorY }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
            val crestAngle = angle + PI / 16.0
            val crestHeight = 0.92 + sin(PI * travel * 0.5) * 0.52 + (index % 3) * 0.05
            val crest = at.clone().add(cos(crestAngle) * (front - 0.12), 0.0,
                sin(crestAngle) * (front - 0.12)).apply { y = floorY + crestHeight }
            emit(crest, Particle.CLOUD)
            if (index % 2 == 0) emit(crest.clone().add(0.0, 0.14, 0.0), Particle.END_ROD)
            if (secondary && index % 3 == 0) emit(crest, Particle.DUST_COLOR_TRANSITION, VIOLET_TO_AQUA)
        }
        val wakeCount = when {
            age <= 12 -> 4
            age == 14 -> 3
            age == 16 -> 2
            else -> 1
        }
        val wake = (front - 0.72).coerceAtLeast(1.25)
        repeat(wakeCount) { index ->
            val angle = index * PI * 2.0 / wakeCount + travel * 0.08
            val point = at.clone().add(cos(angle) * wake, 0.32, sin(angle) * wake)
                .apply { y = floorY + 0.32 }
            if (index % 2 == 0) emit(point, Particle.CLOUD)
            else emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
        }
    }

    private fun novaWaveFrame(at: Location, forward: Vector, right: Vector,
        reach: Double, configuredWidth: Double, age: Int) {
        val front = 1.8 + staffWaveFront(age, reach - 1.8, 16)
        val halfWidth = configuredWidth * front / reach
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            emit(at.clone().add(forward.clone().multiply(reach)).apply { y = floorY + 0.45 }, Particle.END_ROD)
            return
        }
        val columns = when {
            age <= 12 -> 7
            age == 14 -> 5
            age == 16 -> 3
            else -> 1
        }
        val lift = sin(PI * ((age - 2) / 16.0).coerceIn(0.0, 1.0) * 0.5)
        repeat(columns) { index ->
            val across = if (columns == 1) 0.0 else index / (columns - 1.0) * 2.0 - 1.0
            val side = right.clone().multiply(across * halfWidth)
            val floor = at.clone().add(forward.clone().multiply(front)).add(side).apply { y = floorY }
            emit(floor, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
            val crestHeight = 0.94 + lift * (0.52 + (index % 2) * 0.22)
            val crest = floor.clone().add(0.0, crestHeight, 0.0)
            emit(crest, Particle.CLOUD)
            if (index % 3 == 0) emit(crest.clone().add(0.0, 0.22, 0.0), Particle.END_ROD)
        }
        val wakeCount = when {
            age <= 12 -> 3
            age == 14 -> 2
            age == 16 -> 1
            else -> 1
        }
        val wake = at.clone().add(forward.clone().multiply((front - 0.78).coerceAtLeast(1.25)))
        repeat(wakeCount) { index ->
            val side = if (wakeCount == 1) 0.0 else (index / (wakeCount - 1.0) * 2.0 - 1.0) * halfWidth * 0.6
            val point = wake.clone().add(right.clone().multiply(side)).apply { y = floorY + 0.38 + (index % 2) * 0.06 }
            if (index % 2 == 0) emit(point, Particle.CLOUD)
            else emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
        }
    }

    private fun boltPoints(from: Location, to: Location, distance: Double): List<Location> {
        val direction = to.toVector().subtract(from.toVector())
        val frame = frame(direction) ?: return emptyList()
        val steps = (distance * 0.6).toInt().coerceIn(4, 16)
        return (0..steps).map { i ->
            val t = i / steps.toDouble()
            val base = interpolate(from, to, t)
            if (i == 0 || i == steps) base else {
                val taper = sin(PI * t)
                base.add(frame.first.clone().multiply(sin(i * 2.1) * 0.3 * taper))
                    .add(frame.second.clone().multiply(cos(i * 2.7) * 0.22 * taper))
            }
        }
    }

    private fun lineSamples(from: Location, to: Location, segments: Int): List<Location> =
        (0..segments).map { interpolate(from, to, it / segments.toDouble()) }

    private fun interpolate(from: Location, to: Location, t: Double): Location =
        from.clone().add(to.toVector().subtract(from.toVector()).multiply(t))

    private fun frame(direction: Vector): Pair<Vector, Vector>? {
        if (!direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() || direction.lengthSquared() < 1.0e-8) return null
        val forward = direction.clone().normalize()
        var right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        if (right.lengthSquared() < 1.0e-8) right = forward.clone().crossProduct(Vector(1.0, 0.0, 0.0))
        right.normalize()
        return right to right.clone().crossProduct(forward).normalize()
    }

    private fun segmentLength(from: Location, to: Location): Double? {
        val world = from.world ?: return null
        if (to.world !== world || !valid(from) || !valid(to)) return null
        return to.toVector().subtract(from.toVector()).length().takeIf { it.isFinite() && it in 0.01..96.0 }
    }

    private fun boundedRadius(radius: Double, min: Double, max: Double) =
        radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(min, max)

    private fun valid(location: Location) = location.x.isFinite() && location.y.isFinite() && location.z.isFinite()

    private fun drawable(location: Location): Boolean {
        val world = location.world ?: return false
        return valid(location) && world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)
    }

    private fun emit(at: Location, particle: Particle, data: Any? = null,
        minimumEyeDistance: Double = IMPACT_CAMERA_CLEARANCE) {
        if (!drawable(at)) return
        val world = at.world ?: return
        val clearance = minimumEyeDistance.takeIf { it.isFinite() && it >= 0.0 } ?: IMPACT_CAMERA_CLEARANCE
        val minEyeDistanceSquared = clearance * clearance
        val maxViewerDistanceSquared = 48.0 * 48.0
        world.getNearbyPlayers(at, 48.0).forEach { player ->
            val eye = player.eyeLocation
            if (eye.world !== world) return@forEach
            val distanceSquared = eye.distanceSquared(at)
            if (distanceSquared < minEyeDistanceSquared || distanceSquared > maxViewerDistanceSquared) return@forEach
            if (data == null) player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0)
            else player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, data)
        }
    }

    private fun localSound(at: Location, sound: Sound, volume: Float, pitch: Float) {
        if (drawable(at)) at.world?.playSound(at, sound, SoundCategory.PLAYERS, volume, pitch)
    }

    private fun dispatch(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action() else tasks.runSync(action)
    }

    private companion object {
        const val COMPACT_CAMERA_CLEARANCE = 1.15
        const val MARK_CAMERA_CLEARANCE = 1.6
        const val IMPACT_CAMERA_CLEARANCE = 3.2
        const val BLACKHOLE_CORE_HEIGHT = 1.6
        val VIOLET = Particle.DustOptions(Color.fromRGB(166, 74, 255), 1.15f)
        val VIOLET_BURST = Particle.DustOptions(Color.fromRGB(207, 112, 255), 1.5f)
        val VIOLET_TO_AQUA = Particle.DustTransition(Color.fromRGB(205, 90, 255), Color.fromRGB(70, 235, 255), 1.2f)
        val ACCRETION_DUST = Particle.DustTransition(Color.fromRGB(104, 30, 196), Color.fromRGB(238, 132, 255), 2.2f)
        val GOLD = Particle.DustOptions(Color.fromRGB(255, 193, 66), 1.3f)
        val GOLD_TAIL = Particle.DustOptions(Color.fromRGB(255, 222, 145), 0.75f)
        val ICE_TO_AQUA = Particle.DustTransition(Color.fromRGB(180, 230, 255), Color.fromRGB(90, 245, 255), 0.9f)
        val GREEN_TO_AQUA = Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.15f)
    }
}
