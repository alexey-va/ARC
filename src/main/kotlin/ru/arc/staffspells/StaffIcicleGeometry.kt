package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun staffIcicleStep(age: Int): Double = minOf(2.4, 0.55 + age.coerceAtLeast(0) * 0.18)
internal fun staffIcicleAnchorOffset(index: Int) = Vector3f((index - 1) * 2.1f, if (index == 1) 2.3f else 0.5f, -1.2f)
internal fun staffIcicleLaunchDelay(index: Int): Int = 8 + index * 4

/** One shoulder crystal: its eight stable pieces assemble, fly together, then fracture. */
internal fun staffIcicleDisplayParts(ageTicks: Int, impact: Boolean): List<StaffDisplayPart> {
    val charge = (ageTicks / 8.0).coerceIn(0.0, 1.0)
    val release = (ageTicks / 8.0).coerceIn(0.0, 1.0)
    return List(8) { index ->
        val angle = index * PI / 4.0
        val material = when (index) {
            0 -> Material.PACKED_ICE
            1 -> Material.SEA_LANTERN
            2, 4, 6 -> Material.BLUE_ICE
            else -> Material.LIGHT_BLUE_STAINED_GLASS
        }
        if (impact) {
            val spread = 0.2 + release * 1.1
            StaffDisplayPart(material,
                Vector3f((cos(angle) * spread).toFloat(), (sin(angle) * spread).toFloat(),
                    ((index % 3 - 1) * spread * 0.65).toFloat()),
                Vector3f(0.18f, 0.14f, 0.55f).mul((1.0 - release * 0.6).toFloat()),
                Quaternionf().rotateY(angle.toFloat()).rotateX((release * 1.2).toFloat()))
        } else {
            val spread = if (index < 2) 0.0 else 0.16 + (1.0 - charge) * 0.26
            val size = (0.3 + charge * 0.7).toFloat()
            val scale = when (index) {
                0 -> Vector3f(0.32f, 0.32f, 1.35f)
                1 -> Vector3f(0.20f, 0.20f, 0.70f)
                else -> Vector3f(0.18f, 0.22f, 0.76f)
            }.mul(size)
            StaffDisplayPart(material,
                Vector3f((cos(angle) * spread).toFloat(), (sin(angle) * spread).toFloat(),
                    if (index == 1) 0.42f else if (index < 2) -0.18f else -0.48f),
                scale, Quaternionf().rotateZ((angle + PI / 4.0).toFloat())
                    .rotateY((cos(angle) * 0.20).toFloat()).rotateX((sin(angle) * 0.20).toFloat()))
        }
    }
}
