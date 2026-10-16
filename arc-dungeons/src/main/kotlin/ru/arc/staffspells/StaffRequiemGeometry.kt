package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

internal const val STAFF_REQUIEM_PRIMARY_CLANG_TICK = 16
internal const val STAFF_REQUIEM_PRIMARY_END_TICK = 30
internal val STAFF_REQUIEM_PRIMARY_CLANG_TICKS = listOf(16, 40, 64)
internal const val STAFF_REQUIEM_PRIMARY_DURATION_TICKS = 86
internal const val STAFF_REQUIEM_PRIMARY_RADIUS = 5.0
internal val STAFF_REQUIEM_SECONDARY_CLANG_TICKS = listOf(16, 40, 64, 88, 112)
internal const val STAFF_REQUIEM_SECONDARY_FRONT_TICKS = 14
internal const val STAFF_REQUIEM_SECONDARY_DURATION_TICKS = 134
internal const val STAFF_REQUIEM_SECONDARY_RADIUS = 7.0
internal const val STAFF_REQUIEM_COLLISION_RAY_COUNT = 24

internal data class StaffRequiemPulse(
    val index: Int,
    val startTick: Int,
    val endTick: Int,
    val progress: Double,
)

/** Shared timing for the preview and the collision front. */
internal fun staffRequiemPulse(ageTicks: Int, secondary: Boolean): StaffRequiemPulse? {
    val age = ageTicks.coerceAtLeast(0)
    val starts = if (secondary) STAFF_REQUIEM_SECONDARY_CLANG_TICKS else STAFF_REQUIEM_PRIMARY_CLANG_TICKS
    val travelTicks = if (secondary) STAFF_REQUIEM_SECONDARY_FRONT_TICKS
        else STAFF_REQUIEM_PRIMARY_END_TICK - STAFF_REQUIEM_PRIMARY_CLANG_TICK
    val index = starts.indexOfLast { age >= it }
    if (index < 0) return null
    val start = starts[index]
    val end = start + travelTicks
    if (age > end) return null
    return StaffRequiemPulse(index, start, end,
        ((age - start).toDouble() / travelTicks).coerceIn(0.0, 1.0))
}

/** Copper-and-gold bell in scene-local coordinates; its bell body is centered at y=4. */
internal fun staffRequiemBellParts(ageTicks: Int, durationTicks: Int, secondary: Boolean,
    charge: Double = 0.0): List<StaffDisplayPart> {
    val age = ageTicks.coerceAtLeast(0)
    val duration = durationTicks.coerceAtLeast(1)
    if (age >= duration) return emptyList()
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val clang = (if (secondary) STAFF_REQUIEM_SECONDARY_CLANG_TICKS
        else STAFF_REQUIEM_PRIMARY_CLANG_TICKS).lastOrNull { age >= it }
    val swingAge = clang?.let { (age - it).toDouble() } ?: -1.0
    val swing = if (swingAge in 0.0..8.0) sin(swingAge * .8) * exp(-swingAge * .3) else 0.0
    val assembly = requiemSmooth(age / 8.0)
    val fade = requiemSmooth((duration - age) / 6.0)
    val opening = .55 + .45 * assembly
    val bodyScale = if (secondary) 1.70 else 1.42
    val scale = (bodyScale + q * .15) * fade
    val bodyTilt = swing * .21
    val hingeY = 5.18
    val parts = ArrayList<StaffDisplayPart>(34)
    fun part(id: String, material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0) {
        val expandedX = x * opening
        val expandedZ = z * opening
        val shiftedY = y + (1.0 - assembly) * 2.0
        val pivotY = shiftedY - hingeY
        val turnedX = expandedX * cos(bodyTilt) - pivotY * sin(bodyTilt)
        val turnedY = hingeY + expandedX * sin(bodyTilt) + pivotY * cos(bodyTilt)
        parts += StaffDisplayPart(material,
            Vector3f((turnedX * scale).toFloat(), (4.0 + (turnedY - 4.0) * bodyScale).toFloat(), (expandedZ * scale).toFloat()),
            Vector3f((sx * scale * opening).coerceAtLeast(.001).toFloat(),
                (sy * scale).coerceAtLeast(.001).toFloat(), (sz * scale * opening).coerceAtLeast(.001).toFloat()),
            Quaternionf().rotationZ(bodyTilt.toFloat()).rotateY(yaw.toFloat()), identity = id)
    }
    fun ring(prefix: String, material: Material, y: Double, radius: Double,
        width: Double, height: Double, depth: Double) {
        repeat(8) { index ->
            val angle = index * PI / 4 + PI / 8
            part("bell:$prefix:$index", if (index % 4 == 0) Material.GOLD_BLOCK else material,
                cos(angle) * radius, y, sin(angle) * radius,
                width, height, depth, yaw = angle + PI / 2)
        }
    }
    ring("skirt", Material.OXIDIZED_COPPER, 3.62, .9, .8, .56, .3)
    ring("body", Material.OXIDIZED_COPPER, 4.13, .63, .47, .55, .25)
    ring("shoulder", Material.COPPER_BLOCK, 4.63, .38, .35, .37, .22)
    repeat(4) { side ->
        val angle = side * PI / 2
        part("bell:gold-rim:$side", Material.GOLD_BLOCK,
            cos(angle) * .78, 3.31, sin(angle) * .78, 1.55, .16, .2, yaw = angle + PI / 2)
    }
    part("bell:cap", Material.GOLD_BLOCK, 0.0, 5.05, 0.0, .52, .34, .52)
    val clapperX = sin(swing) * .88
    val clapperY = 4.78 - cos(swing) * .88
    part("bell:clapper:stem", Material.DEEPSLATE, sin(swing) * .42, 4.78 - cos(swing) * .42,
        0.0, .12, .88, .12)
    part("bell:clapper:bell", Material.GOLD_BLOCK, clapperX, clapperY, 0.0, .34, .34, .34)
    return parts
}

