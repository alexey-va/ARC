package ru.arc.staffspells

import org.bukkit.Location
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/** Follow connected support, stepping over a block face rather than tracing through it. */
internal fun staffRootSurfacePath(knots: List<Location>, targeting: StaffSpellTargeting): List<Location> {
    val result = mutableListOf<Location>()
    fun append(point: Location) {
        if (result.lastOrNull()?.distanceSquared(point)?.let { it < 1e-8 } == true) return
        if (result.size >= 2) {
            val a = result.last().toVector().subtract(result[result.lastIndex - 1].toVector())
            val b = point.toVector().subtract(result.last().toVector())
            if (a.lengthSquared() > 1e-8 && b.lengthSquared() > 1e-8 && a.normalize().dot(b.normalize()) > .99999)
                result.removeAt(result.lastIndex)
        }
        result += point
    }
    for ((index, knot) in knots.withIndex()) {
        val start = if (index == 0) knot else knots[index - 1]
        val delta = knot.toVector().subtract(start.toVector())
        val samples = ceil(hypot(delta.x, delta.z) / .4).toInt().coerceAtLeast(1)
        for (sample in 1..samples) {
            val at = start.clone().add(delta.clone().multiply(sample.toDouble() / samples))
            val prior = result.lastOrNull()
            at.y = (prior?.y ?: knot.y) + 1.12
            val floor = targeting.groundBelow(at, 2.55)?.add(0.0, .12, 0.0) ?: return result
            if (!visible(floor, floor.clone().add(0.0, .3, 0.0))) return result
            if (prior != null) {
                if (abs(prior.y - floor.y) > 1.26) return result
                if (!visible(prior, floor)) {
                    val corner = if (floor.y > prior.y) prior.clone().apply { y = floor.y }
                        else floor.clone().apply { y = prior.y }
                    if (!visible(prior, corner) || !visible(corner, floor)) return result
                    append(corner)
                }
            }
            append(floor)
        }
    }
    return result
}
