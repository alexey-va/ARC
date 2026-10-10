package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
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
    val blend = smooth(age / 2.0).toFloat()
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
    StaffSpell.LANCE -> staffSolarDisplayParts(ageTicks, durationTicks, length, impact)
    StaffSpell.EMBER -> emberDisplayParts(ageTicks, durationTicks, radius, impact)
    StaffSpell.NOVA -> if (secondary) tidalCrestDisplayParts(ageTicks, durationTicks, length, radius)
        else novaDisplayParts(ageTicks, durationTicks, radius)
    }
    val tracked = !impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
    val birth = if (impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)) 1.0 else smooth(ageTicks / 4.0)
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
        if (index == 0) Vector3f(point) else {
            val tangent = Vector3f(point).sub(route[index - 1])
            if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
            val lateral = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
            if (lateral.lengthSquared() < 0.000001f) lateral.set(tangent).cross(Vector3f(1f, 0f, 0f))
            if (lateral.lengthSquared() < 0.000001f) lateral.set(1f, 0f, 0f) else lateral.normalize()
            val sign = if (index % 2 == 0) 1f else -1f
            val amplitude = if (index == 1) 0.20f else when (index % 3) { 0 -> 0.78f; 1 -> 1.05f; else -> 0.62f }
            val vertical = if (index == 1) 0.08f else if (index % 3 == 0) -0.52f else 0.38f
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

    // Each pair of controller samples finalizes a jagged bend. The last pair can be
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
                val muzzleAxis = Vector3f(route[1]).sub(route[0])
                val muzzleLength = muzzleAxis.length().toDouble()
                val inset = minOf(1.45, muzzleLength * 0.85)
                if (muzzleLength > 0.001) displayStart.fma((inset / muzzleLength).toFloat(), muzzleAxis)
            }
            val isMuzzleLink = segment == 0 && edge == 0
            // The persistent near link must not become a large opaque square in first person.
            // Widen with distance; the first bend exposes the channel beyond the reticle.
            val width = when {
                isMuzzleLink -> 0.09
                startIndex + edge == 1 -> 0.18
                startIndex + edge == 2 -> 0.28
                startIndex + edge == 3 -> 0.38
                edge == 0 -> 0.52
                else -> 0.44
            }
            // The bright leader outruns its thin afterimage; the bolt never reads as a solid rope.
            val behind = visualRoute.lastIndex - (startIndex + edge + 1)
            val tail = when { behind <= 2 -> 1.0; behind <= 5 -> 0.65; else -> 0.28 }
            val height = width * 0.88 * tail
            val material = slotMaterials[startIndex + edge]
            val link = link(material, displayStart, second, width * tail, height)
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
        val end = Vector3f(origin).fma(1.25f, side).add(0f, 0.38f, 0f)
        val branchLink = link(slotMaterials[40 + branch], origin, end, 0.24, 0.20)
        activate(40 + branch, branchLink.copy(
            rotation = Quaternionf(branchLink.rotation).rotateZ(if (branch % 2 == 0) 0.23f else -0.23f),
        ))
    }

    if (route.isNotEmpty()) {
        val head = visualRoute.last()
        val tangent = if (visualRoute.size > 1) Vector3f(head).sub(visualRoute[visualRoute.lastIndex - 1]) else Vector3f(0f, 0f, 1f)
        if (tangent.lengthSquared() < 0.000001f) tangent.set(0f, 0f, 1f) else tangent.normalize()
        val facing = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), tangent)
        activate(44, StaffDisplayPart(slotMaterials[44], Vector3f(head), Vector3f(0.20f, 0.20f, 0.20f), Quaternionf(facing)))
        val side = Vector3f(tangent).cross(Vector3f(0f, 1f, 0f))
        if (side.lengthSquared() < 0.000001f) side.set(1f, 0f, 0f) else side.normalize()
        listOf(-1f, 1f).forEachIndexed { index, sign ->
            val center = Vector3f(head).fma(sign * 0.10f, side)
            activate(45 + index, StaffDisplayPart(slotMaterials[45 + index], center,
                Vector3f(0.16f, 0.14f, 0.18f), Quaternionf(facing).rotateZ(sign * 0.24f)))
        }
    }
    return parts
}

