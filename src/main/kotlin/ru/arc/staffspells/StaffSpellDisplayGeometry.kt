package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal data class StaffDisplayPart(
    val material: Material,
    val center: Vector3f,
    val scale: Vector3f,
    val rotation: Quaternionf,
    val visible: Boolean = true,
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
    animationAgeTicks: Int = ageTicks,
): List<StaffDisplayPart> {
    val parts = when (spell) {
    StaffSpell.CHAIN -> chainDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.MARK -> if (secondary) singularityDisplayParts(ageTicks, durationTicks, radius, impact, animationAgeTicks)
        else markDisplayParts(ageTicks, radius, impact, animationAgeTicks)
    StaffSpell.FROST -> if (secondary) frostNovaDisplayParts(ageTicks, durationTicks, radius)
        else frostDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.LANCE -> lanceDisplayParts(ageTicks, durationTicks, length, radius, impact)
    StaffSpell.EMBER -> emberDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.NOVA -> if (secondary) tidalCrestDisplayParts(ageTicks, durationTicks, length, radius)
        else novaDisplayParts(ageTicks, durationTicks, radius)
    }
    val tracked = !impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
    val birth = if (spell == StaffSpell.MARK && impact) 1.0 else smooth(ageTicks / 4.0)
    val lastFrame = (durationTicks - StaffSpellDisplayEffects.FRAME_TICKS).coerceAtLeast(1)
    val novaFade = if (spell == StaffSpell.NOVA) {
        val dissolveStart = (durationTicks - 8).coerceAtLeast(0)
        if (ageTicks <= dissolveStart) 1.0 else {
            val remaining = ((durationTicks - ageTicks).toDouble() / 8.0).coerceIn(0.0, 1.0)
            remaining * remaining
        }
    } else 1.0
    val fade = if (tracked) 1.0 else if (spell == StaffSpell.NOVA) novaFade
        else smooth((lastFrame - ageTicks) / 6.0)
    val visibility = when (spell) {
        StaffSpell.CHAIN -> fade.coerceAtLeast(0.001).toFloat()
        StaffSpell.LANCE -> ((0.62 + 0.38 * birth) * fade).coerceAtLeast(0.001).toFloat()
        else -> (birth * fade).coerceAtLeast(0.001).toFloat()
    }
    return parts.map { part -> part.copy(
        center = if (spell == StaffSpell.FROST || spell == StaffSpell.NOVA)
            Vector3f(part.center).apply { y *= visibility } else part.center,
        scale = Vector3f(part.scale).mul(visibility),
    ) }
}

private fun chainDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    // Preview launch pose only. Live scenes call the same renderer with the accumulated route,
    // beginning with a single eye-origin point rather than a pre-rendered caster-to-target bolt.
    val launchDistance = minOf(extent(length, 48.0, 8.0), 2.5).toFloat()
    return staffLightningTrailParts(listOf(Vector3f(), Vector3f(0f, 0f, launchDistance)))
}

