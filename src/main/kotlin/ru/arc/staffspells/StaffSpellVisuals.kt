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
import kotlin.math.abs
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

    /** Compact ice trail for a single guided icicle step. */
    fun icicleTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val basis = frame(direction) ?: return@dispatch
        val midpoint = interpolate(start, end, 0.52)
        emit(midpoint, Particle.SNOWFLAKE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        emit(interpolate(start, end, 0.78).add(basis.first.clone().multiply(0.08)),
            Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (distance > 1.0) emit(end, Particle.SNOWFLAKE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
    }

    /** A short, camera-culled ice shard burst when the guided projectile stops. */
    fun icicleBurst(at: Location) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val ice = Material.PACKED_ICE.createBlockData()
        repeat(6) { index ->
            val angle = index * PI / 3.0
            emit(origin.clone().add(cos(angle) * 0.24, 0.12 + (index % 3) * 0.09,
                sin(angle) * 0.24), Particle.BLOCK, ice, COMPACT_CAMERA_CLEARANCE)
        }
        emit(origin.clone().add(0.0, 0.28, 0.0), Particle.END_ROD,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        emit(origin.clone().add(0.0, 0.08, 0.0), Particle.SNOWFLAKE,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
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

    /** Called every two ticks: a shrinking violet-white vortex with one restrained cast cue. */
    fun markCharge(at: Location, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val reach = 0.95 * (1.0 - charge) + 0.12
        repeat(3) { i ->
            val angle = i * PI * 2 / 3 - PI / 2 + charge * PI * 2.0
            val point = origin.clone().add(cos(angle) * reach,
                0.12 + sin(angle * 1.5 + charge * PI) * 0.16, sin(angle) * reach)
            emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(point.clone().add(0.0, 0.08, 0.0), Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        if (charge > 0.35) emit(origin.clone().add(0.0, 0.18, 0.0), Particle.END_ROD,
            minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    fun markBurst(at: Location, radius: Double, blackhole: Boolean = false) = dispatch {
        val origin = at.clone().add(0.0, if (blackhole) BLACKHOLE_CORE_HEIGHT else 0.0, 0.0)
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        if (blackhole) {
            blackholeCollapse(origin, reach)
            return@dispatch
        }
        localSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.92f, 1.08f)
        emit(origin.clone().add(0.0, 0.48, 0.0), Particle.FLASH)
        emit(origin.clone().add(0.0, 0.22, 0.0), Particle.END_ROD)
        val crackRadius = min(reach * 0.72, 3.0)
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0
            val point = origin.clone().add(cos(angle) * crackRadius, 0.18 + (i % 3) * 0.12,
                sin(angle) * crackRadius)
            emit(point, Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE)
            if (i % 3 == 0) emit(point.clone().add(0.0, 0.12, 0.0), Particle.END_ROD)
        }
        // The tear opens over four ticks, then sheds a sparse twenty-tick dust tail.
        tasks.runLater(2) {
            repeat(12) { i ->
                val angle = i * PI * 2.0 / 12.0 + 0.12
                val radiusAt = crackRadius * 0.72
                val point = origin.clone().add(cos(angle) * radiusAt, 0.12 + (i % 4) * 0.16,
                    sin(angle) * radiusAt)
                emit(point, Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE)
                if (i % 2 == 0) emit(point, Particle.REVERSE_PORTAL)
            }
        }
        tasks.runLater(4) {
            repeat(16) { i ->
                val angle = i * PI * 2.0 / 16.0 + 0.18
                val point = origin.clone().add(cos(angle) * crackRadius * 1.08,
                    0.16 + (i % 4) * 0.14, sin(angle) * crackRadius * 1.08)
                emit(point, Particle.DUST, VIOLET_BURST)
                if (i % 2 == 0) emit(point.clone().add(0.0, 0.1, 0.0), Particle.END_ROD)
            }
        }
        listOf(8L to 10, 12L to 8, 16L to 5, 20L to 3).forEach { (delay, count) ->
            tasks.runLater(delay) {
                repeat(count) { i ->
                    val angle = i * PI * 2.0 / count + delay * 0.045
                    val radiusAt = crackRadius * (0.88 + (i % 3) * 0.04)
                    emit(origin.clone().add(cos(angle) * radiusAt, 0.24 + (i % 4) * 0.14,
                        sin(angle) * radiusAt), Particle.DUST, VIOLET)
                }
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
            val packedIce = if (frost) Material.PACKED_ICE.createBlockData() else null
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
                            if (frost && (i / 2 + phase) % 3 == 0) emit(crest, Particle.BLOCK, packedIce)
                            else if (frost) emit(crest, Particle.END_ROD)
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
        // Three dense, continuously rotating arms and a bright rim collapse inward across the charge.
        repeat(3) { arm ->
            repeat(6) { step ->
                val progressAlongArm = step / 5.0
                val angle = arm * PI * 2.0 / 3.0 + charge * PI * 5.0 + progressAlongArm * PI * 2.2
                val radiusAt = 0.18 + (orbit - 0.18) * (1.0 - progressAlongArm)
                val height = sin(progressAlongArm * PI * 2.0 + arm * 0.72) * 0.78 + progressAlongArm * 0.10
                val point = origin.clone().add(cos(angle) * radiusAt, height, sin(angle) * radiusAt)
                if ((arm + step) % 2 == 0) emit(point, Particle.DUST_COLOR_TRANSITION, ACCRETION_DUST,
                    minimumEyeDistance = MARK_CAMERA_CLEARANCE)
                else emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            }
        }
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0 - charge * PI * 3.0
            val rimRadius = orbit * 0.82
            val point = origin.clone().add(cos(angle) * rimRadius, sin(angle * 2.0 + charge * PI) * 0.18,
                sin(angle) * rimRadius)
            emit(point, if (i % 4 == 0) Particle.REVERSE_PORTAL else Particle.DUST_COLOR_TRANSITION,
                if (i % 4 == 0) null else BLACKHOLE_RIM, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        repeat(4) { i ->
            val angle = i * PI / 2.0 - charge * PI * 2.0
            val coreRadius = 0.12 + (i % 3) * 0.06
            val point = origin.clone().add(cos(angle) * coreRadius, -0.28 + (i % 4) * 0.18,
                sin(angle) * coreRadius)
            if (i % 2 == 0) emit(point, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            else emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        emit(origin, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    private fun blackholeCollapse(origin: Location, reach: Double) {
        localSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.96f, 0.72f)
        val rimRadius = min(reach * 0.76, 3.4)
        emit(origin.clone().add(0.0, 0.34, 0.0), Particle.FLASH)
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0
            val point = origin.clone().add(cos(angle) * rimRadius, sin(angle * 2.0) * 0.2,
                sin(angle) * rimRadius)
            emit(point, if (i % 3 == 0) Particle.REVERSE_PORTAL else Particle.DUST_COLOR_TRANSITION,
                if (i % 3 == 0) null else BLACKHOLE_RIM)
        }
        tasks.runLater(2) {
            repeat(10) { i ->
                val angle = i * PI * 2.0 / 10.0 + 0.2
                val point = origin.clone().add(cos(angle) * rimRadius * 0.58,
                    sin(angle * 2.0) * 0.12, sin(angle) * rimRadius * 0.58)
                emit(point, Particle.REVERSE_PORTAL)
                if (i % 2 == 0) emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
        tasks.runLater(4) {
            emit(origin, Particle.FLASH)
            repeat(6) { i ->
                val angle = i * PI / 3.0
                val point = origin.clone().add(cos(angle) * 0.22, (i % 3) * 0.12, sin(angle) * 0.22)
                if (i % 2 == 0) emit(point, Particle.END_ROD)
                else emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
        tasks.runLater(8) {
            repeat(3) { i ->
                val angle = i * PI * 2.0 / 3.0 + 0.35
                emit(origin.clone().add(cos(angle) * 0.16, 0.08 + i * 0.08, sin(angle) * 0.16),
                    Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
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
        localSound(start, Sound.BLOCK_BEACON_ACTIVATE, 0.76f, 1.15f)
        lanceCharge(start, forward, right, up, distance, 0)
        val flightTicks = staffLanceFlightTicks(distance)
        for (age in 1..flightTicks) {
            tasks.runLater(age.toLong()) {
                if (age <= 4) lanceCharge(start, forward, right, up, distance, age)
                else lanceStage(start, forward, right, up, distance, age, flightTicks)
            }
        }
        listOf(3L to 8, 6L to 5, 9L to 3).forEach { (delay, count) ->
            tasks.runLater((flightTicks + delay).toLong()) {
                lanceAfterglow(start, forward, right, up, distance, count)
            }
        }
    }

    /** Per-tick trail: at most three particles, no smoke or repeated sound. */
    fun emberTrail(from: Location, to: Location, meteor: Boolean = false) = dispatch {
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
        lineSamples(start, trailEnd, samples).forEachIndexed { index, point ->
            if (meteor && index == samples / 2)
                emit(point, Particle.DUST, HOT_WHITE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            else emit(point, Particle.FLAME, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    fun emberBurst(at: Location, radius: Double, meteor: Boolean = false) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        val ejecta = Material.TUFF.createBlockData()
        localSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.88f, 1.12f)
        // A white-hot core breaks into a broad fire crown and a measured ember tail (206 positions max).
        emit(origin.clone().add(0.0, 0.58, 0.0), Particle.FLASH)
        emit(origin.clone().add(0.0, 0.45, 0.0), Particle.EXPLOSION)
        repeat(10) { i ->
            val angle = i * PI * 2.0 / 10.0
            val point = origin.clone().add(cos(angle) * reach * 0.13, 0.32 + (i % 3) * 0.12,
                sin(angle) * reach * 0.13)
            emit(point, Particle.DUST, HOT_WHITE)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.16, 0.0), Particle.END_ROD)
        }
        repeat(14) { i ->
            val angle = i * PI * 2.0 / 14.0
            val point = origin.clone().add(cos(angle) * reach * 0.2, 0.08 + (i % 4) * 0.1,
                sin(angle) * reach * 0.2)
            if (meteor) emit(point, Particle.BLOCK, ejecta) else emit(point, Particle.FLAME)
        }
        tasks.runLater(2) {
            emit(origin.clone().add(0.0, 0.9, 0.0), Particle.EXPLOSION)
            repeat(28) { i ->
                val angle = i * PI * 2.0 / 28.0
                val ring = reach * (0.28 + (i % 4) * 0.035)
                val point = origin.clone().add(cos(angle) * ring, 0.06 + (i % 5) * 0.1,
                    sin(angle) * ring)
                emit(point, if (meteor) Particle.CLOUD else Particle.FLAME)
            }
            repeat(8) { i ->
                val angle = i * PI / 4.0
                emit(origin.clone().add(cos(angle) * reach * 0.22, 0.28, sin(angle) * reach * 0.22),
                    Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE)
            }
        }
        tasks.runLater(4) {
            repeat(36) { i ->
                val angle = i * PI * 2.0 / 36.0
                val ring = reach * (0.58 + (i % 3) * 0.12)
                emit(origin.clone().add(cos(angle) * ring, 0.08 + (i % 3) * 0.12,
                    sin(angle) * ring), Particle.DUST_COLOR_TRANSITION, GOLD_CORONA)
            }
            repeat(18) { i ->
                val angle = i * PI * 2.0 / 18.0 + 0.08
                val ring = reach * (0.36 + (i % 3) * 0.12)
                val point = origin.clone().add(cos(angle) * ring, 0.12 + (i % 4) * 0.12,
                    sin(angle) * ring)
                if (meteor) emit(point, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE)
                else emit(point, Particle.FLAME)
            }
        }
        tasks.runLater(8) {
            repeat(28) { i ->
                val angle = i * PI * 2.0 / 28.0 + 0.04
                val ring = reach * (0.52 + (i % 5) * 0.095)
                val point = origin.clone().add(cos(angle) * ring, 0.16 + (i % 6) * 0.16,
                    sin(angle) * ring)
                if (meteor) emit(point, Particle.DUST, GOLD_TAIL)
                else emit(point, if (i % 4 == 0) Particle.DUST else Particle.FLAME,
                    if (i % 4 == 0) GOLD_TAIL else null)
            }
            repeat(10) { i ->
                val angle = i * PI / 5.0
                emit(origin.clone().add(cos(angle) * reach * 0.54, 0.62 + (i % 3) * 0.12,
                    sin(angle) * reach * 0.54), Particle.SMOKE)
            }
        }
        tasks.runLater(12) {
            repeat(18) { i ->
                val angle = i * PI * 2.0 / 18.0 + 0.15
                val ring = reach * (0.48 + (i % 4) * 0.12)
                val point = origin.clone().add(cos(angle) * ring, 0.34 + (i % 5) * 0.19,
                    sin(angle) * ring)
                if (meteor || i % 3 == 0) emit(point, Particle.DUST, GOLD_TAIL)
                else emit(point, Particle.FLAME)
            }
            repeat(8) { i ->
                val angle = i * PI / 4.0 + 0.2
                emit(origin.clone().add(cos(angle) * reach * 0.36, 0.86 + (i % 2) * 0.14,
                    sin(angle) * reach * 0.36), Particle.SMOKE)
            }
        }
        tasks.runLater(16) {
            repeat(12) { i ->
                val angle = i * PI * 2.0 / 12.0
                val ring = reach * (0.54 + (i % 3) * 0.11)
                emit(origin.clone().add(cos(angle) * ring, 0.62 + (i % 4) * 0.18,
                    sin(angle) * ring), Particle.DUST, GOLD_TAIL)
            }
        }
        tasks.runLater(20) {
            repeat(8) { i ->
                val angle = i * PI / 4.0 + 0.3
                val ring = reach * (0.58 + (i % 2) * 0.12)
                emit(origin.clone().add(cos(angle) * ring, 0.8 + (i % 3) * 0.16,
                    sin(angle) * ring), Particle.DUST, GOLD_TAIL)
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
        val slash = 0.14 + phase * 0.025
        repeat(5) { i ->
            val side = (i - 2) / 2.0
            emit(tip.clone()
                .add(right.clone().multiply(side * slash))
                .add(up.clone().multiply(-side * slash * 0.7)), Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        if (phase == 0 || phase == 3) {
            emit(tip.clone().add(right.clone().multiply(-slash)), Particle.REVERSE_PORTAL,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(tip.clone().add(right.clone().multiply(slash)), Particle.REVERSE_PORTAL,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(tip, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        } else emit(tip, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
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
        val front = staffLanceFront(age, length)
        val launchFront = min(2.0, length.coerceIn(0.1, 48.0))
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        val tailStart = (front - min(2.6, (front - launchFront).coerceAtLeast(0.0)))
            .coerceAtLeast(launchFront)
        val bodyStart = start.clone().add(forward.clone().multiply(tailStart))
            .add(up.clone().multiply(staffLanceCenterOffset(tailStart, length)))
        lineSamples(bodyStart, tip, 2).forEachIndexed { index, point ->
            emit(point, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            if (index == 2) emit(point, Particle.DUST, HOT_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            if (index != 1) emit(point.clone().add(up.clone().multiply(0.12)), Particle.FLAME,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        emit(tip, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (age == flightTicks) {
            localSound(tip, Sound.ENTITY_BLAZE_SHOOT, 0.58f, 1.3f)
            // FLASH uses the default 3.2-block POV guard; each compact corona point is culled individually.
            emit(tip.clone().add(up.clone().multiply(0.10)), Particle.FLASH)
            emit(tip, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            repeat(12) { index ->
                val angle = index * PI / 6.0
                val ringRadius = 0.42 + (index % 3) * 0.05
                emit(tip.clone()
                    .add(right.clone().multiply(cos(angle) * ringRadius))
                    .add(up.clone().multiply(sin(angle) * ringRadius)),
                    Particle.DUST_COLOR_TRANSITION, GOLD_CORONA,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                if (index % 3 == 0) emit(tip.clone()
                    .add(right.clone().multiply(cos(angle) * (ringRadius + 0.12)))
                    .add(up.clone().multiply(sin(angle) * (ringRadius + 0.12))), Particle.FLAME,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
    }

    private fun lanceCharge(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, age: Int) {
        val muzzle = min(2.0, length.coerceIn(0.1, 48.0))
        val center = start.clone().add(forward.clone().multiply(muzzle))
            .add(up.clone().multiply(staffLanceCenterOffset(muzzle, length)))
        val phase = age.coerceIn(0, 4)
        val ringRadius = 0.42 - phase * 0.045
        val facets = if (age == 0) 3 else 4
        repeat(facets) { index ->
            val angle = index * PI * 2.0 / facets + phase * PI / 8.0
            emit(center.clone()
                .add(right.clone().multiply(cos(angle) * ringRadius))
                .add(up.clone().multiply(sin(angle) * ringRadius)),
                Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        if (age >= 3) emit(center, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        else emit(center, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (age == 4) localSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.48f, 1.62f)
    }

    private fun lanceAfterglow(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, count: Int) {
        val front = staffLanceFront(staffLanceFlightTicks(length), length)
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        repeat(count) { index ->
            val angle = index * PI * 2.0 / count
            emit(tip.clone()
                .add(right.clone().multiply(cos(angle) * 0.34))
                .add(up.clone().multiply(sin(angle) * 0.27)),
                if (index % 3 == 0) Particle.FLAME else Particle.DUST_COLOR_TRANSITION,
                if (index % 3 == 0) null else GOLD_CORONA,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    private fun novaFrame(at: Location, radius: Double, age: Int, secondary: Boolean) {
        val front = 1.8 + staffWaveFront(age, radius - 1.8, 16)
        val travel = ((age - 2) / 16.0).coerceIn(0.0, 1.0)
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            repeat(3) { index ->
                val angle = -0.24 + index * 0.24 + travel * 0.34
                val point = at.clone().add(cos(angle) * front, 0.38 + index * 0.08,
                    sin(angle) * front).apply { y = floorY + 0.38 + index * 0.08 }
                if (index == 1) emit(point, Particle.END_ROD)
                else emit(point, Particle.HAPPY_VILLAGER)
            }
            return
        }
        val arcPoints = when {
            age <= 10 -> 11
            age <= 14 -> 8
            age <= 18 -> 5
            else -> 3
        }
        val waveLift = 0.22 + sin(PI * travel) * 0.48
        repeat(arcPoints) { index ->
            val across = if (arcPoints == 1) 0.5 else index / (arcPoints - 1.0)
            val angle = -PI * 0.70 + across * PI * 1.40 + travel * 0.38
            val crown = sin(across * PI)
            val pointRadius = front - abs(across - 0.5) * 0.18
            val point = at.clone().add(cos(angle) * pointRadius,
                0.0, sin(angle) * pointRadius).apply {
                y = floorY + 0.18 + crown * (0.50 + waveLift)
            }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            if (index % 2 == 0) {
                emit(point.clone().add(0.0, 0.17, 0.0), Particle.HAPPY_VILLAGER)
                emit(point.clone().add(cos(angle) * -0.14, 0.14, sin(angle) * -0.14),
                    Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            }
            if (index % 4 == 0) emit(point.clone().add(0.0, 0.22, 0.0),
                if ((index + age / 2) % 3 == 0) Particle.HAPPY_VILLAGER else Particle.END_ROD)
            if (secondary && index % 3 == 0) emit(point.clone().add(0.0, -0.12, 0.0),
                Particle.DUST_COLOR_TRANSITION, VIOLET_TO_AQUA)
        }
    }

    private fun novaWaveFrame(at: Location, forward: Vector, right: Vector,
        reach: Double, configuredWidth: Double, age: Int) {
        val front = 1.8 + staffWaveFront(age, reach - 1.8, 16)
        val halfWidth = configuredWidth * front / reach
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            val crest = at.clone().add(forward.clone().multiply(reach)).apply { y = floorY + 0.42 }
            emit(crest, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            emit(crest.clone().add(right.clone().multiply(-0.18)), Particle.HAPPY_VILLAGER)
            emit(crest.clone().add(right.clone().multiply(0.18)), Particle.END_ROD)
            return
        }
        val arcPoints = when {
            age <= 10 -> 9
            age <= 14 -> 7
            age <= 18 -> 4
            else -> 2
        }
        val lift = sin(PI * ((age - 2) / 18.0).coerceIn(0.0, 1.0))
        repeat(arcPoints) { index ->
            val across = if (arcPoints == 1) 0.0 else index / (arcPoints - 1.0) * 2.0 - 1.0
            val crown = 1.0 - abs(across)
            val point = at.clone()
                .add(forward.clone().multiply(front + crown * 0.24))
                .add(right.clone().multiply(across * halfWidth))
                .apply { y = floorY + 0.16 + crown * (0.78 + lift * 0.24) }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            if (index % 2 == 0) emit(point.clone()
                .add(right.clone().multiply(0.08)).add(forward.clone().multiply(-0.12))
                .add(0.0, 0.14, 0.0), Particle.HAPPY_VILLAGER)
            if (index % 3 == 0) emit(point.clone().add(0.0, 0.2, 0.0), Particle.END_ROD)
            if (index % 2 == 1) {
                val spore = (index + age / 2) % 3 == 0
                if (spore) emit(point.clone().add(0.0, 0.12, 0.0), Particle.HAPPY_VILLAGER)
                else emit(point.clone().add(0.0, 0.12, 0.0), Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
            }
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
        // Paper 1.21.11 requires Color payload for FLASH; keep this typed default centralized.
        val particleData = data ?: if (particle == Particle.FLASH) Color.WHITE else null
        world.getNearbyPlayers(at, 48.0).forEach { player ->
            val eye = player.eyeLocation
            if (eye.world !== world) return@forEach
            val distanceSquared = eye.distanceSquared(at)
            if (distanceSquared < minEyeDistanceSquared || distanceSquared > maxViewerDistanceSquared) return@forEach
            if (particleData == null) player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0)
            else player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, particleData)
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
        val VIOLET_TO_WHITE = Particle.DustTransition(Color.fromRGB(178, 78, 255), Color.fromRGB(250, 240, 255), 1.45f)
        val VIOLET_TO_AQUA = Particle.DustTransition(Color.fromRGB(205, 90, 255), Color.fromRGB(70, 235, 255), 1.2f)
        val ACCRETION_DUST = Particle.DustTransition(Color.fromRGB(104, 30, 196), Color.fromRGB(238, 132, 255), 2.2f)
        val BLACKHOLE_RIM = Particle.DustTransition(Color.fromRGB(82, 20, 168), Color.fromRGB(248, 178, 255), 2.0f)
        val BLACKHOLE_CORE = Particle.DustTransition(Color.fromRGB(116, 56, 215), Color.fromRGB(255, 250, 255), 1.75f)
        val GOLD = Particle.DustOptions(Color.fromRGB(255, 193, 66), 1.3f)
        val GOLD_TAIL = Particle.DustOptions(Color.fromRGB(255, 222, 145), 0.75f)
        val HOT_WHITE = Particle.DustOptions(Color.fromRGB(255, 250, 226), 1.85f)
        val GOLD_TO_WHITE = Particle.DustTransition(Color.fromRGB(255, 176, 42), Color.fromRGB(255, 255, 240), 1.55f)
        val GOLD_CORONA = Particle.DustTransition(Color.fromRGB(255, 255, 230), Color.fromRGB(255, 179, 44), 1.7f)
        val ICE_TO_AQUA = Particle.DustTransition(Color.fromRGB(180, 230, 255), Color.fromRGB(90, 245, 255), 0.9f)
        val GREEN_TO_AQUA = Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.15f)
        val GREEN_TO_WHITE = Particle.DustTransition(Color.fromRGB(196, 255, 220), Color.fromRGB(50, 255, 125), 1.6f)
    }
}
