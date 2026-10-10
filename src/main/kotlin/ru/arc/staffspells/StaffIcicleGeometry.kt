package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun staffIcicleStep(age: Int): Double = minOf(2.4, 0.55 + age.coerceAtLeast(0) * 0.18)
internal fun staffIcicleAnchorOffset(index: Int, count: Int = 7): Vector3f {
    val angle = if (count <= 1) PI / 2 else PI * (0.12 + 0.76 * index.coerceIn(0, count - 1) / (count - 1))
    return Vector3f((cos(angle) * 2.65).toFloat(), (0.35 + sin(angle) * 2.3).toFloat(), -1.65f)
}
internal fun staffIcicleLaunchDelay(index: Int): Int = 4 + index * 2

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

/** Shoulder halo grows outside the crosshair; stable slots prevent spawn/despawn flicker. */
internal fun staffChargeParts(spell: StaffSpell, progress: Double, age: Int): List<StaffDisplayPart> {
    val count = if (spell == StaffSpell.ICICLE) 11 else 7
    val material = when (spell) {
        StaffSpell.CHAIN -> Material.SEA_LANTERN
        StaffSpell.MARK, StaffSpell.REQUIEM -> Material.CRYING_OBSIDIAN
        StaffSpell.FROST, StaffSpell.ICICLE -> Material.BLUE_ICE
        StaffSpell.LANCE -> Material.GOLD_BLOCK
        StaffSpell.EMBER -> Material.MAGMA_BLOCK
        StaffSpell.NOVA -> Material.EMERALD_BLOCK
        StaffSpell.FAULT -> Material.OBSIDIAN
        StaffSpell.LUNAR -> Material.CALCITE
        StaffSpell.GALE -> Material.WHITE_STAINED_GLASS
        StaffSpell.TIDE -> Material.PRISMARINE
        StaffSpell.PRISM -> Material.AMETHYST_BLOCK
    }
    return (0 until count).flatMap { index ->
        val anchor = staffIcicleAnchorOffset(index, count)
        val growth = (progress * (count - 2) + 2 - index).coerceIn(0.0, 1.0).toFloat()
        List(3) { piece ->
            StaffDisplayPart(if (piece == 1) Material.SEA_LANTERN else material,
                Vector3f(anchor).add(0f, sin(age * 0.07 + index).toFloat() * 0.08f, (piece - 1) * 0.15f),
                Vector3f(if (piece == 1) 0.11f else 0.24f, 0.23f, if (spell == StaffSpell.ICICLE) 0.95f else 0.4f).mul(maxOf(0.001f, growth)),
                Quaternionf().rotateZ((piece * PI / 3 + age * 0.015).toFloat()), growth > 0.01f)
        }
    }
}
