package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pure shared front radius for expanding field damage and its visible wave. */
internal fun staffArsenalFront(ageTicks: Int, durationTicks: Int, radius: Double): Double {
    val safeRadius = radius.takeIf(Double::isFinite)?.coerceIn(0.0, 16.0) ?: 0.0
    return safeRadius * (ageTicks.coerceAtLeast(0).toDouble() / durationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
}

/** Stable, bounded block-display slots for the distinct arsenal silhouettes and their impacts. */
internal fun staffArsenalParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    length: Double,
    radius: Double,
    impact: Boolean,
    secondary: Boolean,
    input: StaffCastInput,
): List<StaffDisplayPart> {
    val duration = durationTicks.coerceAtLeast(1)
    val age = ageTicks.coerceAtLeast(0)
    val progress = (age.toDouble() / duration).coerceIn(0.0, 1.0)
    val reach = length.takeIf(Double::isFinite)?.coerceIn(0.8, 48.0)?.toFloat() ?: 8f
    val spread = radius.takeIf(Double::isFinite)?.coerceIn(0.5, 8.0)?.toFloat() ?: 1f
    val spin = age * 0.14
    val parts = MutableList(32) { invisiblePart() }
    fun put(index: Int, material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) {
        if (index !in parts.indices) return
        parts[index] = arsenalPart(material, x, y, z, sx, sy, sz, yaw, pitch, roll)
    }

    when (spell) {
        StaffSpell.LUNAR -> {
            if (impact) {
                repeat(16) { i ->
                    val angle = i * PI / 8.0 + spin
                    val r = spread * (0.38 + (i % 3) * 0.08)
                    put(i, if (i % 3 == 0) Material.AMETHYST_BLOCK else Material.WHITE_STAINED_GLASS,
                        cos(angle) * r, sin(angle) * r, 0.0, 0.18, 0.24, 0.13, yaw = -angle)
                }
                put(16, Material.SEA_LANTERN, 0.0, 0.0, 0.0, 0.34, 0.34, 0.34)
            } else if (input.quick && input.secondary) {
                repeat(15) { i ->
                    val lane = i / 5 - 1
                    val step = i % 5
                    put(i, if (step % 2 == 0) Material.AMETHYST_BLOCK else Material.WHITE_STAINED_GLASS,
                        lane * 0.28, sin(step * 0.7 + spin) * 0.16, step * 0.25,
                        0.15, 0.2, 0.26, yaw = lane * 0.22, roll = lane * 0.12)
                }
                put(15, Material.SEA_LANTERN, 0.0, 0.0, 0.0, 0.28, 0.28, 0.34)
            } else if (input.quick) {
                repeat(9) { i ->
                    put(i, if (i % 3 == 0) Material.AMETHYST_BLOCK else Material.WHITE_STAINED_GLASS,
                        0.0, 0.0, i * 0.23, 0.18, 0.18, 0.28, yaw = spin)
                }
                put(9, Material.SEA_LANTERN, 0.0, 0.0, 0.0, 0.30, 0.30, 0.36)
            } else {
                val arcRadius = if (input.secondary) 0.78f else 0.62f
                repeat(11) { i ->
                    val angle = -PI * 0.78 + i * PI * 1.56 / 10.0 + spin
                    val x = cos(angle) * arcRadius
                    val y = sin(angle) * arcRadius
                    put(i, if (i % 3 == 0) Material.AMETHYST_BLOCK else Material.WHITE_STAINED_GLASS,
                        x, y, 0.0, 0.22, 0.29, 0.16, yaw = -angle, roll = angle * 0.12)
                }
                put(11, Material.SEA_LANTERN, 0.0, 0.0, 0.04, 0.28, 0.28, 0.24)
                if (input.secondary) repeat(6) { i ->
                    val angle = i * PI / 3.0 - spin
                    put(12 + i, Material.PURPLE_STAINED_GLASS, cos(angle) * 1.15, sin(angle) * 0.5,
                        0.15, 0.12, 0.12, 0.24, yaw = -angle)
                }
            }
        }
        StaffSpell.GALE -> {
            if (impact || input.quick && input.secondary) {
                repeat(16) { i ->
                    val angle = i * PI / 8.0 + spin
                    val r = spread * (0.25 + (i % 4) * 0.07)
                    val y = if (input.quick && input.secondary) (i % 4) * 0.22 else 0.10 + (i % 3) * 0.12
                    put(i, if (i % 3 == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                        cos(angle) * r, y, sin(angle) * r, 0.17, 0.28, 0.17, yaw = -angle)
                }
                put(16, Material.WHITE_STAINED_GLASS, 0.0, 0.35, 0.0, 0.42, 0.42, 0.42)
            } else if (input.quick) {
                repeat(11) { i ->
                    val z = i * 0.22
                    put(i, if (i % 3 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                        sin(spin + i * 0.43) * 0.15, sin(i * 0.5) * 0.08, z,
                        0.12, 0.12, 0.3, yaw = sin(i * 0.5) * 0.35)
                }
            } else {
                repeat(24) { i ->
                    val level = i / 6
                    val angle = (i % 6) * PI / 3.0 + spin * (1.0 + level * 0.16)
                    val y = level * 0.34
                    val r = (if (input.secondary) 0.82 - level * 0.11 else 0.48 + level * 0.11)
                    put(i, when {
                        i % 6 == 0 -> Material.SEA_LANTERN
                        level % 2 == 0 -> Material.LIGHT_BLUE_STAINED_GLASS
                        else -> Material.WHITE_STAINED_GLASS
                    }, cos(angle) * r, y, sin(angle) * r, 0.18, 0.3, 0.18, yaw = -angle)
                }
                put(24, Material.CYAN_STAINED_GLASS, 0.0, 0.78, 0.0, 0.28, 0.42, 0.28)
            }
        }
        StaffSpell.TIDE -> {
            when {
                impact || input.secondary && !input.quick -> {
                    val front = if (impact && input.secondary && !input.quick)
                        staffArsenalFront(age, duration, spread.toDouble()).toFloat() else spread
                    repeat(24) { i ->
                        val band = i / 8
                        val angle = i % 8 * PI / 4.0 + spin * (1.0 + band * 0.2)
                        val r = front * (0.40 + band * 0.29)
                        val y = if (input.secondary) band * 0.32 else 0.12 + band * 0.17
                        put(i, if (i % 4 == 0) Material.PRISMARINE else Material.LIGHT_BLUE_STAINED_GLASS,
                            cos(angle) * r, y, sin(angle) * r, 0.15, 0.24, 0.15, yaw = -angle)
                    }
                    put(24, Material.SEA_LANTERN, 0.0, 0.35, 0.0, 0.34, 0.48, 0.34)
                }
                input.quick && input.secondary -> repeat(18) { i ->
                    val lane = i / 6 - 1
                    val step = i % 6
                    put(i, if (step % 3 == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                        lane * 0.25, step * 0.16, step * 0.24, 0.18, 0.18, 0.26, yaw = lane * 0.16)
                }
                input.quick -> {
                    repeat(10) { i ->
                        put(i, if (i % 3 == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                            0.0, 0.0, i * 0.19, 0.14, 0.14, 0.22)
                    }
                }
                else -> {
                    repeat(24) { i ->
                        val ring = i / 8
                        val angle = i % 8 * PI / 4.0 + spin
                        val r = 0.34 + ring * 0.18
                        put(i, if (i % 4 == 0) Material.SEA_LANTERN else Material.PRISMARINE,
                            cos(angle) * r, sin(angle) * r, 0.0, 0.18, 0.18, 0.18, yaw = -angle)
                    }
                    put(24, Material.LIGHT_BLUE_STAINED_GLASS, 0.0, 0.0, 0.0, 0.48, 0.48, 0.48)
                }
            }
        }
        StaffSpell.REQUIEM -> {
            if (impact || input.secondary && !input.quick) {
                val front = if (impact && input.secondary && !input.quick)
                    staffArsenalFront(age, duration, spread.toDouble()).toFloat() else spread
                repeat(20) { i ->
                    val angle = i * PI / 10.0 + spin
                    val r = front * (0.72 + (i % 4) * 0.09)
                    put(i, if (i % 4 == 0) Material.SOUL_LANTERN else Material.PURPLE_STAINED_GLASS,
                        cos(angle) * r, (i % 3) * 0.18, sin(angle) * r,
                        0.16, 0.32, 0.16, yaw = -angle)
                }
                put(20, Material.CRYING_OBSIDIAN, 0.0, 0.22, 0.0, 0.38, 0.46, 0.38)
            } else if (input.quick && input.secondary) {
                repeat(15) { i ->
                    val lane = i / 5 - 1
                    val step = i % 5
                    put(i, if (step % 2 == 0) Material.SOUL_LANTERN else Material.PURPLE_STAINED_GLASS,
                        lane * 0.30, sin(step * 0.55 + spin) * 0.20, step * 0.23,
                        0.14, 0.18, 0.25, yaw = lane * 0.16)
                }
                put(15, Material.CRYING_OBSIDIAN, 0.0, 0.0, 0.0, 0.25, 0.25, 0.3)
            } else if (input.quick) {
                repeat(12) { i ->
                    val z = i * 0.19
                    put(i, if (i % 4 == 0) Material.SOUL_LANTERN else Material.PURPLE_STAINED_GLASS,
                        sin(i * 0.42) * 0.09, cos(i * 0.36) * 0.08, z,
                        0.12, 0.12, 0.27, yaw = sin(i * 0.4) * 0.22)
                }
            } else {
                repeat(5) { wisp ->
                    val angle = wisp * PI * 2.0 / 5.0 + spin
                    val x = cos(angle) * (0.45 + progress * 0.35)
                    val y = sin(angle * 1.3) * 0.22
                    val z = sin(angle) * (0.45 + progress * 0.35)
                    put(wisp * 3, Material.SOUL_LANTERN, x, y, z, 0.22, 0.28, 0.22)
                    put(wisp * 3 + 1, Material.PURPLE_STAINED_GLASS, x * 0.72, y - 0.19, z * 0.72,
                        0.12, 0.22, 0.12, yaw = angle)
                    put(wisp * 3 + 2, Material.CYAN_STAINED_GLASS, x * 1.18, y + 0.14, z * 1.18,
                        0.1, 0.1, 0.1)
                }
            }
        }
        StaffSpell.PRISM -> {
            if (impact || input.secondary && !input.quick) {
                repeat(18) { i ->
                    val lane = i / 6 - 1
                    val step = i % 6
                    put(i, if (step % 2 == 0) Material.AMETHYST_BLOCK else Material.PINK_STAINED_GLASS,
                        lane * 0.22, sin(step * 0.7 + spin) * 0.12, step * 0.2,
                        0.16, 0.16, 0.25, yaw = lane * 0.18)
                }
                put(18, Material.SEA_LANTERN, 0.0, 0.0, 0.0, 0.38, 0.38, 0.38)
            } else if (input.quick) {
                val count = if (input.secondary) 5 else 9
                repeat(count) { i ->
                    val lane = if (input.secondary) i / 1 - 2 else 0
                    put(i, if (i % 3 == 0) Material.SEA_LANTERN else Material.AMETHYST_BLOCK,
                        lane * 0.18, 0.0, i * 0.18, 0.14, 0.14, 0.24, yaw = lane * 0.1)
                }
            } else {
                repeat(21) { i ->
                    val lane = i / 7 - 1
                    val step = i % 7
                    val x = lane * (0.25 + step * 0.04)
                    put(i, if (step % 3 == 0) Material.SEA_LANTERN else Material.PURPLE_STAINED_GLASS,
                        x, 0.0, step * 0.19, 0.12, 0.12, 0.28, yaw = lane * 0.1)
                }
                repeat(3) { i -> put(21 + i, Material.AMETHYST_BLOCK, (i - 1) * 0.35, 0.0, 0.9,
                    0.18, 0.18, 0.18, yaw = spin) }
            }
        }
        StaffSpell.NOVA -> {
            if (impact || input.secondary && !input.quick) {
                val front = if (impact && input.secondary && !input.quick)
                    staffArsenalFront(age, duration, spread.toDouble()).toFloat() else spread
                repeat(8) { i ->
                    val angle = i * PI / 4.0 + spin * 0.15
                    val r = if (input.secondary) front * if (i % 2 == 0) 0.95 else 0.72 else 0.72
                    put(i, if (i % 2 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                        cos(angle) * r, 0.06, sin(angle) * r, 0.25, 0.18, 0.38, yaw = -angle, roll = 0.25)
                    put(8 + i, Material.MOSS_BLOCK, cos(angle) * r * 0.66, 0.23, sin(angle) * r * 0.66,
                        0.14, 0.28, 0.16, yaw = -angle)
                }
                put(16, Material.SEA_LANTERN, 0.0, 0.25, 0.0, 0.34, 0.34, 0.34)
                put(17, Material.EMERALD_BLOCK, 0.0, 0.56, 0.0, 0.18, 0.18, 0.18)
            } else if (input.quick && input.secondary) {
                repeat(12) { i ->
                    val angle = i * PI / 6.0 + spin
                    val r = 0.7 + 0.12 * sin(age * 0.12 + i)
                    put(i, if (i % 3 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                        cos(angle) * r, 0.22 + sin(angle * 2.0) * 0.12, sin(angle) * r,
                        0.18, 0.12, 0.22, yaw = -angle)
                }
                put(12, Material.EMERALD_BLOCK, 0.0, 0.25, 0.0, 0.18, 0.18, 0.18)
            } else {
                repeat(20) { i ->
                    val t = i / 19.0
                    val z = t * minOf(reach.toDouble(), 7.0)
                    val angle = spin + t * PI * (if (input.quick) 1.1 else 2.1)
                    val x = cos(angle) * (0.12 + t * 0.28)
                    val y = 0.08 + sin(angle) * (0.12 + t * 0.18)
                    put(i, if (i % 4 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                        x, y, z, 0.12, 0.12, 0.26, yaw = -angle, roll = 0.12 * sin(angle))
                }
                put(20, Material.EMERALD_BLOCK, 0.0, 0.08, 0.0, 0.22, 0.22, 0.28)
                if (!input.quick) repeat(6) { i ->
                    val angle = i * PI / 3.0 + spin
                    put(21 + i, Material.FLOWERING_AZALEA_LEAVES,
                        cos(angle) * 0.38, 0.15 + sin(angle) * 0.18, 0.25,
                        0.16, 0.1, 0.24, yaw = -angle)
                }
            }
        }
        else -> Unit
    }
    // Only impact scenes dissolve; leave the silhouette steady until the final five ticks.
    val remaining = ((duration - age).toDouble() / 5.0).coerceIn(0.0, 1.0)
    val dissolve = if (impact) maxOf(0.001, remaining * remaining * (3.0 - 2.0 * remaining)) else 1.0
    // Keep fixed slots and dimensions so per-viewer handles never roll between phases.
    return if (dissolve == 1.0) parts else parts.map { part ->
        part.copy(scale = Vector3f(part.scale).mul(dissolve.toFloat()))
    }
}

private fun invisiblePart() = StaffDisplayPart(Material.GLASS, Vector3f(), Vector3f(0.001f), Quaternionf(), false)

private fun arsenalPart(material: Material, x: Double, y: Double, z: Double,
    sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) =
    StaffDisplayPart(material, Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
        Vector3f(sx.toFloat(), sy.toFloat(), sz.toFloat()),
        Quaternionf().rotationXYZ(pitch.toFloat(), yaw.toFloat(), roll.toFloat()))
