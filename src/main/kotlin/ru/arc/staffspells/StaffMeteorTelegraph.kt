package ru.arc.staffspells

import org.bukkit.Particle
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val STAFF_METEOR_WARNING_TICKS = 15
internal const val STAFF_METEOR_RADIUS = 3.5

/** The bright rim is the actual damage radius; the inner fuse closes only after release. */
internal fun staffMeteorWarningParticles(progress: Double, locked: Boolean): List<StaffParticleSample> {
    val fraction = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return buildList {
        repeat(32) { index ->
            val angle = index * 2 * PI / 32
            val offset = Vector(cos(angle) * STAFF_METEOR_RADIUS, .10, sin(angle) * STAFF_METEOR_RADIUS)
            add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION, offset,
                data = staffFade(if (locked) 0xff793b else 0xffc077, 0xcf451e, .65f)))
        }
        if (locked) repeat(12) { index ->
            val angle = index * 2 * PI / 12
            val radius = STAFF_METEOR_RADIUS * (1 - fraction)
            add(StaffParticleSample(Particle.SMALL_FLAME, Vector(cos(angle) * radius, .16, sin(angle) * radius)))
        }
    }
}
