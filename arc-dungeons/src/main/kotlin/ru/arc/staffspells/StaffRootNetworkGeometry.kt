package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val STAFF_NOVA_HOLD_TICKS = 70
internal data class StaffRootBranch(val key: String, val points: List<Vector3f>)
internal data class StaffRootWrap(val key: String, val feet: Vector3f, val radius: Double, val height: Double)

/** Runtime supplies sampled floor points. One trunk and sixteen two-link branches fit one scene. */
internal fun staffNovaNetworkParts(age: Int, duration: Int, contactTick: Int,
    trunk: List<Vector3f>, branches: List<StaffRootBranch>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val fade = rootSmooth((duration - age) / 6.0)
    val parts = mutableListOf<StaffDisplayPart>()
    fun path(key: String, points: List<Vector3f>, start: Double, end: Double, width: Double) {
        points.zipWithNext().forEachIndexed { i, (from, to) ->
            val growth = rootSmooth((age - start - i * (end - start) / (points.size - 1)) /
                ((end - start) / (points.size - 1)).coerceAtLeast(2.0))
            val tip = Vector3f(from).lerp(to, growth.toFloat())
            parts += rootLink("root:network:$key:$i", from, tip, width * fade * growth,
                if (i % 3 == 1) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD)
        }
    }
    path("trunk", trunk.take(13), 0.0, (contactTick - 8).toDouble(), .30)
    branches.take(16).forEach { path(it.key, it.points.take(3),
        if (trunk.isEmpty()) 0.0 else (contactTick - 10).toDouble(), contactTick.toDouble(), .22) }
    return parts
}

/** Two crooked climbing vines per target; eight targets fit one 48-part scene. */
internal fun staffNovaWrapParts(age: Int, duration: Int, targets: List<StaffRootWrap>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val grow = rootSmooth((age + 2) / 6.0)
    val fade = rootSmooth((duration - age) / 6.0)
    return targets.take(8).flatMap { target ->
        (0 until 2).flatMap { vine ->
            fun point(t: Double): Vector3f {
                val angle = vine * PI + t * PI * 1.60 + sin(t * PI) * .18
                val r = target.radius * (1.12 - grow * .16 + sin(t * PI) * .05)
                return Vector3f(target.feet).add((cos(angle) * r).toFloat(),
                    (.10 + t * target.height * grow).toFloat(), (sin(angle) * r).toFloat())
            }
            (0 until 3).map { joint ->
                rootLink("root:wrap:${target.key}:$vine:$joint", point(joint / 3.0), point((joint + 1) / 3.0),
                    (.20 - joint * .035) * grow * fade,
                    if (joint == 2) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK)
            }
        }
    }
}

private fun rootLink(id: String, from: Vector3f, to: Vector3f, width: Double, material: Material): StaffDisplayPart {
    val delta = Vector3f(to).sub(from)
    val length = delta.length().coerceAtLeast(.001f)
    val rotation = if (delta.lengthSquared() > 1e-8f)
        Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()) else Quaternionf()
    return StaffDisplayPart(material, Vector3f(from).add(to).mul(.5f),
        Vector3f(width.coerceAtLeast(.001).toFloat(), width.coerceAtLeast(.001).toFloat(), length * 1.04f), rotation, identity = id)
}
private fun rootSmooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
