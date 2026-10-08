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

internal fun blendStaffParts(parts: List<StaffDisplayPart>, previous: List<StaffDisplayPart>, age: Int): List<StaffDisplayPart> {
    val blend = smooth(age / 4.0).toFloat()
    return parts.mapIndexed { index, part ->
        previous.getOrNull(index)?.let { old -> part.copy(
            center = Vector3f(old.center).lerp(part.center, blend),
            scale = Vector3f(old.scale).lerp(part.scale, blend),
            rotation = Quaternionf(old.rotation).slerp(part.rotation, blend),
        ) } ?: part
    }
}

/** Pure local cuboid geometry shared by previews and the packet-display runtime. */
internal fun staffDisplayParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
): List<StaffDisplayPart> {
    val parts = when (spell) {
    StaffSpell.CHAIN -> chainDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.MARK -> markDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.FROST -> frostDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.LANCE -> lanceDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.EMBER -> emberDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.NOVA -> novaDisplayParts(ageTicks, durationTicks, radius)
    }
    val tracked = !impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
    val birth = smooth(ageTicks / 4.0)
    val lastFrame = (durationTicks - StaffSpellDisplayEffects.FRAME_TICKS).coerceAtLeast(1)
    val fade = if (tracked) 1.0 else smooth((lastFrame - ageTicks) / 6.0)
    val visibility = (birth * fade).coerceAtLeast(0.001).toFloat()
    return parts.map { part -> part.copy(
        center = if (spell == StaffSpell.FROST) Vector3f(part.center).apply { y *= visibility } else part.center,
        scale = Vector3f(part.scale).mul(visibility),
    ) }
}

