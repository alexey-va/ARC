package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.util.Vector
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

    /** Called every two ticks: three inward-moving wisps, with no rotating ring or repeated sound. */
    fun markCharge(at: Location, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val reach = 0.9 * (1.0 - charge) + 0.12
        repeat(3) { i ->
            val angle = i * PI * 2 / 3 - PI / 2
            emit(origin.clone().add(cos(angle) * reach, 0.14 + (i % 2) * 0.08, sin(angle) * reach), Particle.WITCH)
        }
    }

    fun markBurst(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.82f, 0.82f)
        val coreRadius = min(reach * 0.1, 0.3)
        repeat(5) { i ->
            val angle = i * PI * 2 / 5
            emit(origin.clone().add(cos(angle) * coreRadius, 0.08, sin(angle) * coreRadius), Particle.DUST, VIOLET)
        }
        emit(origin, Particle.REVERSE_PORTAL)
        tasks.runLater(2) { markWispsFrame(origin, min(reach * 0.18, 1.0), 0) }
        tasks.runLater(5) { markWispsFrame(origin, min(reach * 0.3, 1.5), 1) }
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
        val basis = frame(offset) ?: return@dispatch
        val forward = offset.clone().normalize()
        val charge = start.clone().add(forward.clone().multiply(min(0.85, distance * 0.65)))
        localSound(charge, Sound.ENTITY_ARROW_SHOOT, 0.78f, 0.92f)
        repeat(3) { i ->
            val side = (i - 1) * 0.075
            emit(charge.clone().add(basis.first.clone().multiply(side)), Particle.DUST, GOLD)
        }
        for (phase in 1..3) {
            tasks.runLater(phase.toLong()) { lanceStage(start, end, basis.first, distance, phase) }
        }
        tasks.runLater(7) { lanceTail(start, end, 0) }
        tasks.runLater(12) { lanceTail(start, end, 1) }
        tasks.runLater(18) { lanceTail(start, end, 2) }
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
            emit(point, Particle.FLAME)
        }
    }

    fun emberBurst(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.72f, 1.05f)
        val puffRadius = min(reach * 0.35, 1.35)
        repeat(8) { i ->
            val angle = i * PI * 2 / 8
            val height = when (i % 4) { 0 -> 0.08; 1 -> 0.32; 2 -> -0.08; else -> -0.32 }
            emit(origin.clone().add(cos(angle) * puffRadius, height, sin(angle) * puffRadius), Particle.FLAME)
        }
        repeat(2) { i -> emit(origin.clone().add(0.0, i * 0.14, 0.0), Particle.SMOKE) }
    }

    fun nova(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 2.0, 10.0) ?: return@dispatch
        repeat(5) { phase ->
            tasks.runLater((2 + phase * 4).toLong()) {
                if (phase == 0) localSound(origin, Sound.BLOCK_BEACON_ACTIVATE, 0.74f, 1.42f)
                novaFrame(origin, reach, phase)
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

    private fun markWispsFrame(at: Location, radius: Double, phase: Int) {
        repeat(if (phase == 0) 3 else 2) { i ->
            val angle = i * PI * 2 / 3 + phase * 0.42
            val point = at.clone().add(cos(angle) * radius, 0.2 + phase * 0.32, sin(angle) * radius)
            emit(point, Particle.WITCH)
        }
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
            if (i == 2) emit(point.clone().add(0.0, 0.035, 0.0), Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
            if (i == 1 || i == 3) emit(point.clone().add(0.0, 0.045, 0.0), Particle.BLOCK, ice)
        }
        if (phase > 1 && phase < steps && phase % 2 == 0) {
            val frost = origin.clone().add(forward.clone().multiply(distance * 0.72)).apply { y = floorY }
            repeat(2) { i ->
                val side = if (i == 0) -1.0 else 1.0
                emit(frost.clone().add(right.clone().multiply(side * min(distance * 0.12, 0.55))), Particle.SNOWFLAKE)
            }
        }
    }

    private fun lanceStage(start: Location, end: Location, right: Vector,
        distance: Double, phase: Int) {
        val launchProgress = min(0.65, 0.85 / distance)
        val tipProgress = launchProgress + (1.0 - launchProgress) * phase / 3.0
        val shaftStart = interpolate(start, end, launchProgress)
        val tip = interpolate(start, end, tipProgress)
        val shaftLength = shaftStart.distance(tip)
        val segments = (shaftLength * 1.25).toInt().coerceIn(1, 8)
        lineSamples(shaftStart, tip, segments).forEachIndexed { i, point ->
            val shimmer = if (i % 2 == 0) right.clone().multiply(0.025) else right.clone().multiply(-0.025)
            emit(point.add(shimmer), Particle.DUST, GOLD)
        }
        emit(tip, Particle.END_ROD)
    }

    private fun lanceTail(start: Location, end: Location, phase: Int) {
        val count = 3 - phase
        repeat(count) { i ->
            val t = 0.82 + (i + 1) * 0.16 / (count + 1)
            emit(interpolate(start, end, t.coerceAtMost(0.995)), Particle.DUST, GOLD_TAIL)
        }
    }

    private fun novaFrame(at: Location, radius: Double, phase: Int) {
        val reach = 1.8 + (radius - 1.8) * phase / 4.0
        val floorY = at.blockY + 0.06
        repeat(12) { i ->
            val angle = i * PI * 2 / 12
            val point = at.clone().add(cos(angle) * reach, 0.0, sin(angle) * reach).apply { y = floorY }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
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

    private fun emit(at: Location, particle: Particle, data: Any? = null) {
        if (!drawable(at)) return
        val world = at.world ?: return
        if (data == null) world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0)
        else world.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, data)
    }

    private fun localSound(at: Location, sound: Sound, volume: Float, pitch: Float) {
        if (drawable(at)) at.world?.playSound(at, sound, SoundCategory.PLAYERS, volume, pitch)
    }

    private fun dispatch(action: () -> Unit) {
        if (Bukkit.isPrimaryThread()) action() else tasks.runSync(action)
    }

    private companion object {
        val VIOLET = Particle.DustOptions(Color.fromRGB(166, 74, 255), 1.15f)
        val GOLD = Particle.DustOptions(Color.fromRGB(255, 193, 66), 1.3f)
        val GOLD_TAIL = Particle.DustOptions(Color.fromRGB(255, 222, 145), 0.75f)
        val ICE_TO_AQUA = Particle.DustTransition(Color.fromRGB(180, 230, 255), Color.fromRGB(90, 245, 255), 0.9f)
        val GREEN_TO_AQUA = Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.15f)
    }
}
