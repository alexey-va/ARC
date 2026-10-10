package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fixed-slot solar piercer geometry. A compact, lowered muzzle charges for four ticks; a bright
 * faceted channel then advances over the already-shared LANCE flight clock. Four transverse
 * coronas flare as the front passes, and the head opens into a short-lived contact starburst.
 */
@Suppress("UNUSED_PARAMETER")
internal fun staffSolarDisplayParts(
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    impact: Boolean,
): List<StaffDisplayPart> {
    val reach = solarReach(length)
    val muzzle = minOf(reach, 2.0)
    val travel = (reach - muzzle).coerceAtLeast(0.0)
    val front = staffLanceFront(ageTicks, reach)
    val age = ageTicks.coerceAtLeast(0)
    val arrival = staffLanceFlightTicks(reach)
    val burst = if (age < arrival) 0.0 else solarSmooth((age - arrival) / 3.0)
    val parts = MutableList(SOLAR_PART_COUNT) { index ->
        val material = solarMaterial(index)
        StaffDisplayPart(material, Vector3f(), Vector3f(0.001f), Quaternionf(), visible = false)
    }

    fun activate(index: Int, part: StaffDisplayPart) {
        parts[index] = part.copy(visible = true)
    }

    fun path(distance: Double) = Vector3f(0f, staffLanceCenterOffset(distance, reach).toFloat(), distance.toFloat())

    // Sixteen thick, contiguous facets make one bright channel without a long orbiting wire.
    val segmentLength = travel / SOLAR_CHANNEL_SLOTS
    repeat(SOLAR_CHANNEL_SLOTS) { index ->
        val startDistance = muzzle + segmentLength * index
        val endDistance = if (index == SOLAR_CHANNEL_SLOTS - 1) reach else muzzle + segmentLength * (index + 1)
        val activeEnd = minOf(endDistance, front)
        val middle = (startDistance + endDistance) * 0.5
        val flare = solarSmooth((middle - muzzle) / 8.0)
        val width = 0.20 + flare * 0.46
        val height = 0.18 + flare * 0.40
        val full = solarLink(solarMaterial(index), path(startDistance), path(endDistance), width, height)
        if (travel > 0.25 && activeEnd - startDistance >= 0.10) {
            val direction = Vector3f(path(activeEnd)).sub(path(startDistance)).normalize()
            val visibleStart = Vector3f(path(startDistance)).fma(0.025f, direction)
            val visibleEnd = Vector3f(path(activeEnd)).fma(-0.025f, direction)
            activate(index, solarLink(solarMaterial(index), visibleStart, visibleEnd, width, height))
        } else {
            parts[index] = full.copy(scale = Vector3f(0.001f), visible = false)
        }
    }

    // Six separated crystals compose each transverse halo; the slots wake only after the front
    // reaches their fixed station, so early poses never contain future ray length.
    repeat(SOLAR_CORONA_COUNT) { ring ->
        val station = if (travel <= 0.001) reach else muzzle + travel * (ring + 1) / (SOLAR_CORONA_COUNT + 1)
        val flare = if (travel <= 0.001) 0.0 else solarSmooth((station - muzzle) / travel)
        val passed = solarSmooth((front - station + 1.5) / 2.5)
        val ringRadius = (0.38 + flare * 0.54) * (0.62 + passed * 0.38)
        repeat(SOLAR_CORONA_FACETS) { facet ->
            val index = SOLAR_CHANNEL_SLOTS + ring * SOLAR_CORONA_FACETS + facet
            val angle = facet * 2.0 * PI / SOLAR_CORONA_FACETS + ring * 0.19
            val x = cos(angle) * ringRadius
            val y = staffLanceCenterOffset(station, reach) + sin(angle) * ringRadius
            val tangentLength = 0.34 + flare * 0.12
            val part = StaffDisplayPart(
                solarMaterial(index),
                Vector3f(x.toFloat(), y.toFloat(), station.toFloat()),
                Vector3f(tangentLength.toFloat(), (0.15 + flare * 0.035).toFloat(), 0.20f),
                Quaternionf().rotationZ((angle + PI / 2.0).toFloat()),
            )
            if (travel >= 2.0 && age >= 5 && front >= station - 0.001) activate(index, part)
            else parts[index] = part.copy(scale = Vector3f(0.001f), visible = false)
        }
    }

    // A tiny, stable head during flight becomes a radial flare only after reaching the endpoint.
    val headY = staffLanceCenterOffset(front, reach)
    val headScale = 0.30 + 0.14 * solarSmooth((front - muzzle) / travel.coerceAtLeast(0.001)) + burst * 0.06
    activate(SOLAR_HEAD_START, StaffDisplayPart(
        Material.SEA_LANTERN, Vector3f(0f, headY.toFloat(), front.toFloat()),
        Vector3f(headScale.toFloat(), (headScale * 0.92).toFloat(), (headScale * 1.12).toFloat()),
        Quaternionf().rotationZ((PI / 4.0).toFloat()),
    ))
    repeat(SOLAR_HEAD_FACETS - 1) { facet ->
        val index = SOLAR_HEAD_START + facet + 1
        val angle = facet * 2.0 * PI / (SOLAR_HEAD_FACETS - 1)
        val spread = 0.08 + burst * 0.92
        val size = 0.18 + burst * 0.22
        activate(index, StaffDisplayPart(
            solarMaterial(index),
            Vector3f((cos(angle) * spread).toFloat(), (headY + sin(angle) * spread).toFloat(), (front - burst * 0.10).toFloat()),
            Vector3f(size.toFloat(), (size * 1.12).toFloat(), (size * 0.82).toFloat()),
            Quaternionf().rotateY(angle.toFloat()).rotateZ((angle + PI / 5.0).toFloat()),
        ))
    }

    // Four pinprick facets stay below the reticle during charge, then disappear as the head leaves.
    val muzzleFade = 1.0 - solarSmooth((age - 4.0) / 2.0)
    repeat(SOLAR_MUZZLE_FACETS) { facet ->
        val index = SOLAR_MUZZLE_START + facet
        val angle = facet * PI / 2.0 + PI / 4.0
        val radius = 0.16
        val size = 0.12 * muzzleFade
        val part = StaffDisplayPart(
            solarMaterial(index),
            Vector3f((cos(angle) * radius).toFloat(), (staffLanceCenterOffset(muzzle, reach) + sin(angle) * radius).toFloat(), muzzle.toFloat()),
            Vector3f(size.toFloat(), (size * 0.82).toFloat(), (size * 1.1).toFloat()),
            Quaternionf().rotationZ(angle.toFloat()),
        )
        if (age <= 5) activate(index, part)
        else parts[index] = part.copy(scale = Vector3f(0.001f), visible = false)
    }

    return parts
}

