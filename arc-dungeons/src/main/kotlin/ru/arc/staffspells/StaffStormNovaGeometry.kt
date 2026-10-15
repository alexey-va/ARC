package ru.arc.staffspells

import org.bukkit.Particle
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Horizontal rays shared by the runtime and source-driven preview. */
internal const val STAFF_STORM_NOVA_RAY_COUNT = 24
internal const val STAFF_STORM_NOVA_RADIUS = 8.0
internal const val STAFF_STORM_NOVA_BUILDUP_TICKS = 4
internal const val STAFF_STORM_NOVA_TRAVEL_TICKS = 10

/**
 * Returns one bounded, local-particle frame for the CHAIN secondary.
 * `rayLimits` are horizontal distances from the anchor, one per 15-degree sector.
 */
internal fun staffStormNovaFrame(
    ageTicks: Int,
    radius: Double,
    rayLimits: List<Double>,
): List<StaffParticleSample> {
    if (!radius.isFinite() || radius <= 0.0 || rayLimits.size != STAFF_STORM_NOVA_RAY_COUNT) return emptyList()
    val reach = radius.coerceAtMost(64.0)
    val age = ageTicks.coerceAtLeast(0)
    val buildup = STAFF_STORM_NOVA_BUILDUP_TICKS
    val travel = STAFF_STORM_NOVA_TRAVEL_TICKS
    val front = if (age <= buildup) {
        0.18 + 0.42 * age / buildup
    } else {
        reach * ((age - buildup).toDouble() / travel).coerceIn(0.0, 1.0)
    }
    val transition = staffFade(0x27D8FF, 0xF5FDFF, 1.2f)
    val samples = ArrayList<StaffParticleSample>(128)

    fun sectorLimit(index: Int): Double {
        val current = index.mod(STAFF_STORM_NOVA_RAY_COUNT)
        val before = (current - 1).mod(STAFF_STORM_NOVA_RAY_COUNT)
        val after = (current + 1).mod(STAFF_STORM_NOVA_RAY_COUNT)
        return min(reach, minOf(
            safeLimit(rayLimits[current], reach),
            safeLimit(rayLimits[before], reach),
            safeLimit(rayLimits[after], reach),
        ))
    }

    fun add(particle: Particle, angle: Double, distance: Double, clip: Double, y: Double, data: Any? = null) {
        val radial = min(distance.coerceAtLeast(0.0), clip)
        if (!radial.isFinite() || radial < 0.02) return
        samples += StaffParticleSample(
            particle = particle,
            offset = Vector(cos(angle) * radial, y, sin(angle) * radial),
            data = data,
        )
    }

    for (index in 0 until STAFF_STORM_NOVA_RAY_COUNT) {
        val angle = index * PI * 2.0 / STAFF_STORM_NOVA_RAY_COUNT
        val clip = sectorLimit(index)
        if (clip <= 0.02) continue

        if (age <= buildup) {
            val coilRadius = min(front * (0.94 + (index % 3) * 0.04), clip)
            add(Particle.DUST_COLOR_TRANSITION, angle, coilRadius, clip, 0.08, transition)
            if (index % 2 == 0) {
                val flicker = angle + if (index % 4 == 0) 0.11 else -0.11
                add(Particle.ELECTRIC_SPARK, flicker, coilRadius * 0.72, clip, 0.16)
            }
        } else {
            // The outer contour and three interrupted spokes make the front visibly jagged.
            val jag = when (index % 4) { 0 -> 1.0; 1 -> 0.91; 2 -> 1.04; else -> 0.96 }
            add(Particle.DUST_COLOR_TRANSITION, angle, front * jag, clip, 0.08, transition)
            val lean = if (index % 2 == 0) 0.075 else -0.075
            add(Particle.ELECTRIC_SPARK, angle + lean, front * 0.36, clip, 0.18)
            add(Particle.ELECTRIC_SPARK, angle - lean, front * 0.67, clip, 0.13)
            add(Particle.DUST_COLOR_TRANSITION, angle + lean * 0.55, front * 0.88, clip, 0.09, transition)
            if (index % 2 == 0) {
                val branchSign = if (index % 4 == 0) 1.0 else -1.0
                add(Particle.ELECTRIC_SPARK, angle + branchSign * 0.14, front * 0.73, clip, 0.24)
            }
        }
    }

    if (age <= buildup) {
        repeat(8) { index ->
            val angle = index * PI / 4.0 + age * 0.19
            val rayIndex = index * STAFF_STORM_NOVA_RAY_COUNT / 8
            add(Particle.ELECTRIC_SPARK, angle, 0.10 + (index % 3) * 0.08, sectorLimit(rayIndex), 0.24)
        }
    }
    return samples.take(STAFF_STORM_NOVA_MAX_SAMPLES)
}

private fun safeLimit(value: Double, radius: Double) =
    value.takeIf(Double::isFinite)?.coerceIn(0.0, radius) ?: 0.0

private const val STAFF_STORM_NOVA_MAX_SAMPLES = 192

/** Three sky strikes accompany each expanding contact front, clipped by the same adjacent cover rays. */
internal fun staffStormNovaStrikes(ageTicks: Int, radius: Double, rayLimits: List<Double>): List<Vector> {
    if (ageTicks !in listOf(6, 10, 14) || !radius.isFinite() || radius <= 0.0 ||
        rayLimits.size != STAFF_STORM_NOVA_RAY_COUNT) return emptyList()
    val beat = (ageTicks - 6) / 4
    val front = radius * (ageTicks - STAFF_STORM_NOVA_BUILDUP_TICKS) / STAFF_STORM_NOVA_TRAVEL_TICKS
    return (0..2).mapNotNull { branch ->
        val sector = (beat * 5 + branch * 8) % STAFF_STORM_NOVA_RAY_COUNT
        val clip = (-1..1).minOf { neighbor -> safeLimit(rayLimits[(sector + neighbor).mod(STAFF_STORM_NOVA_RAY_COUNT)], radius) }
        val distance = minOf(front, clip - .18)
        if (distance < 1.25) return@mapNotNull null
        val angle = sector * PI * 2 / STAFF_STORM_NOVA_RAY_COUNT
        Vector(cos(angle) * distance, 0.0, sin(angle) * distance)
    }
}
