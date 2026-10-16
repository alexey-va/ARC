package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val STAFF_NOVA_HOLD_TICKS = 70
internal const val STAFF_NOVA_FAN_REACH = 18.0
internal const val STAFF_NOVA_FAN_HALF_ANGLE = .50
internal const val STAFF_NOVA_FAN_START_TICK = 2
internal const val STAFF_NOVA_CONTACT_TICK = 14
internal const val STAFF_NOVA_FIELD_TICKS = 26
internal const val STAFF_NOVA_ATTACK_TICKS = STAFF_NOVA_CONTACT_TICK + STAFF_NOVA_HOLD_TICKS + 6
internal data class StaffRootBranch(val key: String, val points: List<Vector3f>)
internal data class StaffRootWrap(val key: String, val feet: Vector3f, val radius: Double, val height: Double,
    val ageOffset: Int = 0)

/** Flat reference paths; runtime projects these same knots onto physical ground and clips at cover. */
internal fun staffNovaFanPaths(reach: Double): List<StaffRootBranch> = (0..6).map { lane ->
    val across = (lane - 3) / 3.0
    StaffRootBranch("lane-$lane", (0..4).map { knot ->
        val t = knot / 4.0
        val distance = .75 + (reach.coerceAtLeast(.75) - .75) * t
        val angle = across * STAFF_NOVA_FAN_HALF_ANGLE + sin(t * PI) * sin(lane * 1.8) * .045
        Vector3f((sin(angle) * distance).toFloat(), .12f, (cos(angle) * distance).toFloat())
    })
}

internal fun staffNovaSnarePaths(radius: Double): List<StaffRootBranch> = (0..7).map { root ->
    val angle = root * PI / 4 + sin(root * 1.7) * .08
    StaffRootBranch("claw-$root", (0..3).map { knot ->
        val t = knot / 3.0
        val bend = angle + sin(t * PI) * (if (root % 2 == 0) .24 else -.20)
        val distance = radius.coerceAtLeast(.5) * (1.0 - t * .78)
        Vector3f((cos(bend) * distance).toFloat(), .12f, (sin(bend) * distance).toFloat())
    })
}

internal fun staffNovaFanFront(age: Int, reach: Double): Double = .75 +
    (reach.coerceAtLeast(.75) - .75) * ((age - STAFF_NOVA_FAN_START_TICK).toDouble() /
        (STAFF_NOVA_CONTACT_TICK - STAFF_NOVA_FAN_START_TICK)).coerceIn(0.0, 1.0)

/** Seven thick, ground-sampled roots carry one readable front; hooks never imply a captured target. */
internal fun staffNovaFanParts(age: Int, duration: Int, reach: Double,
    paths: List<StaffRootBranch>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val fade = rootSmooth((duration - age) / 6.0)
    val parts = mutableListOf<StaffDisplayPart>()
    val front = staffNovaFanFront(age, reach) - .75
    paths.take(7).forEachIndexed { lane, path ->
        val points = path.points.take(5)
        if (points.size < 2) return@forEachIndexed
        var passed = 0.0
        var tip = Vector3f(points.first())
        var heading = Vector3f(points[1]).sub(points.first()).normalize()
        points.zipWithNext().forEachIndexed { joint, (from, to) ->
            val length = from.distance(to).toDouble().coerceAtLeast(.001)
            val grow = ((front - passed) / length).coerceIn(0.0, 1.0)
            val end = Vector3f(from).lerp(to, grow.toFloat())
            parts += rootLink("root:fan:${path.key}:$joint", from, end,
                (.42 - joint * .038 + (lane % 2) * .035) * fade * grow,
                if ((lane + joint) % 3 == 1) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD)
            if (front >= passed) {
                tip = end
                heading = Vector3f(to).sub(from).normalize()
            }
            passed += length
        }
        val lift = rootSmooth(front / 2.0) * fade
        val fork = Vector3f(tip).add(heading.x * .30f, (.68 * lift).toFloat(), heading.z * .30f)
        val hook = Vector3f(fork).add(heading.x * .38f, (-.24 * lift).toFloat(), heading.z * .38f)
        parts += rootLink("root:fan:${path.key}:hook:0", tip, fork, .23 * lift, Material.MOSS_BLOCK)
        parts += rootLink("root:fan:${path.key}:hook:1", fork, hook, .13 * lift, Material.FLOWERING_AZALEA_LEAVES)
    }
    return parts
}