/** A live bolt only contains the route the controller has already traveled. */
internal fun staffLightningTrailParts(points: List<Vector3f>, fade: Double = 1.0): List<StaffDisplayPart> {
    val visibility = fade.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    if (visibility <= 0.0) return emptyList()

    val route = ArrayList<Vector3f>(41)
    points.take(41).forEach { point ->
        route += if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) Vector3f(point)
        else route.lastOrNull()?.let { Vector3f(it) } ?: Vector3f()
    }
    val visualRoute = route.mapIndexed { index, point ->
        if (index < 2) Vector3f(point) else {
            val tangent = Vector3f(point).sub(route[index - 1])
            if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
            val lateral = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
            if (lateral.lengthSquared() < 0.000001f) lateral.set(tangent).cross(Vector3f(1f, 0f, 0f))
            if (lateral.lengthSquared() < 0.000001f) lateral.set(1f, 0f, 0f) else lateral.normalize()
            val sign = if (index % 2 == 0) 1f else -1f
            val amplitude = when (index % 3) { 0 -> 0.30f; 1 -> 0.375f; else -> 0.45f }
            val vertical = if (index % 3 == 0) -0.20f else 0.20f
            Vector3f(point).fma(sign * amplitude, lateral).add(0f, vertical, 0f)
        }
    }
    val anchor = visualRoute.lastOrNull() ?: Vector3f()
    val slotMaterials = List(47) { index -> when {
        index < 40 -> if (index % 2 == 0) Material.SEA_LANTERN else Material.CYAN_STAINED_GLASS
        index < 44 -> if (index % 2 == 0) Material.LIGHT_BLUE_STAINED_GLASS else Material.WHITE_STAINED_GLASS
        index == 44 -> Material.SEA_LANTERN
        else -> Material.WHITE_STAINED_GLASS
    } }
    val parts = MutableList(47) { index ->
        StaffDisplayPart(slotMaterials[index], Vector3f(anchor), Vector3f(0.001f), Quaternionf(), visible = false)
    }

    fun activate(index: Int, part: StaffDisplayPart) {
        parts[index] = part.copy(scale = Vector3f(part.scale).mul(visibility.toFloat()), visible = true)
    }

    // Each pair of controller samples finalizes a four-block bend. The last pair can be
    // shorter while in flight; occupied slots never roll when later samples arrive.
    for (segment in 0 until 20) {
        val startIndex = segment * 2
        if (startIndex + 1 >= visualRoute.size) continue
        val endIndex = minOf(startIndex + 2, visualRoute.lastIndex)
        val edges = if (endIndex > startIndex + 1) 2 else 1
        for (edge in 0 until edges) {
            val first = visualRoute[startIndex + edge]
            val second = visualRoute[startIndex + edge + 1]
            val delta = Vector3f(second).sub(first)
            val length = delta.length().toDouble()
            if (length < 0.05) continue

            val displayStart = Vector3f(first)
            if (segment == 0 && edge == 0) {
                val inset = minOf(1.45, length * 0.85)
                displayStart.fma((inset / length).toFloat(), delta)
            }
            val isMuzzleLink = segment == 0 && edge == 0
            val width = when {
                isMuzzleLink -> 0.40
                edge == 0 -> 0.52
                else -> 0.44
            }
            val height = when {
                isMuzzleLink -> 0.38
                edge == 0 -> 0.48
                else -> 0.40
            }
            val material = slotMaterials[startIndex + edge]
            val link = link(material, displayStart, second, width, height)
            val roll = if ((segment + edge) % 2 == 0) 0.18f else -0.18f
            activate(startIndex + edge, link.copy(rotation = Quaternionf(link.rotation).rotateZ(roll)))
        }
    }

    val branchAnchors = intArrayOf(4, 12, 20, 28)
    branchAnchors.forEachIndexed { branch, pointIndex ->
        if (pointIndex >= visualRoute.size) return@forEachIndexed
        val origin = visualRoute[pointIndex]
        val previous = visualRoute[(pointIndex - 1).coerceAtLeast(0)]
        val tangent = Vector3f(origin).sub(previous)
        if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
        val side = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
        if (side.lengthSquared() < 0.000001f) side.set(1f, 0f, 0f) else side.normalize()
        if (branch % 2 == 1) side.negate()
        val end = Vector3f(origin).fma(0.68f, side).add(0f, 0.18f, 0f)
        val branchLink = link(slotMaterials[40 + branch], origin, end, 0.42, 0.38)
        activate(40 + branch, branchLink.copy(
            rotation = Quaternionf(branchLink.rotation).rotateZ(if (branch % 2 == 0) 0.23f else -0.23f),
        ))
    }

    if (route.isNotEmpty()) {
        val head = visualRoute.last()
        val tangent = if (visualRoute.size > 1) Vector3f(head).sub(visualRoute[visualRoute.lastIndex - 1]) else Vector3f(0f, 0f, 1f)
        if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
        val facing = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), tangent)
        activate(44, StaffDisplayPart(slotMaterials[44], Vector3f(head), Vector3f(0.32f, 0.32f, 0.32f), Quaternionf(facing)))
        val side = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
        if (side.lengthSquared() < 0.000001f) side.set(1f, 0f, 0f) else side.normalize()
        listOf(-1f, 1f).forEachIndexed { index, sign ->
            val center = Vector3f(head).fma(sign * 0.15f, side)
            activate(45 + index, StaffDisplayPart(slotMaterials[45 + index], center,
                Vector3f(0.26f, 0.24f, 0.30f), Quaternionf(facing).rotateZ(sign * 0.24f)))
        }
    }
    return parts
}