private fun chainDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(length, 32.0, 8.0)
    val width = extent(radius, 2.0, 0.45)
    val muzzle = minOf(0.85, reach * 0.5)
    val parts = ArrayList<StaffDisplayPart>(32)
    val boltWidth = (width * 0.26).coerceIn(0.13, 0.18)
    val points = (0..18).map { index ->
        val t = index / 18.0
        val taper = if (index == 0 || index == 18) 0.0 else sin(PI * t)
        val side = if (index % 2 == 0) -1.0 else 1.0
        val phaseJitter = sin(index * 1.7) * width * 0.08 * taper
        val muzzleOffset = (1.0 - reach * t / 3.0).coerceAtLeast(0.0)
        Vector3f(
            (side * width * 0.45 * taper + phaseJitter + 0.32 * muzzleOffset).toFloat(),
            (cos(index * 1.7) * width * 0.14 * taper - 0.24 * muzzleOffset).toFloat(),
            (muzzle + (reach - muzzle) * t).toFloat(),
        )
    }
    repeat(18) { index ->
        val start = points[index]
        val end = points[index + 1]
        val delta = Vector3f(end).sub(start)
        val segmentLength = delta.length()
        val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
        val center = Vector3f(start).add(end).mul(0.5f)
        val stemWidth = when (index) { 0 -> 0.045; 1 -> 0.09; else -> boltWidth }
        parts += StaffDisplayPart(
            if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            center,
            Vector3f(stemWidth.toFloat(), stemWidth.toFloat(), segmentLength),
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
        val angle = index * PI / 4.0 + phase * 0.18
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
    val opening = if (impact) 1.0 else smooth(ageTicks / 8.0)
    val release = if (impact) 1.0 - (1.0 - phase) * (1.0 - phase) else 0.0
    val reach = extent(radius, 8.0, 1.3)
    val rx = if (impact) 0.48 + reach * release * 0.75 else 0.38 + opening * 0.20
    val ry = if (impact) 0.92 + release * 0.75 else 0.68 + opening * 0.30
    val parts = ArrayList<StaffDisplayPart>(28)
    // A vertical tear opens around one fixed axis. No fast orbit or phase-reset spin.
    repeat(16) { index ->
        val angle = index * PI / 8.0
        val next = angle + PI / 8.0
        val start = Vector3f((cos(angle) * rx).toFloat(), (sin(angle) * ry).toFloat(), 0f)
        val end = Vector3f((cos(next) * rx).toFloat(), (sin(next) * ry).toFloat(), 0f)
        val material = if (index % 4 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS
        val link = link(material, start, end, 0.075, 0.12)
        parts += if (impact) link.copy(
            center = Vector3f(link.center).add(0f, (-0.4 * release * release).toFloat(),
                (sin(angle * 3) * release * 0.55).toFloat()),
            scale = Vector3f(link.scale).mul((1.0 - phase * 0.72).toFloat()),
        ) else link
    }
    repeat(8) { index ->
        val side = if (index % 2 == 0) -1.0 else 1.0
        val y = (index / 2 - 1.5) * 0.34
        val x = side * (if (impact) 0.22 + reach * release * 0.65 else 0.20 + 0.10 * opening)
        parts += part(Material.MAGENTA_STAINED_GLASS, x,
            y * (1.0 + release) - release * release * 0.35,
            side * release * 0.42, 0.07, 0.24 * (1.0 - phase * if (impact) 0.6 else 0.0),
            0.09, roll = -side * (0.30 + release * 0.4))
    }
    repeat(4) { index ->
        val y = (index - 1.5) * 0.24
        parts += part(Material.CRYING_OBSIDIAN, sin(index * 1.8) * 0.04 * (1.0 + release),
            y - release * release * 0.25, 0.025, 0.07 * (1.0 - release * 0.85),
            0.24 * (1.0 - release * 0.8), 0.06)
    }
    return parts
}

private fun frostDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val reach = extent(length, 14.0, 7.0)
    val halfAngle = atan2(extent(radius, 96.0, 8.3), reach)
    val parts = ArrayList<StaffDisplayPart>(40)
    // Stable rows rise from their own ground contact, then melt; new rows lead the wave.
    repeat(5) { row ->
        val distance = reach * (row + 0.65) / 5.0
        val arrival = 2.0 + distance / reach * 12.0
        val local = ageTicks - arrival
        val grow = smooth((local + 2.0) / 3.0)
        val melt = 1.0 - smooth((local - 4.0) / 6.0)
        val height = (0.72 + row * 0.18) * grow * melt
        repeat(7) { column ->
            val angle = (column / 3.0 - 1.0) * halfAngle
            val x = sin(angle) * distance
            val z = cos(angle) * distance
            val h = (height * (0.82 + 0.18 * cos(column * 1.7))).coerceAtLeast(0.001)
            parts += part(if ((row + column) % 3 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                x, h * 0.5, z, (0.28 + row * 0.045) * (0.2 + grow * 0.8), h,
                0.44 + row * 0.06, yaw = -angle, pitch = -0.10 * grow)
        }
        val gleam = (grow * melt).coerceAtLeast(0.001)
        parts += part(Material.LIGHT_BLUE_STAINED_GLASS, 0.0, 0.045, distance,
            (0.35 + distance * 0.3) * gleam, 0.045 * gleam, 0.14 * gleam)
    }
    return parts
}

private fun lanceDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val reach = extent(length, 32.0, 8.0)
    val launch = smooth(ageTicks / 4.0)
    val front = minOf(reach, 0.8 + (reach - 0.8).coerceAtLeast(0.0) * launch)
    val muzzle = minOf(0.85, front * 0.5)
    val shaftLength = front - muzzle
    val width = extent(radius, 1.0, 0.75)
    val parts = ArrayList<StaffDisplayPart>(25)
    repeat(12) { index ->
        fun point(t: Double) = Vector3f((0.32 * (1.0 - t)).toFloat(),
            (-0.24 * (1.0 - t)).toFloat(), (muzzle + shaftLength * t).toFloat())
        val thinning = (1.0 - progress(ageTicks, durationTicks) * 0.45)
        val thickness = (0.035 + index / 11.0 * 0.035) * thinning
        val segment = link(if (index % 4 == 0) Material.GOLD_BLOCK else Material.YELLOW_STAINED_GLASS,
            point(index / 12.0), point((index + 1) / 12.0), thickness, thickness)
        parts += segment.copy(scale = Vector3f(segment.scale).apply { z *= 0.995f })
    }
    // Four swept wings build a recognisable spear head instead of a rotating rod.
    repeat(4) { index ->
        val angle = index * PI / 2.0 + PI / 4.0
        val start = Vector3f(0f, 0f, front.toFloat())
        val end = Vector3f((cos(angle) * width * 0.55).toFloat(),
            (sin(angle) * width * 0.55).toFloat(), (front - 0.8).coerceAtLeast(0.0).toFloat())
        parts += link(Material.GOLD_BLOCK, start, end, 0.10, 0.10)
        parts += part(Material.WHITE_STAINED_GLASS,
            cos(angle) * width * 0.2, sin(angle) * width * 0.2, front - 0.2,
            0.07, 0.07, 0.50, roll = angle)
    }
    parts += part(Material.SEA_LANTERN, 0.0, 0.0, front, 0.14, 0.14, 0.40, roll = PI / 4.0)
    repeat(4) { index ->
        val side = if (index % 2 == 0) -1.0 else 1.0
        val z = front * (0.25 + index * 0.16)
        parts += part(Material.YELLOW_STAINED_GLASS, side * width * 0.18, 0.0, z,
            0.055, 0.055, (front * 0.11).coerceAtLeast(0.001))
    }
    return parts
}

private fun emberDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val width = extent(radius, 8.0, 0.85)
    val flightWidth = if (impact) 0.85 else width
    val core = if (impact) (1.0 - phase * 2.5).coerceAtLeast(0.001) else 1.0
    val parts = ArrayList<StaffDisplayPart>(14)
    parts += part(Material.GOLD_BLOCK, 0.0, 0.0, 0.0,
        0.24 * flightWidth * core, 0.24 * flightWidth * core, 0.40 * flightWidth * core, roll = PI / 4)
    parts += part(Material.ORANGE_STAINED_GLASS, 0.0, 0.0, -0.08,
        0.44 * flightWidth * core, 0.44 * flightWidth * core, 0.64 * flightWidth * core, roll = PI / 4)
    repeat(12) { index ->
        if (!impact) {
            val row = index / 3
            val angle = index % 3 * PI * 2.0 / 3.0
            val taper = 1.0 - row * 0.20
            parts += part(if (row == 0) Material.YELLOW_STAINED_GLASS else Material.ORANGE_STAINED_GLASS,
                cos(angle) * 0.14 * taper, sin(angle) * 0.14 * taper,
                -0.40 - row * 0.40, 0.10 * taper, 0.10 * taper, 0.58 * taper)
        } else {
            val y = 1.0 - 2.0 * (index + 0.5) / 12.0
            val radial = kotlin.math.sqrt(1.0 - y * y)
            val angle = index * 2.399963229728653
            val distance = 0.16 + width * (1.0 - (1.0 - phase) * (1.0 - phase))
            val shard = (0.22 * (1.0 - phase * 0.75)).coerceAtLeast(0.001)
            parts += part(if (index % 3 == 0) Material.GOLD_BLOCK else Material.ORANGE_STAINED_GLASS,
                cos(angle) * radial * distance, y * distance - phase * phase * 0.6,
                sin(angle) * radial * distance, shard, shard, shard * 1.8,
                yaw = angle, pitch = -y * 0.8)
        }
    }
    return parts
}

