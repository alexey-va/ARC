package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.sin

internal fun staffPrismTravelDistance(age: Int, points: List<Vector3f>): Double =
    points.zipWithNext().sumOf { (a, b) -> a.distance(b).toDouble() } *
        (age / 14.0).coerceIn(0.0, 1.0)

/** Fixed mirror planes visibly explain every turn; one continuous front grows through the route. */
internal fun staffPrismRefractionParts(age: Int, duration: Int, points: List<Vector3f>): List<StaffDisplayPart> {
    if (age >= duration || points.size < 2) return emptyList()
    val route = points.take(9)
    val fade = ((duration - age) / 8.0).coerceIn(0.0, 1.0).toFloat()
    val travel = staffPrismTravelDistance(age, route)
    val parts = mutableListOf<StaffDisplayPart>()
    var passed = 0.0
    route.zipWithNext().forEachIndexed { i, (from, to) ->
        val delta = Vector3f(to).sub(from)
        val length = delta.length().coerceAtLeast(.001f)
        val front = ((travel - passed) / length).coerceIn(0.0, 1.0).toFloat()
        val rotation = if (delta.lengthSquared() > 1e-8f)
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()) else Quaternionf()
        val end = Vector3f(from).lerp(to, front)
        parts += StaffDisplayPart(if (i % 2 == 0) Material.WHITE_STAINED_GLASS else Material.PURPLE_STAINED_GLASS,
            Vector3f(from).add(end).mul(.5f), Vector3f(.20f * fade, .20f * fade, (length * front).coerceAtLeast(.001f)),
            rotation, identity = "prism:refract:beam:$i")
        passed += length
        if (i < route.lastIndex - 1) {
            val outgoing = Vector3f(route[i + 2]).sub(to).normalize()
            val incoming = Vector3f(to).sub(from).normalize()
            val normal = incoming.sub(outgoing)
            if (normal.lengthSquared() < 1e-8f) normal.set(0f, 1f, 0f)
            val mirror = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), normal.normalize())
            val pulse = if (travel >= passed) 1f + sin(age * .7).toFloat() * .09f else .86f
            parts += StaffDisplayPart(Material.AMETHYST_BLOCK, Vector3f(to),
                Vector3f(.24f, .90f, .24f).mul(fade), mirror, identity = "prism:refract:anchor:$i")
            parts += StaffDisplayPart(if (i % 2 == 0) Material.LIGHT_BLUE_STAINED_GLASS else Material.PINK_STAINED_GLASS,
                Vector3f(to), Vector3f(.95f * pulse, 1.30f * pulse, .10f).mul(fade), mirror,
                identity = "prism:refract:mirror:$i")
        }
    }
    return parts
}
