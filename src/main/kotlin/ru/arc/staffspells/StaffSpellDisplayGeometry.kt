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
    val visibility = when (spell) {
        StaffSpell.CHAIN, StaffSpell.LANCE -> ((0.62 + 0.38 * birth) * fade).coerceAtLeast(0.001).toFloat()
        else -> (birth * fade).coerceAtLeast(0.001).toFloat()
    }
    return parts.map { part -> part.copy(
        center = if (spell == StaffSpell.FROST) Vector3f(part.center).apply { y *= visibility } else part.center,
        scale = Vector3f(part.scale).mul(visibility),
    ) }
}

private fun chainDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val reach = extent(length, 48.0, 8.0)
    val coreWidth = extent(radius, 1.6, 0.75).coerceIn(0.45, 0.80)
    val launch = minOf(reach, 2.5)
    val front = if (impact) reach else launch + (reach - launch).coerceAtLeast(0.0) * smooth(ageTicks / 12.0)
    val parts = ArrayList<StaffDisplayPart>(32)
    val segmentCount = 14
    repeat(segmentCount) { index ->
        val t = (index + 0.5) / segmentCount
        val step = front / segmentCount
        val sway = sin(index * 1.8 + phase * 0.7)
        val centerX = sway * (0.16 + 0.10 * t)
        val centerY = cos(index * 1.35 + phase) * 0.10
        parts += part(if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            centerX, centerY, front * t, coreWidth * 0.70 + 0.05 * t, coreWidth * 0.65 + 0.04 * t,
            maxOf(0.42, step * 0.84), yaw = sin(index * 1.8) * 0.22,
            pitch = cos(index * 1.35) * 0.18, roll = sin(index * 0.9 + phase) * 0.20)
    }
    listOf(2, 5, 8, 11).forEachIndexed { branch, index ->
        val t = (index + 0.7) / segmentCount
        val side = if (branch % 2 == 0) -1.0 else 1.0
        parts += part(Material.LIGHT_BLUE_STAINED_GLASS,
            side * (0.32 + t * 0.20), 0.16 + sin(index * 1.7) * 0.08, front * t,
            0.38, 0.36, 0.50, yaw = side * 0.62, pitch = side * -0.38, roll = phase * 0.15)
    }
    val headSize = 0.68 + if (impact) phase * 0.20 else 0.0
    parts += part(Material.SEA_LANTERN, 0.0, 0.0, front,
        maxOf(headSize, coreWidth * 0.90), maxOf(headSize * 0.92, coreWidth * 0.82),
        maxOf(headSize * 1.1, coreWidth * 0.92), roll = PI / 4.0)
    repeat(4) { index ->
        val angle = index * PI / 2.0 + phase * 0.12
        parts += part(if (index % 2 == 0) Material.CYAN_STAINED_GLASS else Material.WHITE_STAINED_GLASS,
            cos(angle) * 0.27, sin(angle) * 0.27, front - 0.06,
            0.48, 0.46, 0.58, yaw = angle, pitch = sin(angle) * 0.34, roll = angle * 0.35)
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
        val width = (0.42 + 0.16 * age) * visibility
        val secondaryWidth = (0.32 + 0.12 * age) * visibility
        val zig = (if (index % 2 == 0) 0.18 else -0.18) * visibility
        val nextZig = -zig
        val start = Vector3f(traveled[index]).fma(zig.toFloat(), sides[index])
        val end = Vector3f(traveled[index + 1]).fma(nextZig.toFloat(), sides[index + 1])
        val secondStart = Vector3f(traveled[index]).fma(-zig.toFloat() * 0.55f, sides[index])
            .add(0f, 0.24f * visibility.toFloat(), 0f)
        val secondEnd = Vector3f(traveled[index + 1]).fma(-nextZig.toFloat() * 0.55f, sides[index + 1])
            .add(0f, 0.24f * visibility.toFloat(), 0f)
        val launchInset = if (index == 0 && trail.size <= 17) 0.22 else 0.0
        val coreLink = trimmedLink(if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            start, end, width, width * 0.85, maxOf(launchInset, width * 0.32), width * 0.25)
        segmentParts += if (launchInset > 0.0)
            coreLink.copy(rotation = Quaternionf(coreLink.rotation).rotateZ(0.22f)) else coreLink
        val glintLink = trimmedLink(if (index % 4 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_BLUE_STAINED_GLASS,
            secondStart, secondEnd, secondaryWidth, secondaryWidth * 0.75,
            maxOf(launchInset, secondaryWidth * 0.32), secondaryWidth * 0.25)
        segmentParts += if (launchInset > 0.0)
            glintLink.copy(rotation = Quaternionf(glintLink.rotation).rotateZ(-0.22f)) else glintLink
    }

    val branchIndices = when {
        segmentCount >= 6 -> listOf(segmentCount / 4, segmentCount / 2, segmentCount * 3 / 4)
        segmentCount >= 3 -> listOf(segmentCount / 3, segmentCount * 2 / 3)
        else -> emptyList()
    }.distinct().filter { it in 1 until traveled.lastIndex }
    val branchParts = branchIndices.mapIndexed { branch, index ->
        val direction = Vector3f(sides[index]).mul(if (branch % 2 == 0) 1f else -1f)
        val end = Vector3f(traveled[index])
            .fma(0.52f * visibility.toFloat(), direction)
            .add(0f, 0.28f * visibility.toFloat(), 0f)
        link(Material.CYAN_STAINED_GLASS, traveled[index], end,
            0.38 * visibility, 0.34 * visibility)
    }

    val head = traveled.last()
    val headWidth = 0.78 * visibility
    val headParts = ArrayList<StaffDisplayPart>(3)
    headParts += part(Material.SEA_LANTERN, head.x.toDouble(), head.y.toDouble(), head.z.toDouble(),
        headWidth, headWidth * 0.88, headWidth, roll = PI / 4.0)
    listOf(-1.0, 1.0).forEach { side ->
        val point = Vector3f(head).fma((0.24 * visibility * side).toFloat(), sides.last())
            .add(0f, (0.10 * visibility).toFloat(), 0f)
        headParts += part(Material.WHITE_STAINED_GLASS, point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
            0.40 * visibility, 0.38 * visibility, 0.58 * visibility, yaw = side * 0.55,
            pitch = side * 0.30, roll = side * 0.25)
    }
    // Reserve branch slots and keep the moving head at indices 0–2 so packet IDs never swap roles.
    val branches = (0 until 3).map { index -> branchParts.getOrNull(index) ?: link(
        Material.CYAN_STAINED_GLASS, head, Vector3f(head).add(0.001f, 0f, 0f), 0.001, 0.001) }
    return headParts + branches + segmentParts
}

