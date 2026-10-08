package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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
    secondary: Boolean = false,
): List<StaffDisplayPart> {
    val parts = when (spell) {
    StaffSpell.CHAIN -> chainDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.MARK -> if (secondary) singularityDisplayParts(ageTicks, durationTicks, radius, impact)
        else markDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.FROST -> if (secondary) frostNovaDisplayParts(ageTicks, durationTicks, radius)
        else frostDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.LANCE -> lanceDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.EMBER -> emberDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.NOVA -> if (secondary) tidalCrestDisplayParts(ageTicks, durationTicks, length, radius)
        else novaDisplayParts(ageTicks, durationTicks, radius)
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
    val reach = extent(length, 48.0, 8.0)
    val width = extent(radius, 2.0, 0.45)
    val parts = ArrayList<StaffDisplayPart>(32)
    val boltWidth = (width * 0.26).coerceIn(0.13, 0.18)
    val points = (0..18).map { index ->
        val t = index / 18.0
        val taper = if (index == 0 || index == 18) 0.0 else sin(PI * t)
        val side = if (index % 2 == 0) -1.0 else 1.0
        val phaseJitter = sin(index * 1.7) * width * 0.08 * taper
        Vector3f(
            (side * width * 0.45 * taper + phaseJitter).toFloat(),
            (cos(index * 1.7) * width * 0.14 * taper).toFloat(),
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

/** A live bolt only contains the route the controller has already traveled. */
internal fun staffLightningTrailParts(points: List<Vector3f>, fade: Double = 1.0): List<StaffDisplayPart> {
    val visibility = fade.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    if (visibility <= 0.0) return emptyList()

    val trail = ArrayList<Vector3f>(17)
    points.forEach { point ->
        if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite() &&
            (trail.isEmpty() || trail.last().distanceSquared(point) > 0.0001f)) {
            trail += Vector3f(point)
        }
    }
    val traveled = trail.takeLast(17)
    if (traveled.isEmpty()) return emptyList()

    val sides = ArrayList<Vector3f>(traveled.size)
    traveled.indices.forEach { index ->
        val before = traveled[(index - 1).coerceAtLeast(0)]
        val after = traveled[(index + 1).coerceAtMost(traveled.lastIndex)]
        val tangent = Vector3f(after).sub(before)
        if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
        val side = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
        if (side.lengthSquared() < 0.000001f) side.set(tangent).cross(Vector3f(1f, 0f, 0f))
        if (side.lengthSquared() < 0.000001f) side.set(1f, 0f, 0f) else side.normalize()
        sides.lastOrNull()?.takeIf { side.dot(it) < 0f }?.let { side.negate() }
        sides += side
    }

    val segmentParts = ArrayList<StaffDisplayPart>(32)
    val segmentCount = (traveled.size - 1).coerceAtLeast(0)
    repeat(segmentCount) { index ->
        val age = index.toDouble() / (segmentCount - 1).coerceAtLeast(1)
        val width = (0.10 + 0.06 * age) * visibility
        val secondaryWidth = (0.10 + 0.04 * age) * visibility
        val zig = (if (index % 2 == 0) 0.09 else -0.09) * visibility
        val nextZig = -zig
        val start = Vector3f(traveled[index]).fma(zig.toFloat(), sides[index])
        val end = Vector3f(traveled[index + 1]).fma(nextZig.toFloat(), sides[index + 1])
        val secondStart = Vector3f(traveled[index]).fma(-zig.toFloat() * 0.55f, sides[index])
            .add(0f, 0.045f * visibility.toFloat(), 0f)
        val secondEnd = Vector3f(traveled[index + 1]).fma(-nextZig.toFloat() * 0.55f, sides[index + 1])
            .add(0f, 0.045f * visibility.toFloat(), 0f)
        segmentParts += shortenedLink(if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            start, end, width, width * 0.85, width * 0.15)
        segmentParts += shortenedLink(if (index % 4 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
            secondStart, secondEnd, secondaryWidth, secondaryWidth * 0.75, secondaryWidth * 0.15)
    }

    val branchIndices = when {
        segmentCount >= 6 -> listOf(segmentCount / 4, segmentCount / 2, segmentCount * 3 / 4)
        segmentCount >= 3 -> listOf(segmentCount / 3, segmentCount * 2 / 3)
        else -> emptyList()
    }.distinct().filter { it in 1 until traveled.lastIndex }
    val branchParts = branchIndices.mapIndexed { branch, index ->
        val direction = Vector3f(sides[index]).mul(if (branch % 2 == 0) 1f else -1f)
        val end = Vector3f(traveled[index])
            .fma(0.28f * visibility.toFloat(), direction)
            .add(0f, 0.16f * visibility.toFloat(), 0f)
        link(Material.CYAN_STAINED_GLASS, traveled[index], end,
            0.085 * visibility, 0.075 * visibility)
    }

    val head = traveled.last()
    val headWidth = 0.20 * visibility
    val headParts = ArrayList<StaffDisplayPart>(3)
    headParts += part(Material.SEA_LANTERN, head.x.toDouble(), head.y.toDouble(), head.z.toDouble(),
        headWidth, headWidth, headWidth)
    listOf(-1.0, 1.0).forEach { side ->
        val point = Vector3f(head).fma((0.075 * visibility * side).toFloat(), sides.last())
            .add(0f, (0.035 * visibility).toFloat(), 0f)
        headParts += part(Material.WHITE_STAINED_GLASS, point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
            0.075 * visibility, 0.075 * visibility, 0.16 * visibility, yaw = side * 0.55)
    }
    // Reserve branch slots and keep the moving head at indices 0–2 so packet IDs never swap roles.
    val branches = (0 until 3).map { index -> branchParts.getOrNull(index) ?: link(
        Material.CYAN_STAINED_GLASS, head, Vector3f(head).add(0.001f, 0f, 0f), 0.001, 0.001) }
    return headParts + branches + segmentParts
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

private fun singularityDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val opening = if (impact) smooth((phase / 0.42).coerceAtMost(1.0)) else smooth(ageTicks / 8.0)
    val collapse = if (impact) smooth((phase - 0.56) / 0.44) else 0.0
    val reach = extent(radius, 8.0, 3.5)
    val ringRadius = 0.32 + (reach - 0.32) * opening * (1.0 - collapse)
    val core = 0.70 + collapse * 0.05
    val swirlY = 1.6
    val displayRingRadius = ringRadius * 0.65
    val parts = ArrayList<StaffDisplayPart>(34)

    // The dark core stays pinned to the target while a flat inner ring and tilted outer ring expand and collapse.
    parts += part(Material.OBSIDIAN, 0.0, swirlY, 0.0,
        core * 1.15, core * 0.70, core * 1.15, roll = PI / 4)
    parts += part(Material.CRYING_OBSIDIAN, 0.0, swirlY - 0.28, 0.0,
        core * 1.7, 0.07, core * 1.7)
    val outerTilt = Quaternionf().rotationX(Math.toRadians(35.0).toFloat())
    repeat(2) { layer ->
        val ring = displayRingRadius * (if (layer == 0) 0.64 else 1.0)
        val y = swirlY - (1 - layer) * 0.075
        repeat(12) { index ->
            val angle = index * PI / 6.0 + layer * PI / 12.0
            val material = when {
                (index + layer) % 4 == 0 -> Material.AMETHYST_BLOCK
                (index + layer) % 3 == 0 -> Material.CRYING_OBSIDIAN
                else -> Material.PURPLE_STAINED_GLASS
            }
            val link = groundRingLink(material, ring, angle, angle + PI / 6.0, y,
                if (layer == 0) 0.075 else 0.09)
            if (layer == 0) parts += link else {
                val center = Vector3f(link.center).sub(0f, swirlY.toFloat(), 0f)
                outerTilt.transform(center).add(0f, y.toFloat(), 0f)
                parts += link.copy(center = center, rotation = Quaternionf(outerTilt).mul(link.rotation))
            }
        }
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 + PI / 8.0
        val orbit = ringRadius * (0.46 + index % 2 * 0.12)
        parts += part(if (index % 3 == 0) Material.AMETHYST_BLOCK else Material.CRYING_OBSIDIAN,
            cos(angle) * orbit, swirlY + sin(angle * 2.0) * 0.025, sin(angle) * orbit,
            0.11 + collapse * 0.035, 0.11 + collapse * 0.035, 0.18,
            yaw = angle, pitch = sin(angle) * 0.25)
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
            parts += part(if (column == 3 || (row + column) % 3 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
                x, h * 0.5, z, (0.28 + row * 0.045) * (0.2 + grow * 0.8), h,
                0.44 + row * 0.06, yaw = -angle, pitch = -0.10 * grow)
        }
        val gleam = (grow * melt).coerceAtLeast(0.001)
        parts += part(Material.LIGHT_BLUE_STAINED_GLASS, 0.0, 0.045, distance,
            (0.35 + distance * 0.3) * gleam, 0.045 * gleam, 0.14 * gleam)
    }
    return parts
}

private fun frostNovaDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double): List<StaffDisplayPart> {
    val reach = extent(radius, 16.0, 8.0).coerceAtLeast(1.8)
    val travel = ((ageTicks - 2) / 16.0).coerceIn(0.0, 1.0)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val lift = sin(PI * travel)
    val parts = ArrayList<StaffDisplayPart>(48)

    repeat(16) { index ->
        val angle = index * PI / 8.0
        parts += groundRingLink(if (index % 3 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
            front, angle, angle + PI / 8.0, 0.055, 0.12)
    }
    repeat(16) { index ->
        val angle = index * PI / 8.0
        val height = 0.70 + lift * (0.45 + 0.25 * abs(sin(index * 1.9)))
        val x = cos(angle) * front
        val z = sin(angle) * front
        parts += part(if (index % 4 == 0) Material.PACKED_ICE else Material.BLUE_ICE,
            x, height * 0.5, z, 0.22 + lift * 0.07, height, 0.24 + lift * 0.05,
            yaw = -angle, pitch = -0.12 * lift)
    }
    repeat(16) { index ->
        val angle = index * PI / 8.0 + PI / 16.0
        val wake = (front - 0.52).coerceAtLeast(1.25)
        val y = 0.24 + lift * 0.16 + sin(index * 1.4) * 0.035
        parts += part(if (index % 3 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
            cos(angle) * wake, y, sin(angle) * wake,
            0.075, 0.24 + lift * 0.08, 0.28, yaw = -angle, roll = angle * 0.18)
    }
    return parts
}

private fun lanceDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val reach = extent(length, 48.0, 8.0)
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
    val width = extent(radius, 8.0, 0.85)
    val phase = progress(ageTicks, durationTicks)
    val parts = ArrayList<StaffDisplayPart>(if (impact) 48 else 14)
    if (impact) {
        val expand = smooth(phase / 0.42)
        val core = 1.0 - smooth(phase / 0.40) * 0.86
        val distance = 0.18 + width * expand
        parts += part(Material.SEA_LANTERN, 0.0, 0.04, 0.0,
            0.42 * core, 0.42 * core, 0.42 * core, roll = PI / 4)
        parts += part(Material.GOLD_BLOCK, 0.0, 0.10, 0.0,
            0.62 * core, 0.62 * core, 0.62 * core, roll = PI / 4)
        repeat(32) { index ->
            val y = (index + 0.5) / 32.0
            val radial = kotlin.math.sqrt(1.0 - y * y)
            val angle = index * 2.399963229728653
            val shard = (0.18 + width * 0.035) * (1.0 - expand * 0.52)
            val material = when {
                index % 8 == 0 -> Material.BLACKSTONE
                index % 3 == 0 -> Material.YELLOW_STAINED_GLASS
                else -> Material.ORANGE_STAINED_GLASS
            }
            parts += part(material,
                cos(angle) * radial * distance, 0.18 + y * distance,
                sin(angle) * radial * distance, shard, shard * 0.82, shard * 1.8,
                yaw = angle, pitch = -y * 0.8)
        }
        repeat(14) { index ->
            val angle = index * 2.0 * PI / 14.0
            val ring = 0.24 + width * expand * 0.88
            parts += groundRingLink(if (index % 3 == 0) Material.YELLOW_STAINED_GLASS else Material.ORANGE_STAINED_GLASS,
                ring, angle, angle + 2.0 * PI / 14.0, 0.07 + expand * 0.04,
                0.10 + (1.0 - expand) * 0.04)
        }
        return parts
    }

    parts += part(Material.GOLD_BLOCK, 0.0, 0.0, 0.0,
        0.24 * width, 0.24 * width, 0.40 * width, roll = PI / 4)
    parts += part(Material.ORANGE_STAINED_GLASS, 0.0, 0.0, -0.08,
        0.44 * width, 0.44 * width, 0.64 * width, roll = PI / 4)
    repeat(12) { index ->
        val row = index / 3
        val angle = index % 3 * PI * 2.0 / 3.0
        val taper = 1.0 - row * 0.20
        parts += part(if (row == 0) Material.YELLOW_STAINED_GLASS else Material.ORANGE_STAINED_GLASS,
            cos(angle) * 0.14 * taper, sin(angle) * 0.14 * taper,
            -0.40 - row * 0.40, 0.10 * taper, 0.10 * taper, 0.58 * taper)
    }
    return parts
}

private fun novaDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double): List<StaffDisplayPart> {
    val reach = extent(radius, 16.0, 8.0).coerceAtLeast(1.8)
    val travel = ((ageTicks - 2) / 16.0).coerceIn(0.0, 1.0)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val crest = sin(PI * travel)
    val parts = ArrayList<StaffDisplayPart>(48)
    val lower = (front - 0.22).coerceAtLeast(1.55)
    repeat(16) { index ->
        val angle = index * PI / 8.0
        parts += groundRingLink(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.LIME_STAINED_GLASS,
            lower, angle, angle + PI / 8.0, 0.08 + crest * 0.035, 0.11)
    }
    repeat(16) { index ->
        val angle = index * PI / 8.0
        parts += groundRingLink(if (index % 3 == 0) Material.EMERALD_BLOCK else Material.CYAN_STAINED_GLASS,
            front, angle, angle + PI / 8.0, 0.24 + crest * 0.65, 0.14 + crest * 0.025)
    }
    repeat(16) { index ->
        val angle = index * PI / 8.0 + PI / 16.0
        val wake = (front - 0.12).coerceAtLeast(1.55)
        val height = 0.25 + crest * 0.45
        val y = 0.4 + crest * 0.7 + sin(index * 1.7) * 0.025
        val centerY = if (wake <= 3.0) minOf(y, 0.7 - height / 2.0) else y
        parts += part(if (index % 4 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
            cos(angle) * wake, centerY, sin(angle) * wake,
            0.08, height, 0.24, yaw = -angle, pitch = -0.18, roll = angle * 0.08)
    }
    return parts
}

private fun tidalCrestDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double): List<StaffDisplayPart> {
    val reach = extent(length, 48.0, 12.0).coerceAtLeast(1.8)
    val configuredHalfWidth = extent(radius, 10.0, 5.5)
    val travel = ((ageTicks - 2) / 16.0).coerceIn(0.0, 1.0)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val halfWidth = configuredHalfWidth * front / reach
    val lift = sin(PI * travel)
    val parts = ArrayList<StaffDisplayPart>(48)

    val lowerPoints = (0..16).map { index ->
        val angle = -PI / 2.0 + index * PI / 16.0
        Vector3f((sin(angle) * halfWidth).toFloat(), (0.07 + lift * 0.08 * cos(angle)).toFloat(),
            (front + 0.58 * cos(angle)).toFloat())
    }
    repeat(16) { index ->
        val width = 0.14
        val height = 0.12
        val inset = arcJoinInset(lowerPoints, index, maxOf(width, height))
        parts += shortenedLink(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.LIME_STAINED_GLASS,
            lowerPoints[index], lowerPoints[index + 1], width, height, inset)
    }
    val upperPoints = (0..16).map { index ->
        val angle = -PI / 2.0 + index * PI / 16.0
        Vector3f((sin(angle) * halfWidth).toFloat(), (0.24 + lift * 0.86 * cos(angle)).toFloat(),
            (front + 0.90 * cos(angle)).toFloat())
    }
    repeat(16) { index ->
        val width = 0.17
        val height = 0.20 + lift * 0.06
        val inset = arcJoinInset(upperPoints, index, maxOf(width, height))
        parts += shortenedLink(if (index % 3 == 0) Material.EMERALD_BLOCK else Material.CYAN_STAINED_GLASS,
            upperPoints[index], upperPoints[index + 1], width, height, inset)
    }
    repeat(16) { index ->
        val angle = -PI / 2.0 + (index + 0.5) * PI / 16.0
        val arch = cos(angle)
        val height = 0.34 + lift * 0.16 * arch
        val x = sin(angle) * halfWidth
        val z = front + 0.82 * arch
        val y = 0.64 + lift * 1.36 * arch
        val centerY = if (kotlin.math.hypot(x, z) <= 3.0) minOf(y, 0.7 - height / 2.0) else y
        parts += part(if (index % 4 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
            x, centerY, z, 0.10, height, 0.22,
            yaw = -angle, roll = angle * 0.12)
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

private fun groundRingLink(material: Material, radius: Double, startAngle: Double, endAngle: Double,
    y: Double, thickness: Double): StaffDisplayPart {
    val start = Vector3f((cos(startAngle) * radius).toFloat(), y.toFloat(), (sin(startAngle) * radius).toFloat())
    val end = Vector3f((cos(endAngle) * radius).toFloat(), y.toFloat(), (sin(endAngle) * radius).toFloat())
    val cornerOverlap = thickness * 0.5 * tan(abs(endAngle - startAngle) * 0.5)
    return shortenedLink(material, start, end, thickness, thickness, cornerOverlap + 0.002)
}

private fun arcJoinInset(points: List<Vector3f>, index: Int, thickness: Double): Double {
    val current = Vector3f(points[index + 1]).sub(points[index])
    var turn = 0.0
    if (index > 0) {
        val previous = Vector3f(points[index]).sub(points[index - 1])
        turn = maxOf(turn, vectorTurn(previous, current))
    }
    if (index + 1 < points.lastIndex) {
        val next = Vector3f(points[index + 2]).sub(points[index + 1])
        turn = maxOf(turn, vectorTurn(current, next))
    }
    return thickness * 0.5 * tan(turn * 0.5) + 0.002
}

private fun vectorTurn(first: Vector3f, second: Vector3f): Double {
    val a = Vector3f(first).normalize()
    val b = Vector3f(second).normalize()
    return atan2(Vector3f(a).cross(b).length().toDouble(), a.dot(b).coerceIn(-1f, 1f).toDouble())
}

private fun shortenedLink(material: Material, start: Vector3f, end: Vector3f,
    width: Double, height: Double, inset: Double): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(0.001f)
    val offset = Vector3f(delta).normalize().mul(minOf(inset, length * 0.2).toFloat())
    return link(material, Vector3f(start).add(offset), Vector3f(end).sub(offset), width, height)
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
