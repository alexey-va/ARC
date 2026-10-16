package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

internal data class StaffOssuaryRib(
    val slot: Int,
    val curve: List<Vector3f>,
    val fork: List<Vector3f>,
    val width: Double,
)

internal data class StaffOssuaryShape(val ribs: List<StaffOssuaryRib>)

/** Generate once at cluster birth; frame sampling only grows this fixed skeleton. */
internal fun staffOssuaryShape(attackId: UUID, nodeIndex: Int = 0): StaffOssuaryShape {
    val random = Random(attackId.mostSignificantBits xor attackId.leastSignificantBits xor
        (nodeIndex.toLong() * -7046029254386353131L))
    // Separate angular sectors keep the near cluster's central aiming window open.
    val angles = doubleArrayOf(-2.95, -2.30, -.32, .74, 2.40)
    val omitted = if (random.nextBoolean()) listOf(0, 1, 4).random(random) else -1
    val slots = angles.indices.filter { it != omitted }
    val forked = slots.shuffled(random).take(random.nextInt(2, slots.size + 1)).toSet()
    return StaffOssuaryShape(slots.map { slot ->
        val angle = angles[slot] + random.nextDouble(-.11, .11)
        val base = random.nextDouble(.78, .85)
        val inward = random.nextDouble(.28, .43)
        val bend = random.nextDouble(.65, 1.65)
        val twist = random.nextDouble(-.16, .16)
        val height = random.nextDouble(.78, 1.23)
        val hook = random.nextDouble(.54, .74)
        val joints = listOf(0.0, random.nextDouble(.17, .31), random.nextDouble(.40, .59),
            random.nextDouble(.67, .84), 1.0)
        fun point(t: Double): Vector3f {
            val radius = base - inward * sin(t * PI * .5).pow(bend)
            val a = angle + twist * sin(t * PI)
            return Vector3f((cos(a) * radius).toFloat(),
                (sin(t * PI * hook) * height).toFloat(), (sin(a) * radius).toFloat())
        }
        val curve = joints.map(::point)
        val fork = if (slot in forked) {
            val at = random.nextDouble(.28, .58)
            val joint = joints.indexOfLast { it <= at }.coerceAtMost(joints.lastIndex - 1)
            val anchor = Vector3f(curve[joint]).lerp(curve[joint + 1],
                ((at - joints[joint]) / (joints[joint + 1] - joints[joint])).toFloat())
            val side = if (anchor.x < 0f) -1.0 else 1.0
            val reach = random.nextDouble(.10, .22)
            val sweep = random.nextDouble(-.16, .16)
            fun bounded(point: Vector3f): Vector3f {
                val radius = hypot(point.x.toDouble(), point.z.toDouble())
                if (radius > .86) {
                    point.x *= (.86 / radius).toFloat()
                    point.z *= (.86 / radius).toFloat()
                }
                return point
            }
            val elbow = bounded(Vector3f(anchor).add((side * reach).toFloat(),
                random.nextDouble(.08, .19).toFloat(), sweep.toFloat()))
            val tip = bounded(Vector3f(elbow).add((-side * reach * random.nextDouble(.12, .55)).toFloat(),
                random.nextDouble(.10, .25).toFloat(), (sweep * random.nextDouble(-.4, .8)).toFloat()))
            listOf(anchor, elbow, tip)
        } else emptyList()
        StaffOssuaryRib(slot, curve, fork, random.nextDouble(.25, .35))
    })
}

private val DEFAULT_OSSUARY_SHAPE = staffOssuaryShape(UUID(0L, 0L))

/** Unequal ribs curl inward and split into crooked forks; at most thirty stable parts. */
internal fun staffOssuaryRibParts(age: Int, duration: Int, radius: Double, secondary: Boolean,
    charge: Double = 0.0, shape: StaffOssuaryShape = DEFAULT_OSSUARY_SHAPE): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val extent = radius.takeIf(Double::isFinite)?.coerceIn(.5, 16.0) ?: 2.75
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val fade = ribSmooth((duration - 2.0 - age) / 6.0)
    return shape.ribs.flatMap { rib ->
        val delay = if (secondary) OSSUARY_SECONDARY_ERUPT_TICK + rib.slot % 3 else rib.slot % 2
        val rise = ribSmooth((age - delay) / 4.0)
        val height = (if (secondary) 3.7 else 2.65) * (1.0 + q * .08)
        fun point(point: Vector3f) = Vector3f(point.x * extent.toFloat(),
            (-.10 + point.y * height * rise).toFloat(), point.z * extent.toFloat())
        fun link(id: String, start: Vector3f, end: Vector3f, width: Double, material: Material): StaffDisplayPart {
            val delta = Vector3f(end).sub(start)
            val length = delta.length().coerceAtLeast(.001f)
            return StaffDisplayPart(material, Vector3f(start).add(end).mul(.5f),
                Vector3f((width * rise * fade).coerceAtLeast(.001).toFloat(),
                    (width * rise * fade).coerceAtLeast(.001).toFloat(), (length * fade * 1.05).coerceAtLeast(.001).toFloat()),
                if (delta.lengthSquared() > 1e-8f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()) else Quaternionf(),
                identity = "bone:rib:${rib.slot}:$id")
        }
        val width = rib.width * if (secondary) 1.25 else 1.0
        val main = (0 until 4).map { joint ->
            link("curve:$joint", point(rib.curve[joint]), point(rib.curve[joint + 1]),
                width * (1.0 - joint * .19), if (joint == 3) Material.CALCITE else Material.BONE_BLOCK)
        }
        if (rib.fork.isEmpty()) main else main +
            link("fork", point(rib.fork[0]), point(rib.fork[1]), width * .53, Material.BONE_BLOCK) +
            link("tine", point(rib.fork[1]), point(rib.fork[2]), width * .30, Material.CALCITE)
    }
}

private fun ribSmooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