/** Native soul accents and a ground-level ring, in offsets from the pulse center. */
internal fun staffRequiemParticles(ageTicks: Int, durationTicks: Int, secondary: Boolean,
    radius: Double, charge: Double = 0.0, rayLimits: List<Double> = emptyList()): List<StaffParticleSample> {
    val age = ageTicks.coerceAtLeast(0)
    if (age >= durationTicks.coerceAtLeast(1)) return emptyList()
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val soulDust = staffFade(0xe7d5ff, 0x7652a8, 1.25f + (q * .2).toFloat())
    val out = ArrayList<StaffParticleSample>(56)
    fun mote(particle: Particle, x: Double, y: Double, z: Double, data: Any? = null) {
        out += StaffParticleSample(particle, Vector(x, y, z), data = data)
    }
    val pulse = staffRequiemPulse(age, secondary)
    val finalFront = if (secondary) STAFF_REQUIEM_SECONDARY_CLANG_TICKS.last() + STAFF_REQUIEM_SECONDARY_FRONT_TICKS
        else STAFF_REQUIEM_PRIMARY_CLANG_TICKS.last() + STAFF_REQUIEM_PRIMARY_END_TICK - STAFF_REQUIEM_PRIMARY_CLANG_TICK
    val primaryTail = age > finalFront && age < durationTicks
    if (pulse == null && !primaryTail) {
        repeat(32) { index ->
            val angle = index * PI * 2 / 32 + age * .025
            val radius = .62 + sin(angle * 3 + age * .08) * .035
            mote(if (index % 8 == 0) Particle.SOUL else Particle.DUST_COLOR_TRANSITION,
                cos(angle) * radius, 4.0 + sin(angle * 2) * .18, sin(angle) * radius,
                if (index % 8 == 0) null else soulDust)
        }
        repeat(8) { index ->
            val angle = index * PI / 4 + age * .04
            mote(Particle.SOUL_FIRE_FLAME, cos(angle) * .5, 4.0, sin(angle) * .5)
        }
        return out
    }

    val safeRadius = radius.takeIf(Double::isFinite)?.coerceIn(0.0, 64.0) ?: 0.0
    val progress = pulse?.progress ?: 1.0
    val fade = if (primaryTail) ((durationTicks - age).toDouble() /
        (durationTicks - finalFront).coerceAtLeast(1)).coerceIn(0.0, 1.0) else 1.0
    val moteCount = (STAFF_REQUIEM_COLLISION_RAY_COUNT * 2 * fade).toInt().coerceAtLeast(1)
    repeat(moteCount) { index ->
        val ray = index / 2
        val angle = ray * PI * 2 / STAFF_REQUIEM_COLLISION_RAY_COUNT
        val jagged = .96 + .04 * sin(angle * 7 + age * .04 + (pulse?.index ?: 0) * .9)
        val limit = rayLimits.getOrNull(ray)?.takeIf(Double::isFinite)?.coerceIn(0.0, safeRadius) ?: safeRadius
        val frontRadius = minOf(safeRadius * progress * jagged, limit) - (index % 2) * .12
        val particle = if (ray % 6 == 0) Particle.SOUL else Particle.DUST_COLOR_TRANSITION
        mote(particle, cos(angle) * frontRadius.coerceAtLeast(0.0),
            .13 + (index % 2) * .1 + sin(angle * 3 + age * .12) * .045,
            sin(angle) * frontRadius.coerceAtLeast(0.0), if (particle == Particle.SOUL) null else soulDust)
    }
    val flameCount = (8 * fade).toInt().coerceAtLeast(1)
    repeat(flameCount) { index ->
        val ray = index * STAFF_REQUIEM_COLLISION_RAY_COUNT / flameCount
        val angle = ray * PI * 2 / STAFF_REQUIEM_COLLISION_RAY_COUNT
        val limit = rayLimits.getOrNull(ray)?.takeIf(Double::isFinite)?.coerceIn(0.0, safeRadius) ?: safeRadius
        val frontRadius = minOf(safeRadius * progress, limit)
        mote(Particle.SOUL_FIRE_FLAME, cos(angle) * frontRadius, .2, sin(angle) * frontRadius)
    }
    return out
}

private fun requiemSmooth(value: Double) = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.let {
    it * it * (3.0 - 2.0 * it)
} ?: 0.0
