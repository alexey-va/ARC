package ru.arc.staffspells

import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Damage and expanding field geometry share the same front. */
internal fun staffArsenalFront(ageTicks: Int, durationTicks: Int, radius: Double): Double {
    val r = radius.takeIf(Double::isFinite)?.coerceIn(0.0, 16.0) ?: 0.0
    return r * (ageTicks.coerceAtLeast(0).toDouble() / durationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)
}

internal fun staffParticleOnlyQuick(spell: StaffSpell, input: StaffCastInput) =
    input == StaffCastInput.QUICK && spell in setOf(StaffSpell.GALE, StaffSpell.TIDE)

/** One body per projectile. Pieces retain their material and identity for the whole animation. */
internal fun staffArsenalParts(
    spell: StaffSpell, ageTicks: Int, durationTicks: Int, length: Double, radius: Double,
    impact: Boolean, secondary: Boolean, input: StaffCastInput, charge: Double = 0.0,
): List<StaffDisplayPart> {
    if (staffParticleOnlyQuick(spell, input)) return emptyList()
    val age = ageTicks.coerceAtLeast(0)
    val duration = durationTicks.coerceAtLeast(1)
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val power = 0.78 + q * 0.50
    val r = radius.takeIf(Double::isFinite)?.coerceIn(0.5, 8.0) ?: 1.0
    val t = (age.toDouble() / duration).coerceIn(0.0, 1.0)
    val spin = age * 0.085
    val field = input.quick || secondary && spell in setOf(StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.NOVA)
    val reveal = if (field) smooth(age / 4.0) else 1.0
    val fade = if (impact || input.quick) smooth((duration - StaffSpellDisplayEffects.FRAME_TICKS - age) / 5.0) else 1.0
    val burst = if (impact && !field) smooth(age / 6.0) else 0.0
    val parts = mutableListOf<StaffDisplayPart>()
    fun part(material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) {
        val scale = maxOf(0.001, reveal * fade) * if (field) .85 + q * .35 else power
        val spread = if (field) 1.0 else power * (1.0 + burst * 1.8)
        parts += StaffDisplayPart(material,
            Vector3f((x * spread).toFloat(), (y * spread).toFloat(), (z * spread).toFloat()),
            Vector3f((sx * scale).toFloat(), (sy * scale).toFloat(), (sz * scale).toFloat()),
            Quaternionf().rotationXYZ(pitch.toFloat(), yaw.toFloat(), roll.toFloat()))
    }
    fun arc(material: Material, count: Int, span: Double, ring: Double, y: Double, thickness: Double,
        start: Double = -span / 2, tilt: Double = 0.0) {
        repeat(count) { i ->
            val angle = start + span * i / (count - 1).coerceAtLeast(1)
            part(material, sin(angle) * ring, y + cos(angle) * tilt, cos(angle) * ring,
                thickness, thickness, maxOf(thickness, ring * span / count * 1.30), yaw = angle + PI / 2)
        }
    }
    if (input.quick) {
        // Short contact/control gestures, rather than reduced copies of the travelling attack.
        val sweep = -0.95 + smooth(age / 8.0) * 1.9
        when (spell) {
            StaffSpell.LUNAR -> repeat(if (secondary) 2 else 1) { lane ->
                repeat(11) { i ->
                    val angle = -1.0 + i * 0.2 + sweep
                    val x = sin(angle) * r * 0.85
                    val z = cos(angle) * r * 0.70
                    part(if (i % 3 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                        x, if (secondary) (lane - .5) * x * .55 else .1, z,
                        .24, .18, .48, yaw = angle + PI / 2, roll = if (lane == 0) .25 else -.25)
                }
            }
            StaffSpell.GALE -> if (secondary) {
                repeat(18) { i ->
                    val h = i / 18.0
                    val angle = i * 2.39996 - age * .18
                    part(if (i % 4 == 0) Material.CALCITE else Material.WHITE_STAINED_GLASS,
                        cos(angle) * r * (.75 - h * .4), -.6 + h * 2.5 * reveal,
                        sin(angle) * r * (.75 - h * .4), .25, .30, .55, yaw = -angle)
                }
            } else {
                repeat(3) { band -> arc(Material.WHITE_STAINED_GLASS, 7, PI * .92,
                    r * (.4 + band * .24) * (.55 + .45 * smooth(age / 7.0)),
                    -.4 + band * .30, .23, tilt = .10) }
            }
            StaffSpell.TIDE -> if (secondary) {
                repeat(7) { i ->
                    val angle = i * PI * 2 / 7
                    val h = (.7 + (i % 3) * .45) * reveal
                    part(if (i % 2 == 0) Material.PRISMARINE else Material.LIGHT_BLUE_STAINED_GLASS,
                        cos(angle) * .65, -.65 + h / 2, sin(angle) * .65,
                        .38, h, .38, roll = cos(angle) * -.22, pitch = sin(angle) * .22)
                }
                arc(Material.WHITE_STAINED_GLASS, 12, PI * 2, r * smooth(age / 8.0), -.6, .24)
            } else {
                repeat(12) { i ->
                    val x = (i - 5.5) * r / 6
                    val curl = sin(i * PI / 11)
                    part(if (i % 4 == 0) Material.PRISMARINE else Material.LIGHT_BLUE_STAINED_GLASS,
                        x, -.7 + curl * .45, (smooth(age / 8.0) - .5) * 1.5 + curl * .4,
                        r / 5, .62 + curl * .50, .46, pitch = -.35 - t * .65)
                    part(Material.WHITE_STAINED_GLASS, x, -.1 + curl * .70,
                        (smooth(age / 8.0) - .5) * 1.5 + curl * .4, r / 5, .16, .35)
                }
            }
            StaffSpell.REQUIEM -> repeat(if (secondary) 5 else 4) { finger ->
                val angle = if (secondary) finger * PI * 2 / 5 else -.9 + finger * .6
                repeat(3) { joint ->
                    val curl = smooth(age / 8.0)
                    val radial = r * (.85 - joint * (.15 + curl * .1))
                    part(if (joint == 2) Material.SOUL_LANTERN else Material.PURPLE_STAINED_GLASS,
                        sin(angle) * radial, -.7 + joint * .60, cos(angle) * radial,
                        .29, .66, .29, pitch = -cos(angle) * curl * .8, roll = sin(angle) * curl * .8)
                }
            }
            StaffSpell.PRISM -> repeat(if (secondary) 9 else 5) { i ->
                val angle = if (secondary) i * PI * 2 / 9 else -.8 + i * .4
                val radial = if (secondary) r * .65 else r * .35
                part(if (i % 3 == 0) Material.SEA_LANTERN else Material.AMETHYST_BLOCK,
                    sin(angle) * radial, -.5 + reveal * .60, cos(angle) * radial,
                    .32, if (secondary) 1.5 else .40, if (secondary) .32 else 1.6,
                    yaw = angle, pitch = if (secondary) cos(angle) * -.38 else -.20,
                    roll = if (secondary) sin(angle) * .38 else sweep * .5)
            }
            StaffSpell.NOVA -> if (secondary) {
                repeat(6) { vine -> repeat(3) { joint ->
                    val angle = vine * PI / 3 + joint * .10
                    val radial = r * (.9 - joint * .25 * reveal)
                    part(if (joint == 2) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                        cos(angle) * radial, -.7 + joint * .52, sin(angle) * radial,
                        .30, .65, .30, pitch = -sin(angle) * joint * .2, roll = cos(angle) * joint * .2)
                } }
            } else repeat(16) { i ->
                val f = i / 15.0
                val angle = sweep + f * 2.5 - 1.25
                part(if (i % 4 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                    sin(angle) * r * f, -.3 + sin(f * PI) * .3, cos(angle) * r * f,
                    .32 - f * .15, .28 - f * .12, .42, yaw = angle + .7)
            }
            else -> Unit
        }
        return parts
    }

    when (spell) {
        StaffSpell.LUNAR -> {
            repeat(13) { i ->
                val a = -PI * .78 + i * PI * 1.56 / 12 + spin
                part(if (i % 4 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                    cos(a) * .78, sin(a) * .78, 0.0, .24, .34, .18, roll = a - PI / 2)
            }
            part(Material.AMETHYST_BLOCK, -.12, 0.0, 0.0, .4, .4, .2, roll = spin)
        }
        StaffSpell.GALE -> {
            // The native particle ropes own the funnel silhouette. These are only tumbling debris.
            repeat(8) { i ->
                val f = .12 + i / 9.0
                val a = i * 2.399963 + spin * (2.2 - f)
                val orbit = .22 + f * f * (if (secondary) 1.65 else 1.05)
                part(when (i % 3) { 0 -> Material.CALCITE; 1 -> Material.COBBLESTONE; else -> Material.OAK_LEAVES },
                    cos(a) * orbit, -.8 + f * 2.8, sin(a) * orbit,
                    .25 + f * .12, .24 + f * .09, .27 + f * .13,
                    yaw = a, pitch = spin * 1.5 + i, roll = spin + i * .4)
            }
        }
        StaffSpell.TIDE -> if (field) {
            val front = if (impact) staffArsenalFront(age, (duration - 8).coerceAtLeast(1), r) else r * .15
            repeat(16) { i ->
                val a = i * PI / 8
                part(if (i % 4 == 0) Material.PRISMARINE else Material.LIGHT_BLUE_STAINED_GLASS,
                    cos(a) * front, -.45 + .2 * sin(a * 3 + spin), sin(a) * front,
                    .42, .8, .42, yaw = -a, roll = .3)
                part(Material.WHITE_STAINED_GLASS, cos(a) * front, .05, sin(a) * front,
                    .38, .16, .38)
            }
        } else {
            repeat(3) { i -> part(if (i == 0) Material.SEA_LANTERN else Material.LIGHT_BLUE_STAINED_GLASS,
                0.0, 0.0, 0.0, .64, .64, .64, pitch = i * .6 + spin * .4, roll = i * .7) }
            repeat(9) { i ->
                val f = i / 8.0; val a = i * 1.9 + spin
                part(Material.PRISMARINE, cos(a) * .20, sin(a) * .20, -.2 - f * 1.45,
                    .25 * (1 - f * .55), .25 * (1 - f * .55), .40, roll = a)
            }
        }
        StaffSpell.REQUIEM -> if (field) {
            val front = if (impact) staffArsenalFront(age, (duration - 8).coerceAtLeast(1), r) else r * .15
            repeat(12) { i ->
                val a = i * PI / 6
                part(if (i % 3 == 0) Material.SOUL_LANTERN else Material.PURPLE_STAINED_GLASS,
                    cos(a) * front, -.4 + .4 * sin(a * 2 + spin), sin(a) * front,
                    .36, .8, .36, roll = .35 * sin(a))
            }
        } else {
            part(Material.SOUL_LANTERN, 0.0, 0.0, 0.0, .48, .52, .48, roll = sin(spin) * .2)
            part(Material.CYAN_STAINED_GLASS, 0.0, 0.0, -.05, .64, .38, .38, roll = .7)
            repeat(7) { i ->
                val f = i / 6.0
                part(Material.PURPLE_STAINED_GLASS, sin(i * .45 + spin) * f * .16,
                    cos(i * .45 + spin) * f * .13, -.35 - f * 1.6,
                    .26 - f * .15, .26 - f * .15, .38, roll = spin + i * .2)
            }
        }
        StaffSpell.PRISM -> {
            part(Material.AMETHYST_BLOCK, 0.0, 0.0, 0.0, .34, .34, .9, roll = PI / 4 + spin * .4)
            part(Material.SEA_LANTERN, 0.0, 0.0, .1, .22, .22, .95, roll = PI / 4)
            repeat(8) { i ->
                val f = i / 7.0
                part(Material.PINK_STAINED_GLASS, 0.0, 0.0, -.45 - f * 1.7,
                    .22 - f * .13, .22 - f * .13, .38, roll = PI / 4)
            }
        }
        StaffSpell.NOVA -> if (field) {
            val front = if (impact) staffArsenalFront(age, (duration - 8).coerceAtLeast(1), r) else r * .15
            repeat(8) { i ->
                val a = i * PI / 4
                part(Material.FLOWERING_AZALEA_LEAVES, cos(a) * front, -.25, sin(a) * front,
                    .5, .28, .75, yaw = -a, roll = .35)
                part(Material.MOSS_BLOCK, cos(a) * front * .80, -.4, sin(a) * front * .80,
                    .28, .65, .28, roll = cos(a) * .4, pitch = sin(a) * .4)
            }
            part(Material.EMERALD_BLOCK, 0.0, -.2, 0.0, .50, .50, .50, roll = .7)
        } else {
            part(Material.EMERALD_BLOCK, 0.0, 0.0, 0.0, .54, .54, .62, roll = .65)
            repeat(14) { i ->
                val f = i / 13.0; val a = f * PI * 2 - spin
                part(if (i % 4 == 0) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                    sin(a) * f * .34, cos(a) * f * .24, -.2 - f * 2.8,
                    .34 - f * .15, .32 - f * .14, .44, yaw = sin(a) * .25, roll = a * .15)
            }
        }
        else -> Unit
    }
    return parts
}

private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