private fun markDisplayParts(ageTicks: Int, radius: Double, impact: Boolean,
    animationAgeTicks: Int): List<StaffDisplayPart> {
    val charge = if (impact) 1.0 else smooth(ageTicks / 12.0)
    val reach = extent(radius, 8.0, 1.3)
    // A held fracture opens in one decisive snap; fragments keep traveling after that snap.
    val release = if (impact) 1.0 - kotlin.math.exp(-ageTicks / 2.0) else 0.0
    val drift = if (impact) ageTicks * 0.035 else 0.0
    val spin = animationAgeTicks * 0.014
    val parts = ArrayList<StaffDisplayPart>(17)
    repeat(5) { i ->
        val a = i * 2.0 * PI / 5.0 + spin
        val r = (0.08 + release * reach * 0.24 + drift) * (0.8 + i * 0.07)
        parts += part(if (i == 0) Material.SEA_LANTERN else if (i % 2 == 0) Material.AMETHYST_BLOCK else Material.PURPLE_STAINED_GLASS,
            cos(a) * r, sin(a) * r * 1.25, sin(a * 2) * 0.16,
            (0.24 + charge * 0.28) * (1.0 - release * 0.25),
            0.75 + charge * 1.05 + release * 0.75, 0.34 + charge * 0.30,
            yaw = a * 0.14, pitch = 0.12 * sin(a), roll = a + 0.20)
    }
    repeat(8) { i ->
        val a = i * PI / 4.0 + spin * 0.4
        val r = 0.35 + charge * 0.60 + release * reach * 0.56 + drift
        val size = 0.30 + charge * 0.28
        parts += part(if (i % 3 == 0) Material.AMETHYST_BLOCK else Material.MAGENTA_STAINED_GLASS,
            cos(a) * r, sin(a) * r, sin(a * 3.0) * (0.18 + release * 0.48),
            size, 0.72 + charge * 0.52 + release * 0.30, size * 0.72,
            yaw = a * 0.2, pitch = sin(a) * 0.25, roll = a - PI / 2)
    }
    repeat(4) { i ->
        val a = i * PI / 2 + PI / 4 + spin
        val r = 0.22 + charge * 0.52 + release * reach * 0.42 + drift
        parts += part(Material.PURPLE_STAINED_GLASS,
            cos(a) * r, sin(a) * r * 0.9, -0.20,
            0.35 + charge * 0.25, 1.05 + charge * 0.40, 0.32,
            yaw = a * 0.12, roll = a)
    }
    return parts
}

