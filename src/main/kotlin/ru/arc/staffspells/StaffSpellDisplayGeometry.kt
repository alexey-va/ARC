package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class StaffDisplayPart(
    val material: Material,
    val center: Vector3f,
    val scale: Vector3f,
    val rotation: Quaternionf,
)

/** Pure local cuboid geometry shared by previews and the packet-display runtime. */
internal fun staffDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
): List<StaffDisplayPart> = when (spell) {
    StaffSpell.CHAIN -> chainDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.MARK -> markDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.FROST -> frostDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.LANCE -> lanceDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.EMBER -> emberDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.NOVA -> novaDisplayParts(ageTicks, durationTicks, length, radius, impact)
}

private fun chainDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(length, 32.0, 8.0)
    val width = extent(radius, 2.0, 0.45)
    val parts = ArrayList<StaffDisplayPart>(32)
    val boltWidth = (width * 0.26).coerceIn(0.13, 0.18)
    val points = (0..18).map { index ->
        val t = index / 18.0
        val taper = if (index == 0 || index == 18) 0.0 else sin(PI * t)
        val side = if (index % 2 == 0) -1.0 else 1.0
        val phaseJitter = sin(phase * PI * 2.0 + index * 1.7) * width * 0.08 * taper
        Vector3f(
            (side * width * 0.45 * taper + phaseJitter).toFloat(),
            (cos(index * 1.7 + phase * PI * 2.0) * width * 0.14 * taper).toFloat(),
            (reach * t).toFloat(),
        )
    }
    repeat(18) { index ->
        val start = points[index]
        val end = points[index + 1]
        val delta = Vector3f(end).sub(start)
        val segmentLength = delta.length()
        val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
        val center = Vector3f(start).add(end).mul(0.5f)
        parts += StaffDisplayPart(
            if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            center,
            Vector3f(boltWidth.toFloat(), boltWidth.toFloat(), segmentLength),
            rotation,
        )
    }
    for (anchor in listOf(4, 9, 14)) {
        val point = points[anchor]
        for (side in listOf(-1.0, 1.0)) {
            parts += part(Material.CYAN_STAINED_GLASS,
                point.x.toDouble() + side * width * 0.64,
                point.y.toDouble() + side * width * 0.24,
                point.z.toDouble() + reach * 0.035,
                boltWidth * 0.75, boltWidth * 0.75, maxOf(0.16, reach * 0.035), yaw = side * 0.45, pitch = side * -0.32)
        }
    }
    val headRadius = width * (if (impact) 0.78 + phase * 0.30 else 0.50)
    repeat(8) { index ->
        val angle = index * PI / 4.0 + phase * PI
        parts += part(
            if (index % 2 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            cos(angle) * headRadius, sin(angle) * headRadius, reach,
            boltWidth, boltWidth, 0.34,
            yaw = angle,
            pitch = sin(angle) * 0.45,
        )
    }
    return parts
}

private fun markDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(radius, 3.3, 1.3)
    val expansion = if (impact) 0.55 + phase * 0.55 else 0.72 + phase * 0.18
    val spin = phase * PI * 2.0
    val parts = ArrayList<StaffDisplayPart>(32)
    repeat(12) { index ->
        val angle = index * PI / 6.0 + spin
        val ringRadius = reach * expansion
        parts += part(
            if (index % 3 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS,
            cos(angle) * ringRadius, 0.18, sin(angle) * ringRadius,
            0.30, 0.18, 0.36, yaw = -angle,
        )
        if (index % 2 == 0) {
            parts += part(Material.MAGENTA_STAINED_GLASS,
                cos(angle) * ringRadius * 0.72, 0.42, sin(angle) * ringRadius * 0.72,
                0.20, 0.38, 0.20, yaw = angle + spin)
        }
    }
    parts += part(Material.AMETHYST_BLOCK, 0.0, 0.46, 0.0, 0.30, 0.42, 0.30, yaw = -spin)
    parts += part(Material.PURPLE_STAINED_GLASS, 0.0, 0.82, 0.0, 0.16, 0.26, 0.16, yaw = spin * 1.4)
    if (impact) repeat(6) { index ->
        val angle = index * PI / 3.0 + spin
        parts += part(Material.AMETHYST_BLOCK,
            cos(angle) * reach * (0.3 + phase * 0.35), 0.65 + 0.15 * sin(angle),
            sin(angle) * reach * (0.3 + phase * 0.35),
            0.16, 0.30, 0.16, yaw = angle, pitch = 0.45 * cos(angle))
    }
    return parts
}