private fun markDisplayParts(ageTicks: Int, radius: Double, impact: Boolean,
    animationAgeTicks: Int): List<StaffDisplayPart> {
    val opening = if (impact) 1.0 else smooth(ageTicks / 8.0)
    val reach = extent(radius, 8.0, 1.3)
    val release = if (impact) smooth(ageTicks / 4.0) else opening
    val coreSize = 0.62 + opening * 0.54
    val chargedRadius = 0.55 + reach * 0.20 * opening
    val tearRadius = if (impact) chargedRadius + reach * 0.22 * release else chargedRadius
    val spin = animationAgeTicks * 0.022
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
        val size = if (impact) 0.62 + 0.20 * release else 0.42 + 0.20 * release
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
            (if (impact) 0.64 else 0.48) + release * 0.16,
            (if (impact) 0.72 else 0.58) + release * 0.14,
            (if (impact) 0.86 else 0.70) + release * 0.16,
            yaw = angle, pitch = -0.48, roll = angle * 0.45)
    }
    return parts
}

private fun singularityDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean,
    animationAgeTicks: Int): List<StaffDisplayPart> {
    val phase = progress(ageTicks, durationTicks)
    val opening = if (impact) 1.0 else smooth(ageTicks / 8.0)
    val collapse = if (impact) smooth((phase - 0.56) / 0.44) else 0.0
    val reach = extent(radius, 8.0, 3.5)
    val cloudRadius = (0.55 + reach * 0.48 * opening) * (1.0 - collapse * 0.80)
    val core = 0.82 + 0.36 * opening + 0.18 * collapse
    val swirlY = 1.6
    val spin = animationAgeTicks * 0.035
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
    val reach = staffLanceReach(length)
    val front = staffLanceFront(ageTicks, length)
    val shaftStart = (front - 7.0).coerceAtLeast(minOf(1.45, front))
    val shaftLength = (front - shaftStart).coerceAtLeast(0.30)
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
    val launchFront = minOf(reach, 2.0)
    val travel = if (reach <= launchFront) 1.0 else ((front - launchFront) / (reach - launchFront)).coerceIn(0.0, 1.0)
    val expansion = (0.65 + 0.95 * travel).toFloat()
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
    val crest = sin(PI * travel * 0.5)
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
    val lift = sin(PI * travel * 0.5)
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

private fun staffLanceReach(length: Double): Double =
    length.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.1, 48.0) ?: 0.1

/** Shared flight pacing for the display geometry and the particle trail. */
internal fun staffLanceFlightTicks(length: Double): Int {
    val reach = staffLanceReach(length)
    return ceil((reach - minOf(reach, 2.0)) / 2.8).toInt().coerceAtLeast(4)
}

internal fun staffLanceDuration(length: Double): Int = staffLanceFlightTicks(length) + 8

/** Lance begins as a compact two-block muzzle core, then advances at 2.8 blocks per tick. */
internal fun staffLanceFront(ageTicks: Int, length: Double): Double {
    val reach = staffLanceReach(length)
    val muzzle = minOf(reach, 2.0)
    val flight = staffLanceFlightTicks(length)
    val progress = (ageTicks.coerceAtLeast(0).toDouble() / flight).coerceIn(0.0, 1.0)
    return muzzle + (reach - muzzle) * progress
}

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