private fun markDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val opening = if (impact) smooth(phase / 0.34) else smooth(ageTicks / 8.0)
    val collapse = if (impact) smooth((phase - 0.80) / 0.20) else 0.0
    val reach = extent(radius, 8.0, 1.3)
    val release = if (impact) smooth(phase / 0.42) * (1.0 - collapse) else opening
    val coreSize = (0.62 + opening * 0.54) * (1.0 - collapse * 0.48)
    val tearRadius = 0.55 + reach * (if (impact) 0.42 * release else 0.20 * opening)
    val spin = ageTicks * 0.022
    val parts = ArrayList<StaffDisplayPart>(20)

    // A charged violet mass gives the tear a solid center before its broad shards open.
    repeat(5) { index ->
        val angle = index * 2.0 * PI / 5.0 + spin
        val size = coreSize * (0.84 + (index % 3) * 0.08)
        parts += part(when (index) {
            0 -> Material.AMETHYST_BLOCK
            1, 3 -> Material.PURPLE_STAINED_GLASS
            2 -> Material.MAGENTA_STAINED_GLASS
            else -> Material.CRYING_OBSIDIAN
        },
            cos(angle) * 0.10, sin(angle * 1.7) * 0.11, sin(angle) * 0.09,
            size, size * (0.88 + index % 2 * 0.12), size * (0.82 + index % 3 * 0.10),
            yaw = angle + PI / 5.0, pitch = 0.42 + index * 0.17, roll = angle * 0.43)
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 + spin * 0.65
        val radiusAt = tearRadius * (0.78 + index % 2 * 0.14)
        val size = 0.42 + 0.20 * release
        parts += part(if (index % 3 == 0) Material.AMETHYST_BLOCK else Material.MAGENTA_STAINED_GLASS,
            cos(angle) * radiusAt, sin(angle) * radiusAt * 0.70, sin(angle * 2.0) * 0.20,
            size, size * 1.12, size * 1.24,
            yaw = angle + PI / 3.0, pitch = 0.35 + sin(angle) * 0.5, roll = angle * 0.28)
    }
    repeat(4) { index ->
        val angle = index * PI / 2.0 + PI / 4.0 + spin
        val radiusAt = tearRadius * (0.34 + opening * 0.10)
        parts += part(Material.PURPLE_STAINED_GLASS,
            cos(angle) * radiusAt, sin(angle) * radiusAt * 0.62, sin(angle + 1.2) * 0.24,
            0.48 + release * 0.16, 0.58 + release * 0.14, 0.70 + release * 0.16,
            yaw = angle, pitch = -0.48, roll = angle * 0.45)
    }
    return parts
}