private fun frostDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(length, 8.0, 6.0)
    val width = extent(radius, 8.0, 1.3) * (0.68 + phase * 0.32)
    val front = reach * (if (impact) 0.76 + phase * 0.24 else 0.58 + phase * 0.42)
    val parts = ArrayList<StaffDisplayPart>(24)
    repeat(18) { index ->
        val row = index / 6
        val column = index % 6
        val across = column / 5.0 * 2.0 - 1.0
        val t = 0.34 + row * 0.28
        val angle = across * atan2(width, reach)
        val z = cos(angle) * front * t
        val x = sin(angle) * front * t
        val material = when ((row + column) % 3) {
            0 -> Material.BLUE_ICE
            1 -> Material.PACKED_ICE
            else -> Material.CYAN_STAINED_GLASS
        }
        parts += part(material, x, 0.10 + abs(across) * 0.12, z,
            0.24, 0.42 + 0.12 * t, (front * 0.20).coerceIn(0.70, 1.90),
            yaw = atan2(x, maxOf(0.01, z)), pitch = -0.18 + across * 0.22)
    }
    repeat(4) { index ->
        val side = if (index % 2 == 0) -1.0 else 1.0
        parts += part(Material.SEA_LANTERN,
            side * width * (0.18 + phase * 0.1), 0.12 + (index / 2) * 0.4, front,
            0.16, 0.26, 0.46, yaw = side * 0.5, pitch = side * 0.15)
    }
    return parts
}

private fun lanceDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(length, 32.0, 8.0)
    val width = extent(radius, 1.0, 0.22)
    val spin = phase * PI * 3.0
    val shaftWidth = (width * 0.42).coerceAtLeast(0.10)
    val ribWidth = (width * 0.28).coerceAtLeast(0.12)
    val parts = ArrayList<StaffDisplayPart>(24)
    parts += part(Material.GOLD_BLOCK, 0.0, 0.0, reach * 0.43,
        shaftWidth, shaftWidth, reach * 0.86, roll = spin)
    repeat(8) { index ->
        val t = (index + 0.5) / 8.0
        val angle = spin + t * PI * 5.0
        parts += part(
            if (index % 2 == 0) Material.YELLOW_STAINED_GLASS else Material.GOLD_BLOCK,
            cos(angle) * width * 0.32, sin(angle) * width * 0.32, reach * (0.12 + t * 0.68),
            ribWidth, ribWidth, maxOf(0.18, reach * 0.09), roll = angle,
        )
    }
    repeat(4) { index ->
        val angle = spin * 0.3 + index * PI / 2.0
        parts += part(Material.GOLD_BLOCK,
            cos(angle) * width * 0.78, sin(angle) * width * 0.78, reach * 0.79,
            (width * 0.60).coerceAtLeast(0.14), (width * 0.32).coerceAtLeast(0.10),
            (width * 0.32).coerceAtLeast(0.10), yaw = angle)
    }
    parts += part(Material.YELLOW_STAINED_GLASS, 0.0, 0.0, reach * 0.94,
        ribWidth * 0.9, ribWidth * 0.9, reach * 0.12, roll = spin)
    if (impact) repeat(8) { index ->
        val angle = index * PI / 4.0 + spin
        val flare = width * (0.32 + phase * 0.36)
        parts += part(Material.GOLD_BLOCK, cos(angle) * flare, sin(angle) * flare, reach,
            ribWidth, ribWidth, 0.28, yaw = angle, pitch = sin(angle) * 0.4)
    }
    return parts
}

private fun emberDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val width = extent(radius, 2.0, 0.7)
    val spin = phase * PI * 4.0
    // The renderer moves the display anchor with the projectile/collision point.
    // Keep the core local to that anchor and let the wake trail extend behind it.
    val centerZ = 0.0
    val burst = if (impact) phase else 0.0
    val parts = ArrayList<StaffDisplayPart>(24)
    parts += part(Material.MAGMA_BLOCK, 0.0, 0.0, centerZ, width * 0.48, width * 0.48, width * 0.48, yaw = spin)
    val orbitSize = (width * 0.28).coerceAtLeast(0.18)
    repeat(8) { index ->
        val angle = spin + index * PI / 4.0
        val orbit = width * (0.42 + burst * 0.45)
        parts += part(
            if (index % 2 == 0) Material.ORANGE_STAINED_GLASS else Material.GOLD_BLOCK,
            cos(angle) * orbit, sin(angle) * orbit, centerZ + 0.12 * sin(angle * 2.0),
            orbitSize, orbitSize, orbitSize * 1.15, yaw = angle, pitch = 0.25 * cos(angle),
        )
    }
    if (!impact) repeat(6) { index ->
        val trailPhase = index / 6.0
        val trailZ = centerZ - width * (0.6 + trailPhase * 1.5)
        val angle = spin * 0.6 + index * 2.399963229728653
        parts += part(Material.NETHERRACK,
            cos(angle) * width * (0.25 + trailPhase * 0.1), sin(angle) * width * 0.25, trailZ,
            orbitSize * 0.82, orbitSize * 0.82, orbitSize, yaw = angle)
    } else repeat(12) { index ->
        val angle = index * PI / 6.0 + spin
        val spread = width * (0.55 + burst * 0.72)
        val shard = (width * 0.30).coerceAtLeast(0.18)
        parts += part(
            if (index % 3 == 0) Material.MAGMA_BLOCK else Material.ORANGE_STAINED_GLASS,
            cos(angle) * spread, sin(angle) * spread, centerZ + 0.15 * sin(angle * 2.0),
            shard, shard, shard * 1.2, yaw = angle, pitch = 0.35 * cos(angle),
        )
    }
    return parts
}

