package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, particle-only spell animations. Entry points and delayed phases always run on Paper's main thread. */
internal class StaffSpellVisuals(private val tasks: LifecycleTaskScope) {
    fun lightning(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val points = boltPoints(start, end, distance)
        points.forEachIndexed { i, point ->
            emit(point, Particle.ELECTRIC_SPARK)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.035, 0.0), Particle.END_ROD)
        }
        val frame = frame(start.toVector().subtract(end.toVector())) ?: return@dispatch
        listOf(0.24, 0.53, 0.79).forEachIndexed { branch, fraction ->
            val anchor = points[(fraction * (points.lastIndex)).toInt()]
            val sign = if (branch == 1) -1.0 else 1.0
            for (step in 1..5) {
                val t = step / 5.0
                emit(anchor.clone()
                    .add(frame.first.clone().multiply(sign * (0.18 + t * 0.48)))
                    .add(frame.second.clone().multiply(t * (if (branch == 1) -0.32 else 0.24))),
                    Particle.ELECTRIC_SPARK)
            }
        }
        localSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 1.8f)
        tasks.runLater(1) {
            lineSamples(start, end, (distance * 1.1).toInt().coerceIn(8, 28)).forEachIndexed { i, point ->
                if (i % 2 == 0) emit(point, Particle.END_ROD)
            }
            emit(end, Particle.FLASH)
        }
    }

    fun markLaunch(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val frame = frame(end.toVector().subtract(start.toVector())) ?: return@dispatch
        val steps = (distance * 1.6).toInt().coerceIn(12, 36)
        for (i in 0..steps) {
            val t = i / steps.toDouble()
            val base = interpolate(start, end, t)
            val angle = t * PI * 8
            val radius = 0.08 + 0.12 * sin(t * PI)
            for (side in listOf(-1.0, 1.0)) {
                val point = base.clone()
                    .add(frame.first.clone().multiply(cos(angle) * radius * side))
                    .add(frame.second.clone().multiply(sin(angle) * radius * side))
                emit(point, Particle.DUST, VIOLET)
            }
            if (i % 4 == 0) emit(base, Particle.REVERSE_PORTAL)
        }
        localSound(start, Sound.ENTITY_ENDERMAN_TELEPORT, 0.35f, 1.35f)
        tasks.runLater(1) {
            lineSamples(start, end, 18).forEachIndexed { i, point ->
                if (i % 2 == 0) emit(point, Particle.WITCH)
            }
        }
    }

    /** Called repeatedly while a mark is charging; stays below 64 particles per call and makes no sound. */
    fun markCharge(at: Location, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        repeat(24) { i ->
            val angle = i * PI * 2 / 24
            emit(origin.clone().add(cos(angle) * 0.58, 0.0, sin(angle) * 0.58), Particle.WITCH)
            if (i % 2 == 0) emit(origin.clone().add(cos(angle) * 0.82, 0.0, sin(angle) * 0.82), Particle.DUST, VIOLET)
        }
        repeat(ceil(charge * 16).toInt()) { i ->
            val angle = i * PI * 2 / 16 - PI / 2
            emit(origin.clone().add(cos(angle) * 1.02, 0.08, sin(angle) * 1.02), Particle.END_ROD)
        }
    }

    fun markBurst(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.5f, 0.75f)
        repeat(3) { phase ->
            val draw = { burstMarkFrame(origin, reach, phase) }
            if (phase == 0) draw() else tasks.runLater(phase.toLong()) { draw() }
        }
    }

    fun frost(eye: Location, range: Double, halfAngle: Double) = dispatch {
        val origin = eye.clone()
        val reach = boundedRadius(range, 0.5, 14.0) ?: return@dispatch
        val spread = halfAngle.takeIf(Double::isFinite)?.coerceIn(5.0, 85.0) ?: return@dispatch
        val direction = origin.direction
        val frame = frame(direction) ?: return@dispatch
        localSound(origin, Sound.BLOCK_GLASS_BREAK, 0.48f, 0.72f)
        repeat(4) { phase ->
            val draw = { frostWaveFrame(origin, direction, frame.first, reach, spread, phase) }
            if (phase == 0) draw() else tasks.runLater((phase * 2).toLong()) { draw() }
        }
    }

    fun lance(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val frame = frame(end.toVector().subtract(start.toVector())) ?: return@dispatch
        val steps = (distance * 1.5).toInt().coerceIn(12, 32)
        for (i in 0..steps) {
            val t = i / steps.toDouble()
            val base = interpolate(start, end, t)
            val angle = t * PI * 10
            val radius = 0.035 + 0.09 * sin(t * PI)
            for (side in listOf(-1.0, 1.0)) {
                emit(base.clone()
                    .add(frame.first.clone().multiply(cos(angle) * radius * side))
                    .add(frame.second.clone().multiply(sin(angle) * radius * side)), Particle.DUST, GOLD)
            }
            if (i % 3 == 0) emit(base, Particle.END_ROD)
        }
        localSound(start, Sound.ENTITY_ARROW_SHOOT, 0.42f, 0.55f)
        tasks.runLater(1) {
            lineSamples(start, end, 18).forEachIndexed { i, point -> if (i % 2 == 0) emit(point, Particle.ELECTRIC_SPARK) }
            emit(end, Particle.FLASH)
        }
    }

    /** Trail is designed for per-tick projectile callbacks: at most 34 particles, no repeated sound. */
    fun emberTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        if (start.world == null || end.world !== start.world || !valid(start) || !valid(end)) return@dispatch
        if (start.toVector().distanceSquared(end.toVector()) < 0.0001) {
            emberCore(end)
            localSound(end, Sound.ENTITY_BLAZE_SHOOT, 0.34f, 1.35f)
            return@dispatch
        }
        val distance = segmentLength(start, end) ?: return@dispatch
        lineSamples(start, end, (distance * 1.3).toInt().coerceIn(8, 16)).forEachIndexed { i, point ->
            emit(point, Particle.FLAME)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.025, 0.0), Particle.SMOKE)
        }
        emberCore(end)
    }

    fun emberBurst(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        localSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.52f, 0.65f)
        repeat(3) { phase ->
            val draw = { emberBurstFrame(origin, reach, phase) }
            if (phase == 0) draw() else tasks.runLater(phase.toLong()) { draw() }
        }
    }

    fun nova(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 12.0) ?: return@dispatch
        localSound(origin, Sound.BLOCK_BEACON_ACTIVATE, 0.42f, 1.7f)
        repeat(4) { phase ->
            val draw = { novaFrame(origin, reach, phase) }
            if (phase == 0) draw() else tasks.runLater((phase * 2).toLong()) { draw() }
        }
    }

    private fun burstMarkFrame(at: Location, radius: Double, phase: Int) {
        val reach = radius * (0.48 + phase * 0.26)
        repeat(28) { i ->
            val angle = i * PI * 2 / 28
            val point = at.clone().add(cos(angle) * reach, 0.0, sin(angle) * reach)
            emit(point, Particle.DUST, VIOLET)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.22 + phase * 0.12, 0.0), Particle.WITCH)
        }
        if (phase == 2) emit(at, Particle.FLASH)
    }

    private fun frostWaveFrame(origin: Location, forward: Vector, right: Vector,
        range: Double, halfAngle: Double, phase: Int) {
        val distance = range * (phase + 1) / 4.0
        for (i in 0..16) {
            val angle = Math.toRadians((i / 8.0 - 1.0) * halfAngle)
            val direction = forward.clone().multiply(cos(angle)).add(right.clone().multiply(sin(angle))).normalize()
            val point = origin.clone().add(direction.multiply(distance))
            emit(point, Particle.SNOWFLAKE)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.12, 0.0), Particle.CLOUD)
        }
    }

    private fun emberBurstFrame(at: Location, radius: Double, phase: Int) {
        val reach = radius * (0.4 + phase * 0.3)
        repeat(36) { i ->
            val y = 1.0 - 2.0 * (i + 0.5) / 36.0
            val ring = sqrt(1.0 - y * y)
            val angle = i * 2.399963229728653
            val point = at.clone().add(cos(angle) * ring * reach, y * reach, sin(angle) * ring * reach)
            emit(point, Particle.FLAME)
            if (i % 2 == 0) emit(point, Particle.SMOKE)
        }
        if (phase == 2) emit(at, Particle.FLASH)
    }

    private fun emberCore(at: Location) {
        repeat(8) { i ->
            val angle = i * PI / 4
            emit(at.clone().add(cos(angle) * 0.11, 0.035, sin(angle) * 0.11), Particle.FLAME)
        }
    }

    private fun novaFrame(at: Location, radius: Double, phase: Int) {
        val reach = radius * (phase + 1) / 4.0
        repeat(24) { i ->
            val angle = i * PI * 2 / 24
            val point = at.clone().add(cos(angle) * reach, 0.0, sin(angle) * reach)
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.32, 0.0), Particle.WITCH)
            if (i % 3 == 0) emit(at.clone().add(cos(angle) * reach * 0.68, -0.3, sin(angle) * reach * 0.68), Particle.END_ROD)
        }
        if (phase == 3) emit(at, Particle.FLASH)
    }

    private fun boltPoints(from: Location, to: Location, distance: Double): List<Location> {
        val direction = to.toVector().subtract(from.toVector())
        val frame = frame(direction) ?: return emptyList()
        val steps = (distance * 2.0).toInt().coerceIn(12, 48)
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
        val GREEN_TO_AQUA = Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.15f)
    }
}