private fun novaDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double): List<StaffDisplayPart> {
    val reach = extent(radius, 16.0, 8.0).coerceAtLeast(1.8)
    val travel = ((ageTicks - 2) / 16.0).coerceIn(0.0, 1.0)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val crest = sin(PI * travel) * 0.65
    val parts = ArrayList<StaffDisplayPart>(48)
    repeat(24) { index ->
        val angle = index * PI / 12.0
        val next = angle + PI / 12.0
        val segment = link(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.CYAN_STAINED_GLASS,
            Vector3f((cos(angle) * front).toFloat(), (0.12 + crest).toFloat(), (sin(angle) * front).toFloat()),
            Vector3f((cos(next) * front).toFloat(), (0.12 + crest).toFloat(), (sin(next) * front).toFloat()),
            0.12, 0.24 + crest * 0.3)
        // Recess the joints slightly so adjacent glass top faces cannot fight for depth.
        parts += segment.copy(scale = Vector3f(segment.scale).apply { z = (z - 0.025f).coerceAtLeast(0.001f) })
    }
    repeat(12) { index ->
        val angle = index * PI / 6.0
        val wake = (front - 0.65).coerceAtLeast(1.2)
        parts += part(Material.LIME_STAINED_GLASS, cos(angle) * wake, 0.09,
            sin(angle) * wake, 0.10, 0.10, (wake * PI / 6.0 * 0.65).coerceAtLeast(0.1), yaw = -angle)
        parts += part(Material.WHITE_STAINED_GLASS, cos(angle) * front,
            0.30 + crest * 1.1, sin(angle) * front, 0.06, 0.22 + crest * 0.4, 0.18,
            yaw = -angle, pitch = -0.35)
    }
    return parts
}

/** The controller and ground geometry share this outward front; no oscillation or rebound. */
internal fun staffWaveFront(ageTicks: Int, range: Double, travelTicks: Int) =
    range.coerceAtLeast(0.0) * ((ageTicks - 2).toDouble() / travelTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)

private fun smooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private fun link(material: Material, start: Vector3f, end: Vector3f, width: Double, height: Double): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(0.001f)
    val rotation = if (delta.lengthSquared() > 0.000001f)
        Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize()) else Quaternionf()
    return StaffDisplayPart(material, Vector3f(start).add(end).mul(0.5f),
        Vector3f(width.toFloat(), height.toFloat(), length), rotation)
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