private fun novaDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val height = length.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(2.0, 4.0) ?: 4.0
    val reach = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.4, 3.3) ?: 2.2
    val flare = if (impact) phase else 0.0
    val spin = phase * PI * 2.0
    val parts = ArrayList<StaffDisplayPart>(32)

    // Two broad counter-rotating ribbons widen from a narrow foot into the crown.
    // Long tangential cuboids overlap slightly so the wind reads as ribbons, not dots.
    val ribbonSegments = 8
    val ribbonTwist = PI * 1.25
    repeat(2) { strand ->
        repeat(ribbonSegments) { index ->
            val fraction = (index + 0.5) / ribbonSegments
            val strandSpin = if (strand == 0) spin else -spin
            val angle = strand * PI + strandSpin + fraction * ribbonTwist
            val radial = reach * (0.07 + fraction * 0.90) * (1.0 + flare * 0.04)
            val y = 0.18 + height * fraction
            val material = when ((index + strand * 2) % 4) {
                0 -> Material.SEA_LANTERN
                1 -> Material.EMERALD_BLOCK
                2 -> Material.CYAN_STAINED_GLASS
                else -> Material.PRISMARINE_BRICKS
            }
            val tangentLength = (radial * ribbonTwist / (ribbonSegments - 1) * 1.14).coerceIn(0.70, 2.10)
            parts += part(
                material,
                cos(angle) * radial, y, sin(angle) * radial,
                0.28 + fraction * 0.09, 0.52 + fraction * 0.08, tangentLength,
                yaw = -angle,
                roll = 0.12 * sin(angle * 1.7),
            )
        }
    }

    // Keep the central crystals above the caster's head so the first-person view stays open.
    parts += part(Material.SEA_LANTERN, 0.0, height * 0.77, 0.0, 0.42, 0.72, 0.42, yaw = spin)
    parts += part(Material.EMERALD_BLOCK, 0.0, height * 0.94, 0.0, 0.30, 0.48, 0.30, yaw = -spin * 1.2)
    repeat(6) { index ->
        val angle = spin * 0.55 + index * PI / 3.0
        val crownRadius = reach * (0.88 + flare * 0.08)
        val material = when (index % 3) {
            0 -> Material.SEA_LANTERN
            1 -> Material.CYAN_STAINED_GLASS
            else -> Material.EMERALD_BLOCK
        }
        parts += part(
            material,
            cos(angle) * crownRadius, height * (0.86 + 0.035 * sin(angle)), sin(angle) * crownRadius,
            0.34, 0.46, 2.12,
            yaw = -angle,
            roll = 0.10 * cos(angle),
        )
    }

    // The impact wave expands and brightens, then contracts into translucent shards.
    val wave = if (impact) sin(PI * phase).coerceAtLeast(0.0) else 0.48
    val baseRadius = reach * (0.20 + wave * 0.76)
    val waveScale = 0.12 + wave * 0.88
    repeat(8) { index ->
        val angle = spin * 0.35 + index * PI / 4.0
        val tangentLength = (baseRadius * PI / 4.0 * 1.06).coerceIn(0.48, 2.45) * waveScale
        val material = when {
            impact && wave < 0.36 -> Material.CYAN_STAINED_GLASS
            index % 3 == 0 -> Material.SEA_LANTERN
            index % 2 == 0 -> Material.PRISMARINE_BRICKS
            else -> Material.EMERALD_BLOCK
        }
        parts += part(
            material,
            cos(angle) * baseRadius, 0.10 + 0.035 * sin(angle * 2.0 + spin), sin(angle) * baseRadius,
            0.26 * waveScale, 0.14 * waveScale, tangentLength,
            yaw = -angle,
        )
    }

    return parts
}

private fun progress(ageTicks: Int, durationTicks: Int) =
    (ageTicks.coerceAtLeast(0).toDouble() /
        (((durationTicks - 1).coerceAtLeast(0) / StaffSpellDisplayEffects.FRAME_TICKS) * StaffSpellDisplayEffects.FRAME_TICKS).coerceAtLeast(1))
        .coerceIn(0.0, 1.0)

private fun extent(value: Double, max: Double, fallback: Double) =
    value.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.1, max) ?: fallback

private fun part(
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    sx: Double,
    sy: Double,
    sz: Double,
    yaw: Double = 0.0,
    pitch: Double = 0.0,
    roll: Double = 0.0,
) = StaffDisplayPart(
    material,
    Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
    Vector3f(sx.toFloat(), sy.toFloat(), sz.toFloat()),
    Quaternionf().rotateY(yaw.toFloat()).rotateX(pitch.toFloat()).rotateZ(roll.toFloat()),
)
