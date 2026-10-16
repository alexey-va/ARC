package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

internal const val STAFF_NOVA_HOLD_TICKS = 70
internal const val STAFF_NOVA_FAN_REACH = 18.0
internal const val STAFF_NOVA_FAN_HALF_ANGLE = .50
internal const val STAFF_NOVA_FAN_START_TICK = 2
internal const val STAFF_NOVA_CONTACT_TICK = 14
internal const val STAFF_NOVA_FIELD_TICKS = 26
internal const val STAFF_NOVA_ATTACK_TICKS = STAFF_NOVA_CONTACT_TICK + STAFF_NOVA_HOLD_TICKS + 6
internal data class StaffRootBranch(val key: String, val points: List<Vector3f>, val width: Double = 1.0,
    val parentKey: String? = null)
internal data class StaffRootWrap(val key: String, val feet: Vector3f, val radius: Double, val height: Double,
    val ageOffset: Int = 0)

/** A cast owns its seed: crooked branches vary between casts, never between animation frames. */
internal fun staffNovaFanPaths(reach: Double, seed: Long = 0L): List<StaffRootBranch> {
    val random = Random(seed)
    val roots = (0..6).map { lane ->
        val across = (lane - 3) / 3.0
        val bend = random.nextDouble(-.10, .10)
        val phase = random.nextDouble(0.0, PI * 2)
        val tip = random.nextDouble(-.018, .018)
        StaffRootBranch("lane-$lane", (0..5).map { knot ->
            val t = knot / 5.0
            val distance = .75 + (reach.coerceAtLeast(.75) - .75) * t
            val angle = across * STAFF_NOVA_FAN_HALF_ANGLE + tip * t +
                sin(t * PI) * (bend + sin(t * PI * 2.3 + phase) * .07)
            Vector3f((sin(angle) * distance).toFloat(), .12f, (cos(angle) * distance).toFloat())
        }, random.nextDouble(.87, 1.16))
    }
    return roots + listOf(1, 3, 5).map { index -> rootOffshoot(roots[index], random, index) }
}

internal fun staffNovaSnarePaths(radius: Double, seed: Long = 0L): List<StaffRootBranch> {
    val random = Random(seed xor 0x62495L)
    val roots = (0..7).map { root ->
        val angle = root * PI / 4 + random.nextDouble(-.13, .13)
        val curl = random.nextDouble(.22, .48) * if (root % 2 == 0) 1 else -1
        StaffRootBranch("claw-$root", (0..5).map { knot ->
            val t = knot / 5.0
            val bend = angle + sin(t * PI) * curl + sin(t * PI * 2) * .12
            val distance = radius.coerceAtLeast(.5) * (1.0 - t * .97)
            Vector3f((cos(bend) * distance).toFloat(), .12f, (sin(bend) * distance).toFloat())
        }, random.nextDouble(.86, 1.15))
    }
    return roots + listOf(0, 2, 4, 6).map { index -> rootOffshoot(roots[index], random, index) }
}

private fun rootOffshoot(parent: StaffRootBranch, random: Random, index: Int): StaffRootBranch {
    val start = Vector3f(parent.points[2 + index % 2])
    val direction = Vector3f(parent.points[3 + index % 2]).sub(start).normalize()
    val side = if (index % 3 == 0) -1f else 1f
    val sideways = Vector3f(direction.z * side, 0f, -direction.x * side)
    val length = random.nextDouble(1.05, 1.85).toFloat()
    return StaffRootBranch("${parent.key}:twig", listOf(start,
        Vector3f(start).add(Vector3f(direction).mul(length * .28f)).add(Vector3f(sideways).mul(length * .55f)),
        Vector3f(start).add(Vector3f(direction).mul(length * .9f)).add(Vector3f(sideways).mul(length))),
        .53, parent.key)
}

internal fun staffRootNearestPoint(path: StaffRootBranch, at: Vector3f): Vector3f? =
    path.points.zipWithNext().map { (from, to) ->
        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = dx * dx + dz * dz
        val t = if (length < 1e-8f) 1f else (((at.x - from.x) * dx + (at.z - from.z) * dz) / length).coerceIn(0f, 1f)
        Vector3f(from).lerp(to, t)
    }.minByOrNull { hypot((it.x - at.x).toDouble(), (it.z - at.z).toDouble()) }

internal fun staffNovaFanFront(age: Int, reach: Double): Double = .75 +
    (reach.coerceAtLeast(.75) - .75) * ((age - STAFF_NOVA_FAN_START_TICK).toDouble() /
        (STAFF_NOVA_CONTACT_TICK - STAFF_NOVA_FAN_START_TICK)).coerceIn(0.0, 1.0)