/** Perimeter buds warn for eight ticks, then eight ground claws sweep inward together. */
internal fun staffNovaSnareParts(age: Int, duration: Int, paths: List<StaffRootBranch>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val prepare = rootSmooth(age / 8.0)
    val close = rootSmooth((age - 8.0) / (STAFF_NOVA_CONTACT_TICK - 8))
    val progress = .18 * prepare + .82 * close
    val fade = rootSmooth((duration - age) / 6.0)
    return paths.take(8).flatMap { path ->
        val points = path.points.take(4)
        if (points.size < 2) return@flatMap emptyList()
        val body = points.zipWithNext().mapIndexed { joint, (from, to) ->
            val grow = (progress * (points.size - 1) - joint).coerceIn(0.0, 1.0)
            rootLink("root:snare:${path.key}:$joint", from, Vector3f(from).lerp(to, grow.toFloat()),
                (.38 - joint * .055) * fade * grow,
                if (joint == 1) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD)
        }
        val pathPosition = progress * (points.size - 1)
        val segment = pathPosition.toInt().coerceIn(0, points.size - 2)
        val tip = Vector3f(points[segment]).lerp(points[segment + 1], (pathPosition - segment).toFloat())
        val heading = Vector3f(points.last()).sub(points.first()).normalize()
        val lift = (.18 * prepare + .75 * close) * fade
        val fork = Vector3f(tip).add(heading.x * .18f, lift.toFloat(), heading.z * .18f)
        val hook = Vector3f(fork).add(heading.x * .50f, (-.35 * lift).toFloat(), heading.z * .50f)
        body + rootLink("root:snare:${path.key}:hook:0", tip, fork, .24 * prepare * fade, Material.MOSS_BLOCK) +
            rootLink("root:snare:${path.key}:hook:1", fork, hook, .14 * prepare * fade, Material.FLOWERING_AZALEA_LEAVES)
    }
}

internal fun staffNovaFieldParticles(age: Int, secondary: Boolean, reach: Double,
    paths: List<StaffRootBranch>): List<StaffParticleSample> {
    if (age !in 2..STAFF_NOVA_CONTACT_TICK) return emptyList()
    val parts = if (secondary) staffNovaSnareParts(age, STAFF_NOVA_FIELD_TICKS, paths)
        else staffNovaFanParts(age, STAFF_NOVA_FIELD_TICKS, reach, paths)
    return parts.filter { it.identity?.endsWith(":hook:1") == true }.map { part ->
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION,
            Vector(part.center.x.toDouble(), part.center.y.toDouble(), part.center.z.toDouble()),
            data = staffFade(0xc4ec87, 0x315830, 1.0f))
    }
}

/** Two crooked climbing vines per target; eight targets fit one 48-part scene. */
internal fun staffNovaWrapParts(age: Int, duration: Int, targets: List<StaffRootWrap>): List<StaffDisplayPart> {
    return targets.take(8).flatMap { target ->
        val localAge = age - target.ageOffset
        if (localAge < 0 || localAge >= duration) return@flatMap emptyList()
        val grow = rootSmooth((localAge + 1) / 5.0)
        val fade = rootSmooth((duration - localAge) / 6.0)
        (0 until 2).flatMap { vine ->
            fun point(t: Double): Vector3f {
                val angle = vine * PI + t * PI * 1.35 + sin(t * PI) * .18
                val r = target.radius * (1.42 - grow * .46 + sin(t * PI) * .05)
                return Vector3f(target.feet).add((cos(angle) * r).toFloat(),
                    (.10 + t * target.height * .82 * grow).toFloat(), (sin(angle) * r).toFloat())
            }
            (0 until 3).map { joint ->
                rootLink("root:wrap:${target.key}:$vine:$joint", point(joint / 3.0), point((joint + 1) / 3.0),
                    (.27 - joint * .045) * grow * fade,
                    when (joint) { 0 -> Material.DARK_OAK_WOOD; 1 -> Material.MOSS_BLOCK; else -> Material.FLOWERING_AZALEA_LEAVES })
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
