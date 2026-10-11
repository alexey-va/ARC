package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.joml.Quaternionf
import org.joml.Vector3f
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/** Bounded native flame samples in caster-local coordinates; +Z is the current look direction. */
internal fun staffInfernoFrame(ageTicks: Int, reach: Double): List<StaffParticleSample> {
    val length = reach.takeIf(Double::isFinite)?.coerceIn(0.0, 10.0) ?: 8.0
    if (length < INFERNO_MUZZLE_CLEARANCE) return emptyList()
    val phase = ageTicks.coerceAtLeast(0) * 0.31
    val core = staffFade(0xFFD64A, 0xFFF6C5, 1.25f)
    val edge = staffFade(0xFF531B, 0xFFB52D, 1.1f)
    return buildList(48) {
        repeat(16) { i ->
            val t = (i + 1) / 16.0
            val z = INFERNO_MUZZLE_CLEARANCE + (length - INFERNO_MUZZLE_CLEARANCE) * t
            val width = 0.045 + 0.24 * t
            val angle = phase + i * 2.399963
            val x = sin(angle) * width
            val y = cos(angle * 1.13) * width * 0.72
            add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(x * 0.35, y * 0.35, z), data = core))
            add(StaffParticleSample(Particle.FLAME, Vector(x, y, z), data = null))
            add(StaffParticleSample(Particle.FLAME,
                Vector(sin(angle + 1.8) * width * 1.65, cos(angle * 0.91 + 0.7) * width * 1.35, z), data = null))
        }
        repeat(8) { i ->
            val t = (i + 1) / 9.0
            val angle = phase * 0.7 + i * 2.399963
            add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION,
                Vector(sin(angle) * (0.18 + t * 0.24), cos(angle) * (0.12 + t * 0.2),
                    INFERNO_MUZZLE_CLEARANCE + (length - INFERNO_MUZZLE_CLEARANCE) * t),
                data = edge))
        }
    }
}

private const val INFERNO_MUZZLE_CLEARANCE = 0.45

/** Decorative block particles copied from a selected block; the real block is never modified. */
internal fun staffGravitySelectionDebris(blockData: BlockData) = listOf(
    StaffParticleSample(Particle.BLOCK, Vector(), count = 10, spread = Vector(0.16, 0.16, 0.16),
        speed = 0.035, data = blockData),
)

internal fun staffGravityImpactFrame() = listOf(
    StaffParticleSample(Particle.BLOCK, Vector(), count = 18, spread = Vector(0.32, 0.30, 0.32),
        speed = 0.08, data = Material.COBBLESTONE.createBlockData()),
    StaffParticleSample(Particle.CLOUD, Vector(0.0, 0.12, 0.0), count = 10,
        spread = Vector(0.24, 0.18, 0.24), speed = 0.035),
    StaffParticleSample(Particle.CRIT, Vector(), count = 8,
        spread = Vector(0.20, 0.22, 0.20), speed = 0.04),
)

/**
 * Stable-identity stone chunks for the held boulder, its flight, and the one impact transition.
 * The packet displays are decorative; this geometry never writes to a world block.
 */
internal fun staffChannelParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
    secondary: Boolean = false,
): List<StaffDisplayPart> {
    if (spell != StaffSpell.GRAVITY) return emptyList()
    val pieces = listOf(
        Triple(Material.STONE, Vector3f(0f, 0f, 0f), 0.88f),
        Triple(Material.COBBLESTONE, Vector3f(-0.30f, 0.10f, -0.22f), 0.42f),
        Triple(Material.ANDESITE, Vector3f(0.28f, -0.16f, -0.26f), 0.40f),
        Triple(Material.COBBLESTONE, Vector3f(-0.16f, 0.30f, 0.20f), 0.38f),
        Triple(Material.STONE, Vector3f(0.18f, -0.31f, 0.18f), 0.40f),
        Triple(Material.ANDESITE, Vector3f(0.36f, 0.12f, 0.14f), 0.34f),
        Triple(Material.COBBLESTONE, Vector3f(-0.36f, -0.14f, 0.12f), 0.34f),
        Triple(Material.STONE, Vector3f(0.04f, 0.38f, -0.16f), 0.32f),
        Triple(Material.COBBLESTONE, Vector3f(-0.08f, -0.36f, -0.12f), 0.32f),
    )
    val safeAge = ageTicks.coerceAtLeast(0)
    val safeDuration = durationTicks.coerceAtLeast(2)
    val t = (safeAge.toDouble() / (safeDuration - 2)).coerceIn(0.0, 1.0)
    val fade = if (impact) (1.0 - t).coerceAtLeast(0.001) else 1.0
    val burstRadius = radius.takeIf(Double::isFinite)?.coerceIn(0.5, 5.0) ?: 1.6
    return pieces.mapIndexed { index, (material, base, size) ->
        val phase = safeAge * 0.035 + index * 0.63
        val radial = Vector3f(base)
        val center = if (impact) {
            radial.mul((0.7 + t * burstRadius * 0.58).toFloat())
        } else {
            radial.rotateY((sin(phase) * 0.13).toFloat())
        }
        val scale = (size * fade).toFloat()
        StaffDisplayPart(material, center, Vector3f(scale, scale * (0.82f + (index % 3) * 0.09f), scale),
            Quaternionf().rotationXYZ((cos(phase) * 0.16).toFloat(), phase.toFloat(), (sin(phase) * 0.12).toFloat()),
            visible = true, identity = "gravity-stone-$index")
    }
}
