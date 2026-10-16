package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Unequal ribs grow from the ground, curl inward, and throw a crooked side fork. */
internal fun staffOssuaryRibParts(age: Int, duration: Int, radius: Double, secondary: Boolean,
    charge: Double = 0.0): List<StaffDisplayPart> {
    if (age >= duration) return emptyList()
    val extent = radius.takeIf(Double::isFinite)?.coerceIn(.5, 16.0) ?: 2.75
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val fade = ribSmooth((duration - 2.0 - age) / 6.0)
    // The nearest primary rib bends beside the aim line, leaving a window through the field.
    val angles = doubleArrayOf(-2.92, if (secondary) -1.71 else -2.18, -.36, .91, 2.26)
    return angles.flatMapIndexed { rib, angle ->
        val delay = if (secondary) OSSUARY_SECONDARY_ERUPT_TICK + rib % 3 else rib % 2
        val rise = ribSmooth((age - delay).toDouble() / if (secondary) 4.0 else 4.0)
        val height = (if (secondary) 3.7 else 2.65) * (1.0 + sin(rib * 1.9) * .18 + q * .08)
        val curl = .14 + sin(rib * 1.4) * .20
        fun point(t: Double): Vector3f {
            val r = extent * (.82 - .53 * sin(t * PI * .5)) * (.94 + sin(rib * 2.1) * .04)
            val a = angle + curl * sin(t * PI)
            return Vector3f((cos(a) * r).toFloat(),
                (-.10 + sin(t * PI * .64) * height * rise).toFloat(), (sin(a) * r).toFloat())
        }
        fun link(id: String, start: Vector3f, end: Vector3f, width: Double, material: Material): StaffDisplayPart {
            val delta = Vector3f(end).sub(start)
            val length = delta.length().coerceAtLeast(.001f)
            return StaffDisplayPart(material, Vector3f(start).add(end).mul(.5f),
                Vector3f((width * rise * fade).coerceAtLeast(.001).toFloat(),
                    (width * rise * fade).coerceAtLeast(.001).toFloat(), (length * fade * 1.05).coerceAtLeast(.001).toFloat()),
                if (delta.lengthSquared() > 1e-8f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.normalize()) else Quaternionf(),
                identity = "bone:rib:$rib:$id")
        }
        val main = (0 until 4).map { joint ->
            link("curve:$joint", point(joint / 4.0), point((joint + 1) / 4.0),
                (.30 - joint * .055) * (if (secondary) 1.25 else 1.0),
                if (joint == 3) Material.CALCITE else Material.BONE_BLOCK)
        }
        val anchor = point(.38 + rib % 2 * .11)
        val sign = if (rib % 2 == 0 || !secondary && rib == 1) -1.0 else 1.0
        val fork = Vector3f(anchor).add((-sin(angle) * sign * extent * .17).toFloat(),
            (.32 * rise).toFloat(), (cos(angle) * sign * extent * .17).toFloat())
        val tip = Vector3f(fork).add((cos(angle + .5) * extent * .10).toFloat(),
            (.48 * rise).toFloat(), (sin(angle + .5) * extent * .10).toFloat())
        main + link("fork", anchor, fork, .16, Material.BONE_BLOCK) +
            link("tine", fork, tip, .09, Material.CALCITE)
    }
}

private fun ribSmooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3.0 - 2.0 * it) }