private const val SOLAR_CHANNEL_SLOTS = 16
private const val SOLAR_CORONA_COUNT = 4
private const val SOLAR_CORONA_FACETS = 6
private const val SOLAR_HEAD_START = SOLAR_CHANNEL_SLOTS + SOLAR_CORONA_COUNT * SOLAR_CORONA_FACETS
private const val SOLAR_HEAD_FACETS = 4
private const val SOLAR_MUZZLE_START = SOLAR_HEAD_START + SOLAR_HEAD_FACETS
private const val SOLAR_MUZZLE_FACETS = 4
private const val SOLAR_PART_COUNT = SOLAR_MUZZLE_START + SOLAR_MUZZLE_FACETS

private fun solarMaterial(index: Int) = when (index) {
    SOLAR_HEAD_START -> Material.SEA_LANTERN
    SOLAR_HEAD_START + 1 -> Material.GOLD_BLOCK
    SOLAR_HEAD_START + 2 -> Material.WHITE_STAINED_GLASS
    SOLAR_HEAD_START + 3 -> Material.YELLOW_STAINED_GLASS
    else -> when (index % 5) {
        0 -> Material.GOLD_BLOCK
        1 -> Material.YELLOW_STAINED_GLASS
        2 -> Material.ORANGE_STAINED_GLASS
        3 -> Material.SEA_LANTERN
        else -> Material.WHITE_STAINED_GLASS
    }
}

private fun solarReach(length: Double): Double =
    length.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.1, 48.0) ?: 0.1

private fun solarLink(material: Material, start: Vector3f, end: Vector3f,
    width: Double, height: Double): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val segment = delta.length().coerceAtLeast(0.001f)
    val rotation = if (delta.lengthSquared() > 0.000001f)
        Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize()) else Quaternionf()
    return StaffDisplayPart(material, Vector3f(start).add(end).mul(0.5f),
        Vector3f(width.toFloat(), height.toFloat(), segment), rotation)
}

private fun solarSmooth(value: Double): Double {
    val t = value.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}
