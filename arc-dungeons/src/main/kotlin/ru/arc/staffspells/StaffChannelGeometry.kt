package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.block.data.BlockData
import org.joml.Quaternionf
import org.joml.Vector3f
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bounded native flame samples in caster-local coordinates; +Z is the current look direction. */
internal fun staffInfernoFrame(ageTicks: Int, reach: Double): List<StaffParticleSample> {
    val length = reach.takeIf(Double::isFinite)?.coerceIn(0.0, 10.0) ?: 8.0
    if (length < INFERNO_MUZZLE_CLEARANCE) return emptyList()
    val phase = ageTicks.coerceAtLeast(0) * 0.31
    val core = staffFade(0xFFD64A, 0xFFF6C5, 0.95f)
    val edge = staffFade(0xFF531B, 0xFFB52D, 0.8f)
    return buildList(44) {
        repeat(16) { i ->
            val t = (i + 1) / 16.0
            val z = INFERNO_MUZZLE_CLEARANCE + (length - INFERNO_MUZZLE_CLEARANCE) * t
            val width = 0.045 + 0.24 * t
            val angle = phase + i * 2.399963
            val x = sin(angle) * width
            val y = cos(angle * 1.13) * width * 0.72
            if (i % 2 == 0)
                add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(x * 0.35, y * 0.35, z), data = core))
            add(StaffParticleSample(Particle.FLAME, Vector(x, y, z), data = null))
            add(StaffParticleSample(Particle.FLAME,
                Vector(sin(angle + 1.8) * width * 1.65, cos(angle * 0.91 + 0.7) * width * 1.35, z), data = null))
        }
        repeat(4) { i ->
            // Keep the original first/last edge stations and spread four accents across the full reach.
            val t = 1.0 / 9.0 + i * (7.0 / 9.0) / 3.0
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

/** A bounded radial lift that folds inward, then descends into an expanding ground shock. */
internal fun staffGravityPulseFrame(ageTicks: Int, radius: Double): List<StaffParticleSample> {
    val safeAge = ageTicks.coerceAtLeast(0)
    if (safeAge > GRAVITY_PULSE_SHOCK_END_TICKS) return emptyList()
    val reach = radius.takeIf(Double::isFinite)?.coerceIn(3.0, 7.0) ?: 6.5
    val violet = staffFade(0x7354BF, 0xE5D8FF, 0.9f)
    return if (safeAge <= GRAVITY_PULSE_LIFT_TICKS) {
        val progress = safeAge.toDouble() / GRAVITY_PULSE_LIFT_TICKS
        val ring = reach * (1.0 - .50 * progress)
        val height = 0.12 + 1.35 * (1.0 - progress)
        buildList(GRAVITY_PULSE_PARTICLE_COUNT * 2) {
            repeat(GRAVITY_PULSE_PARTICLE_COUNT) { index ->
                val angle = index * 2.0 * PI / GRAVITY_PULSE_PARTICLE_COUNT + safeAge * 0.12
                val wobble = sin(safeAge * 0.28 + index * 1.7) * 0.12
                val x = cos(angle) * (ring + wobble)
                val z = sin(angle) * (ring + wobble)
                add(StaffParticleSample(Particle.BLOCK, Vector(x, height, z), count = 1,
                    spread = Vector(0.035, 0.035, 0.035), speed = 0.018,
                    data = Material.ANDESITE.createBlockData()))
                add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION,
                    Vector(x * 0.78, height + 0.08, z * 0.78), data = violet))
            }
        }
    } else {
        val progress = ((safeAge - GRAVITY_PULSE_LIFT_TICKS).toDouble() /
            (GRAVITY_PULSE_SHOCK_END_TICKS - GRAVITY_PULSE_LIFT_TICKS)).coerceIn(0.0, 1.0)
        val ring = reach * (GRAVITY_PULSE_SHOCK_START_FRACTION +
            (1.0 - GRAVITY_PULSE_SHOCK_START_FRACTION) * progress)
        buildList(GRAVITY_PULSE_PARTICLE_COUNT * 2) {
            repeat(GRAVITY_PULSE_PARTICLE_COUNT) { index ->
                val angle = index * 2.0 * PI / GRAVITY_PULSE_PARTICLE_COUNT + safeAge * 0.05
                val x = cos(angle) * ring
                val z = sin(angle) * ring
                add(StaffParticleSample(Particle.DUST_COLOR_TRANSITION,
                    Vector(x, 0.08 + (index % 2) * 0.045, z), data = violet))
                if (index % 2 == 0) add(StaffParticleSample(Particle.BLOCK,
                    Vector(x * 0.92, 0.18 + (1.0 - progress) * 1.2, z * 0.92), count = 1,
                    spread = Vector(0.03, 0.03, 0.03), speed = 0.035,
                    data = Material.COBBLESTONE.createBlockData()))
            }
        }
    }
}

/** Target-contact accent: a small, readable chip-and-dust pop used only after an accepted slam hit. */
internal fun staffGravityPulseContactFrame() = listOf(
    StaffParticleSample(Particle.BLOCK, Vector(), count = 8, spread = Vector(0.16, 0.10, 0.16),
        speed = 0.045, data = Material.COBBLESTONE.createBlockData()),
    StaffParticleSample(Particle.CLOUD, Vector(0.0, 0.10, 0.0), count = 5,
        spread = Vector(0.16, 0.08, 0.16), speed = 0.018),
    StaffParticleSample(Particle.CRIT, Vector(0.0, 0.16, 0.0), count = 5,
        spread = Vector(0.12, 0.16, 0.12), speed = 0.03),
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
    if (secondary) return staffGravityPulseParts(ageTicks, durationTicks, radius)
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

/** A low pressure collar converges around the terrain depression; the center remains clear. */
internal fun staffGravityPulseParts(ageTicks: Int, durationTicks: Int, radius: Double): List<StaffDisplayPart> {
    if (ageTicks < 0 || ageTicks >= durationTicks) return emptyList()
    val reach = radius.takeIf(Double::isFinite)?.coerceIn(3.0, 7.0) ?: 6.5
    val progress = (ageTicks / 8.0).coerceIn(0.0, 1.0)
    val ring = reach * (1.0 - progress) + 3.85 * progress
    val fade = ((durationTicks - 2 - ageTicks) / 8.0).coerceIn(.001, 1.0)
    return List(16) { index ->
        val a0 = index * 2.0 * PI / 16
        val a1 = (index + 1) * 2.0 * PI / 16
        val from = Vector3f((cos(a0) * ring).toFloat(), .07f, (sin(a0) * ring).toFloat())
        val to = Vector3f((cos(a1) * ring).toFloat(), .07f, (sin(a1) * ring).toFloat())
        val delta = Vector3f(to).sub(from)
        StaffDisplayPart(if (index % 4 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS,
            Vector3f(from).add(to).mul(.5f),
            Vector3f((.14 * fade).toFloat(), (.10 * fade).toFloat(), (delta.length() * .94 * fade).toFloat()),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()),
            identity = "gravity-pulse-pressure-$index")
    }
}

private const val GRAVITY_PULSE_PARTICLE_COUNT = 12
private const val GRAVITY_PULSE_LIFT_TICKS = 8
private const val GRAVITY_PULSE_SHOCK_END_TICKS = 20
private const val GRAVITY_PULSE_INWARD_FRACTION = 0.82
private const val GRAVITY_PULSE_LIFT_HEIGHT = 2.1
private const val GRAVITY_PULSE_SHOCK_START_FRACTION = 0.18