private data class RootGrowth(val body: List<StaffDisplayPart>, val tip: Vector3f, val heading: Vector3f)

private fun rootGrowth(path: StaffRootBranch, distance: Double, fade: Double, prefix: String,
    radialFront: Double? = null): RootGrowth {
    val lengths = path.points.zipWithNext().map { (from, to) -> hypot((to.x - from.x).toDouble(), (to.z - from.z).toDouble()) }
    val total = lengths.sum().coerceAtLeast(.001)
    var passed = 0.0
    var active = 0
    var tip = Vector3f(path.points.first())
    var heading = Vector3f(0f, 0f, 1f)
    val body = path.points.zipWithNext().mapIndexed { joint, (from, to) ->
        val length = lengths[joint]
        val startRadius = hypot(from.x.toDouble(), from.z.toDouble())
        val endRadius = hypot(to.x.toDouble(), to.z.toDouble())
        val grow = if (radialFront != null) {
            if (endRadius - startRadius < .001) if (radialFront >= startRadius) 1.0 else 0.0
            else ((radialFront - startRadius) / (endRadius - startRadius)).coerceIn(0.0, 1.0)
        } else if (length < .001) if (distance >= passed) 1.0 else 0.0
            else ((distance - passed) / length).coerceIn(0.0, 1.0)
        val end = Vector3f(from).lerp(to, grow.toFloat())
        val width = (.42 - .18 * passed / total) * path.width * fade * grow
        val part = rootLink("$prefix:${path.key}:$joint", from, end, width,
            if (joint % 3 == 1) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD)
        if (grow > 0) {
            active = joint
            tip = end
            val delta = Vector3f(to).sub(from)
            if (delta.lengthSquared() > 1e-8f) heading = delta.normalize()
        }
        passed += length
        part
    }
    // Burrow the old tail on very jagged terrain; two pooled scenes still fit the 96-part field budget.
    val segments = if (path.parentKey == null) 8 else 4
    val start = (active - segments + 1).coerceAtLeast(0)
    return RootGrowth(body.drop(start).take(segments), tip, heading)
}

/** Crooked surface roots share a swept radial front and keep their accepted victim wraps separate. */
internal fun staffNovaFanParts(age: Int, duration: Int, reach: Double,
    paths: List<StaffRootBranch>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val fade = rootSmooth((duration - age) / 6.0)
    val front = staffNovaFanFront(age, reach)
    return paths.flatMap { path ->
        if (path.points.size < 2) return@flatMap emptyList()
        val first = path.points.first()
        val grown = front - hypot(first.x.toDouble(), first.z.toDouble())
        val root = rootGrowth(path, grown, fade, "root:fan", front)
        if (path.parentKey != null) return@flatMap root.body
        val lift = rootSmooth(grown / 2.0) * fade
        val fork = Vector3f(root.tip).add(root.heading.x * .30f, (.68 * lift).toFloat(), root.heading.z * .30f)
        val hook = Vector3f(fork).add(root.heading.x * .38f, (-.24 * lift).toFloat(), root.heading.z * .38f)
        root.body + rootLink("root:fan:${path.key}:hook:0", root.tip, fork, .23 * lift, Material.MOSS_BLOCK) +
            rootLink("root:fan:${path.key}:hook:1", fork, hook, .13 * lift, Material.FLOWERING_AZALEA_LEAVES)
    }
}

/** Perimeter buds warn for eight ticks, then irregular claws and their offshoots close together. */
internal fun staffNovaSnareParts(age: Int, duration: Int, paths: List<StaffRootBranch>): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val prepare = rootSmooth(age / 8.0)
    val close = rootSmooth((age - 8.0) / (STAFF_NOVA_CONTACT_TICK - 8))
    val progress = .18 * prepare + .82 * close
    val fade = rootSmooth((duration - age) / 6.0)
    return paths.flatMap { path ->
        if (path.points.size < 2) return@flatMap emptyList()
        val length = path.points.zipWithNext().sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.z - a.z).toDouble()) }
        val root = rootGrowth(path, length * progress, fade, "root:snare")
        if (path.parentKey != null) return@flatMap root.body
        val lift = (.18 * prepare + .75 * close) * fade
        val fork = Vector3f(root.tip).add(root.heading.x * .18f, lift.toFloat(), root.heading.z * .18f)
        val hook = Vector3f(fork).add(root.heading.x * .50f, (-.35 * lift).toFloat(), root.heading.z * .50f)
        root.body + rootLink("root:snare:${path.key}:hook:0", root.tip, fork, .24 * prepare * fade, Material.MOSS_BLOCK) +
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