private fun singularityDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val opening = if (impact) smooth((phase / 0.42).coerceAtMost(1.0)) else smooth(ageTicks / 8.0)
    val collapse = if (impact) smooth((phase - 0.56) / 0.44) else 0.0
    val reach = extent(radius, 8.0, 3.5)
    val cloudRadius = (0.55 + reach * 0.48 * opening) * (1.0 - collapse * 0.80)
    val core = 0.82 + 0.36 * opening + 0.18 * collapse
    val swirlY = 1.6
    val spin = ageTicks * 0.035
    val parts = ArrayList<StaffDisplayPart>(30)

    // Heavy overlapping dark crystals make a spherical singularity instead of a wireframe orbit.
    repeat(7) { index ->
        val angle = index * 2.0 * PI / 7.0 + spin
        val size = core * (0.78 + index % 3 * 0.08)
        parts += part(when (index % 4) {
            0 -> Material.OBSIDIAN
            1 -> Material.CRYING_OBSIDIAN
            2 -> Material.PURPLE_STAINED_GLASS
            else -> Material.DARK_PRISMARINE
        },
            cos(angle) * 0.12, swirlY + sin(angle * 1.6) * 0.13, sin(angle) * 0.12,
            size, size * (0.82 + index % 2 * 0.16), size * (0.90 + index % 3 * 0.08),
            yaw = angle + 0.3, pitch = 0.45 + index * 0.16, roll = angle * 0.4)
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 + spin * 0.72
        val orbit = cloudRadius * (0.48 + index % 2 * 0.10)
        val size = 0.48 + 0.16 * opening
        parts += part(if (index % 3 == 0) Material.CRYING_OBSIDIAN else Material.AMETHYST_BLOCK,
            cos(angle) * orbit, swirlY + sin(angle * 1.7) * (0.32 + opening * 0.34), sin(angle) * orbit,
            size, size * 1.18, size * 0.86,
            yaw = angle + PI / 3.0, pitch = 0.45 + sin(angle) * 0.45, roll = angle * 0.31)
    }
    repeat(8) { index ->
        val angle = index * PI / 4.0 + PI / 8.0 - spin * 0.42
        val orbit = cloudRadius * (0.84 + index % 2 * 0.12)
        val size = 0.42 + 0.14 * opening
        parts += part(if (index % 4 == 0) Material.OBSIDIAN else Material.PURPLE_STAINED_GLASS,
            cos(angle) * orbit, swirlY + cos(angle * 2.0) * (0.42 + opening * 0.32), sin(angle) * orbit,
            size * 1.08, size, size * 1.28,
            yaw = angle - PI / 4.0, pitch = -0.52 + sin(angle) * 0.34, roll = angle * 0.27)
    }
    repeat(6) { index ->
        val angle = index * 2.0 * PI / 6.0 + spin * 1.1
        val orbit = cloudRadius * (0.64 + index % 2 * 0.24)
        parts += part(if (index % 2 == 0) Material.DARK_PRISMARINE else Material.CRYING_OBSIDIAN,
            cos(angle) * orbit, swirlY + sin(angle * 2.3) * 0.68, sin(angle) * orbit,
            0.46 + opening * 0.12, 0.48 + opening * 0.18, 0.62 + opening * 0.14,
            yaw = angle, pitch = 0.6, roll = angle * 0.5)
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

@Suppress("UNUSED_PARAMETER")
private fun lanceDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val reach = extent(length, 48.0, 8.0)
    val arrival = smooth(ageTicks / 6.0)
    val front = minOf(reach, 2.0 + (reach - 2.0).coerceAtLeast(0.0) * arrival)
    val tailLength = minOf(6.0 + arrival * 2.0, (front - 1.25).coerceAtLeast(0.30))
    val shaftStart = (front - tailLength).coerceAtLeast(1.25)
    val shaftLength = (front - shaftStart - 0.45).coerceAtLeast(0.30)
    val width = extent(radius, 1.0, 0.75)
    val parts = ArrayList<StaffDisplayPart>(30)

    // A broad, bright plasma body stays straight on the forward axis; facets flare around the point.
    repeat(10) { index ->
        val t = (index + 0.5) / 10.0
        val z = shaftStart + shaftLength * t
        val size = 0.48 + 0.12 * sin(t * PI)
        parts += part(if (index % 3 == 0) Material.GOLD_BLOCK else Material.YELLOW_STAINED_GLASS,
            sin(index * 1.4) * 0.06, cos(index * 1.3) * 0.06, z,
            size, size * 0.88, maxOf(0.42, shaftLength / 10.0 * 0.86),
            yaw = sin(index * 1.1) * 0.12, pitch = cos(index * 1.7) * 0.13,
            roll = index * 0.12)
    }
    repeat(8) { index ->
        val t = (index + 0.5) / 8.0
        val side = if (index % 2 == 0) -1.0 else 1.0
        val z = (shaftStart - 0.05).coerceAtLeast(1.20) + (front - shaftStart - 0.55).coerceAtLeast(0.0) * t
        val flare = 0.18 + 0.16 * t
        parts += part(if (index % 3 == 0) Material.ORANGE_STAINED_GLASS else Material.GOLD_BLOCK,
            side * flare, sin(index * 1.3) * 0.20, z,
            0.42 + 0.12 * t, 0.40 + 0.10 * t, 0.58 + 0.16 * t,
            yaw = side * 0.28, pitch = side * -0.20 + sin(index * 1.7) * 0.04,
            roll = side * (0.35 + t * 0.18))
    }
    val headSize = 0.68 + width * 0.18
    parts += part(Material.SEA_LANTERN, 0.0, 0.0, front,
        headSize, headSize * 0.94, headSize * 1.16, roll = PI / 4.0)
    repeat(5) { index ->
        val angle = index * 2.0 * PI / 5.0
        parts += part(if (index % 2 == 0) Material.WHITE_STAINED_GLASS else Material.GOLD_BLOCK,
            cos(angle) * 0.30, sin(angle) * 0.30, front - 0.18,
            0.52, 0.48, 0.70, yaw = angle + PI / 3.0,
            pitch = sin(angle) * 0.34, roll = angle * 0.42)
    }
    // Grow after leaving the muzzle: a small nearby core and a legible distant solar mass.
    val expansion = (0.28 + 1.90 * smooth((front - 2.0) / 22.0)).toFloat()
    return parts.map { piece -> piece.copy(
        center = Vector3f(piece.center.x * expansion, piece.center.y * expansion, piece.center.z),
        scale = Vector3f(piece.scale).mul(expansion),
    ) }
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
    val parts = ArrayList<StaffDisplayPart>(42)
    repeat(14) { index ->
        val angle = index * 2.0 * PI / 14.0 + travel * 0.08
        val baseRadius = front - 0.32
        val innerRadius = front - 0.42
        val outerRadius = front + 0.24
        val size = 0.66 + 0.24 * crest
        val height = 0.76 + 0.46 * crest * (0.75 + 0.25 * cos(index * 1.7))
        parts += part(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.LIME_TERRACOTTA,
            cos(angle) * baseRadius, height * 0.5, sin(angle) * baseRadius,
            size, height, size * 0.86, yaw = -angle + 0.28, pitch = sin(index * 1.5) * 0.20,
            roll = cos(index * 1.2) * 0.18)
        parts += part(if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            cos(angle) * innerRadius, 0.92 + crest * 0.52, sin(angle) * innerRadius,
            0.62 + crest * 0.18, 0.66 + crest * 0.42, 0.58 + crest * 0.16,
            yaw = -angle + PI / 4.0, pitch = 0.48 + sin(angle) * 0.24, roll = angle * 0.16)
        parts += part(if (index % 3 == 0) Material.EMERALD_BLOCK else Material.PRISMARINE,
            cos(angle) * outerRadius, 0.50 + crest * 0.42, sin(angle) * outerRadius,
            0.48 + crest * 0.12, 0.58 + crest * 0.34, 0.52 + crest * 0.12,
            yaw = -angle - 0.30, pitch = -0.38 + cos(angle) * 0.18, roll = -angle * 0.22)
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
    val parts = ArrayList<StaffDisplayPart>(39)
    val columns = 13
    repeat(columns) { index ->
        val across = index / (columns - 1.0) * 2.0 - 1.0
        val x = across * halfWidth
        val wave = 0.5 + 0.5 * sin(index * 1.35 + travel * PI)
        val depth = front + cos(index * 1.1) * 0.14
        val blockWidth = 0.66 + lift * 0.22
        val baseHeight = 0.76 + lift * (0.38 + wave * 0.24)
        parts += part(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.LIME_TERRACOTTA,
            x, baseHeight * 0.5, depth,
            blockWidth, baseHeight, 0.62 + lift * 0.14,
            yaw = across * 0.30 + sin(index * 1.8) * 0.16,
            pitch = cos(index * 1.3) * 0.15, roll = across * -0.18)
        parts += part(if (index % 3 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS,
            x * 0.96, 0.94 + lift * (0.52 + wave * 0.22), depth - 0.20,
            0.58 + lift * 0.20, 0.74 + lift * (0.38 + wave * 0.18), 0.56 + lift * 0.12,
            yaw = across * 0.42 + PI / 4.0, pitch = 0.42 + across * 0.18, roll = across * 0.24)
        parts += part(if (index % 4 == 0) Material.EMERALD_BLOCK else Material.PRISMARINE,
            x + sin(index * 1.7) * 0.12, 0.48 + lift * (0.36 + wave * 0.22), depth + 0.32,
            0.48 + lift * 0.14, 0.56 + lift * 0.30, 0.52 + lift * 0.14,
            yaw = -across * 0.34 - 0.28, pitch = -0.40 + across * 0.16, roll = across * 0.31)
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

private fun shortenedLink(material: Material, start: Vector3f, end: Vector3f,
    width: Double, height: Double, inset: Double): StaffDisplayPart {
    return trimmedLink(material, start, end, width, height, inset, inset)
}

private fun trimmedLink(material: Material, start: Vector3f, end: Vector3f,
    width: Double, height: Double, startInset: Double, endInset: Double): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(0.001f)
    val direction = Vector3f(delta).normalize()
    val safeStartInset = minOf(startInset, length * 0.2).toFloat()
    val safeEndInset = minOf(endInset, length * 0.2).toFloat()
    return link(material,
        Vector3f(start).fma(safeStartInset, direction),
        Vector3f(end).fma(-safeEndInset, direction),
        width, height)
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
