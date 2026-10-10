package ru.arc.staffspells

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.util.Vector

/** A native particle emission relative to an effect anchor, also consumed by the animation preview. */
internal data class StaffParticleSample(
    val particle: Particle,
    val offset: Vector,
    val count: Int = 1,
    val spread: Vector = Vector(),
    val speed: Double = 0.0,
    val data: Any? = null,
)

internal fun staffDust(rgb: Int, size: Float = 1.25f) = Particle.DustOptions(Color.fromRGB(rgb), size)
internal fun staffFade(from: Int, to: Int, size: Float = 1.25f) =
    Particle.DustTransition(Color.fromRGB(from), Color.fromRGB(to), size)