private fun singularityDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean,
    animationAgeTicks: Int): List<StaffDisplayPart> {
    val charge = if (impact) 1.0 else smooth(ageTicks / 8.0)
    val collapse = if (impact) smooth(ageTicks / 10.0) else 0.0
    val reach = extent(radius, 8.0, 3.5)
    val diskRadius = (0.8 + reach * 0.36 * charge) * (1.0 - collapse * 0.90)
    val spin = animationAgeTicks * 0.085
    val parts = ArrayList<StaffDisplayPart>(29)
    // Rotated dark solids form a silhouette; the luminous tilted accretion disk supplies contrast.
    repeat(7) { i ->
        val a = i * PI * 2 / 7
        val size = (0.80 + charge * 0.48) * (1.0 - collapse * 0.86)
        parts += part(if (i == 6) Material.CRYING_OBSIDIAN else Material.BLACK_CONCRETE,
            cos(a) * 0.09, 1.6 + sin(a) * 0.09, sin(a * 2) * 0.07,
            size, size * 0.94, size, yaw = a, pitch = 0.38 + i * 0.15, roll = a * 0.5)
    }
    repeat(16) { i ->
        val a = i * PI / 8 + spin
        val x = cos(a) * diskRadius
        val z = sin(a) * diskRadius
        parts += part(if (i % 4 == 0) Material.SEA_LANTERN else if (i % 2 == 0) Material.MAGENTA_STAINED_GLASS else Material.PURPLE_STAINED_GLASS,
            x, 1.6 + z * 0.48, z,
            (0.58 + charge * 0.36) * (1.0 - collapse * 0.85), 0.18,
            (0.36 + charge * 0.24) * (1.0 - collapse * 0.85), yaw = -a, pitch = 0.42)
    }
    repeat(6) { i ->
        val a = i * PI / 3 - spin * 0.65
        val r = diskRadius * (1.28 + 0.14 * (i % 2))
        val size = (0.24 + charge * 0.16) * (1.0 - collapse * 0.9)
        parts += part(if (i % 2 == 0) Material.CRYING_OBSIDIAN else Material.AMETHYST_BLOCK,
            cos(a) * r, 1.6 + sin(a * 2) * 0.84, sin(a) * r,
            size, size * 1.3, size * 1.7, yaw = a, pitch = a * 0.7, roll = spin)
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

/** Visual only: lower the safe muzzle below the reticle, then join the actual ray at the head. */
internal fun staffLanceCenterOffset(distance: Double, length: Double): Double {
    val reach = staffLanceReach(length)
    val muzzle = minOf(reach, 2.0)
    val travel = reach - muzzle
    if (travel <= 0.001) return 0.0
    val current = distance.takeIf { it.isFinite() }?.coerceIn(muzzle, reach) ?: muzzle
    return -0.75 * ((reach - current) / travel).coerceIn(0.0, 1.0)
}

/** One deterministic yaw/pitch frame for the LANCE display and its particle accents. */
internal fun staffLanceOrientation(direction: Vector3f): Quaternionf {
    val x = direction.x.toDouble()
    val y = direction.y.toDouble()
    val z = direction.z.toDouble()
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || hypot(hypot(x, z), y) < 1.0e-8)
        return Quaternionf()
    val horizontal = hypot(x, z)
    val yaw = if (horizontal < 1.0e-8) 0.0 else atan2(x, z)
    val elevation = atan2(y, horizontal)
    return Quaternionf().rotationY(yaw.toFloat()).rotateX(-elevation.toFloat())
}

private fun emberDisplayParts(ageTicks: Int, durationTicks: Int, radius: Double, impact: Boolean): List<StaffDisplayPart> {
    val width = extent(radius, 8.0, 0.85)
    val phase = progress(ageTicks, durationTicks)
    val parts = ArrayList<StaffDisplayPart>(if (impact) 48 else 14)
    if (impact) {
        val expand = 1.0 - kotlin.math.exp(-ageTicks / 2.4)
        val core = 1.0 - smooth(phase / 0.40) * 0.86
        val distance = 0.35 + width * expand + ageTicks * 0.045
        parts += part(Material.SEA_LANTERN, 0.0, 0.04, 0.0,
            1.75 * core, 1.75 * core, 1.75 * core, roll = PI / 4)
        parts += part(Material.GOLD_BLOCK, 0.0, 0.10, 0.0,
            1.95 * core, 1.65 * core, 1.85 * core, roll = PI / 4)
        repeat(32) { index ->
            val y = (index + 0.5) / 32.0
            val radial = kotlin.math.sqrt(1.0 - y * y)
            val angle = index * 2.399963229728653
            val shard = (0.48 + width * 0.07) * (1.0 - phase * 0.70)
            val material = when {
                index % 8 == 0 -> Material.BLACKSTONE
                index % 3 == 0 -> Material.YELLOW_STAINED_GLASS
                else -> Material.ORANGE_STAINED_GLASS
            }
            parts += part(material,
                cos(angle) * radial * distance, 0.18 + y * distance + sin(phase * PI) * 0.45,
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
        0.42 * width, 0.42 * width, 0.66 * width, roll = PI / 4)
    parts += part(Material.ORANGE_STAINED_GLASS, 0.0, 0.0, -0.08,
        0.66 * width, 0.66 * width, 0.90 * width, roll = PI / 4)
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
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val parts = ArrayList<StaffDisplayPart>(42)
    repeat(14) { i ->
        val angle = i * 2.0 * PI / 14.0
        val start = Vector3f((cos(angle) * front).toFloat(), 0.25f, (sin(angle) * front).toFloat())
        val endAngle = angle + 2.0 * PI / 14.0
        val end = Vector3f((cos(endAngle) * front).toFloat(), 0.25f, (sin(endAngle) * front).toFloat())
        val crest = 0.9 + sin(i * 1.7) * 0.16
        // Wide translucent sweeping facets, a bright lip and a trailing shard: no stacked pillars.
        parts += link(Material.LIME_STAINED_GLASS, start, end, 0.64, crest)
            .let { it.copy(center = Vector3f(it.center).add(0f, (crest * 0.38).toFloat(), 0f),
                rotation = Quaternionf(it.rotation).rotateZ(-0.30f)) }
        val a = angle + PI / 14.0
        parts += part(if (i % 4 == 0) Material.SEA_LANTERN else Material.EMERALD_BLOCK,
            cos(a) * (front - 0.15), 0.78 + sin(i * 1.7) * 0.10, sin(a) * (front - 0.15),
            0.26, 0.24, 0.64 + front * 0.08, yaw = -a, roll = 0.2)
        parts += part(Material.CYAN_STAINED_GLASS,
            cos(a) * (front - 0.48), 0.35, sin(a) * (front - 0.48),
            0.45, 0.36, 0.80, yaw = -a, pitch = -0.45)
    }
    return parts
}

private fun tidalCrestDisplayParts(ageTicks: Int, durationTicks: Int, length: Double, radius: Double): List<StaffDisplayPart> {
    val reach = extent(length, 48.0, 12.0).coerceAtLeast(1.8)
    val front = 1.8 + staffWaveFront(ageTicks, reach - 1.8, 16)
    val halfWidth = extent(radius, 10.0, 5.5) * front / reach
    val parts = ArrayList<StaffDisplayPart>(39)
    repeat(13) { i ->
        val across = i / 6.0 - 1.0
        val x = across * halfWidth
        // A concave, continuous crescent sweeps forward; the crest curls over its luminous edge.
        val z = front - across * across * 0.9
        val height = 1.5 + (1.0 - across * across) * 0.80
        val width = halfWidth / 6.0 + 0.18
        parts += part(Material.LIME_STAINED_GLASS, x, height * 0.52, z,
            width, height, 0.62, yaw = across * -0.20, pitch = -0.38, roll = across * -0.12)
        parts += part(if (i % 4 == 0) Material.SEA_LANTERN else Material.EMERALD_BLOCK,
            x, height * 0.85, z - 0.28, width * 0.9, 0.28, 0.40,
            yaw = across * -0.24, pitch = -0.55)
        parts += part(Material.CYAN_STAINED_GLASS,
            x, 0.42, z - 0.68, width * 0.80, 0.36, 0.92,
            yaw = across * -0.20, pitch = -0.25, roll = across * 0.18)
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
    val travelTicks = ceil((reach - minOf(reach, 2.0)) / 8.0).toInt().coerceAtLeast(4)
    return 4 + travelTicks
}

internal fun staffLanceDuration(length: Double): Int = staffLanceFlightTicks(length) + 12

/** Lance holds at its safe muzzle for four ticks, then advances through the ray over 4–6 ticks. */
internal fun staffLanceFront(ageTicks: Int, length: Double): Double {
    val reach = staffLanceReach(length)
    val muzzle = minOf(reach, 2.0)
    val travelTicks = (staffLanceFlightTicks(length) - 4).coerceAtLeast(1)
    val progress = ((ageTicks.coerceAtLeast(0) - 4).toDouble() / travelTicks).coerceIn(0.0, 1.0)
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
