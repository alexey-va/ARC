package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun staffArsenalFront(ageTicks: Int, durationTicks: Int, radius: Double): Double =
    (radius.takeIf(Double::isFinite)?.coerceIn(0.0, 64.0) ?: 0.0) *
        (ageTicks.coerceAtLeast(0).toDouble() / durationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)

internal fun staffParticleOnlyQuick(spell: StaffSpell, input: StaffCastInput) = input.quick

internal fun staffArsenalLifetime(spell: StaffSpell, secondary: Boolean) = when (spell) {
    StaffSpell.GALE -> if (secondary) 44 else 76
    StaffSpell.TIDE -> if (secondary) 44 else 52
    StaffSpell.REQUIEM -> if (secondary) 52 else 42
    StaffSpell.NOVA -> 48
    else -> 40
}

/** Solid geometry is reserved for the moon blade, rooted plants and the prism apparatus. */
internal fun staffArsenalParts(spell: StaffSpell, ageTicks: Int, durationTicks: Int, length: Double,
    radius: Double, impact: Boolean, secondary: Boolean, input: StaffCastInput, charge: Double = 0.0): List<StaffDisplayPart> {
    if (input.quick) return emptyList()
    val age = ageTicks.coerceAtLeast(0)
    val q = charge.coerceIn(0.0, 1.0)
    val scale = .8 + q * .45
    val fade = smooth((durationTicks - 2 - age) / 8.0)
    val parts = mutableListOf<StaffDisplayPart>()
    fun part(id: String, material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) {
        parts += StaffDisplayPart(material, Vector3f((x * scale).toFloat(), (y * scale).toFloat(), (z * scale).toFloat()),
            Vector3f((sx * scale * fade).coerceAtLeast(.001).toFloat(),
                (sy * scale * fade).coerceAtLeast(.001).toFloat(), (sz * scale * fade).coerceAtLeast(.001).toFloat()),
            Quaternionf().rotationXYZ(pitch.toFloat(), yaw.toFloat(), roll.toFloat()), identity = id)
    }
    when (spell) {
        StaffSpell.LUNAR -> {
            val fall = smooth((age - 4) / 12.0)
            val birth = smooth(age / 6.0)
            val copies = if (secondary) 2 else 1
            repeat(copies) { side ->
                val turn = if (secondary) (side * 2 - 1) * (.9 - fall * 1.8) else -.7 + fall * 1.2
                repeat(17) { i ->
                    val a = -2.15 + i * 4.3 / 16
                    val width = (.12 + sin(i * PI / 16) * .52) * birth
                    val x = cos(a) * 1.85
                    val y = sin(a) * 1.85
                    val drop = if (secondary) 2.0 else 6.2 - fall * 4.1
                    part("moon:$side:$i", if (i % 4 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                        x * cos(turn) - y * sin(turn), drop + x * sin(turn) + y * cos(turn),
                        if (secondary) (side * 2 - 1) * (1.6 - fall * 1.6) else 0.0,
                        width, .62 * birth, .28 * birth, roll = a + turn)
                }
            }
        }
        StaffSpell.PRISM -> {
            val rise = smooth(age / 10.0)
            val spin = age * .045
            part("prism:core", Material.AMETHYST_BLOCK, 0.0, -1.1 + rise * 3.2, 0.0,
                .85 * rise, 2.25 * rise, .85 * rise, roll = .12, yaw = spin)
            part("prism:light", Material.SEA_LANTERN, 0.0, -.6 + rise * 3.35, 0.0,
                .38 * rise, 1.25 * rise, .38 * rise, yaw = -spin)
            repeat(3) { i ->
                val a = i * PI * 2 / 3 + spin
                val orbit = if (secondary) 2.2 else 1.6
                part("prism:satellite:$i", Material.AMETHYST_BLOCK, cos(a) * orbit, rise * (2.2 + sin(a) * .25), sin(a) * orbit,
                    .44 * rise, 1.15 * rise, .44 * rise, yaw = -a, roll = .2 * cos(a), pitch = -.2 * sin(a))
            }
        }
        StaffSpell.NOVA -> {
            val grow = smooth(age / if (secondary) 16.0 else 10.0)
            if (secondary) {
                repeat(7) { petal ->
                    val a = petal * PI * 2 / 7
                    repeat(3) { segment ->
                        val f = (segment + 1) / 3.0
                        val spread = grow * f * 2.4
                        part("bloom:$petal:$segment", if (segment == 2) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                            cos(a) * spread, -.45 + grow * (.45 + sin(f * PI) * 1.35), sin(a) * spread,
                            .65 * grow, .30 * grow, 1.0 * grow, yaw = -a + PI / 2, pitch = f * .6 - .4)
                    }
                }
                part("bloom:heart", Material.EMERALD_BLOCK, 0.0, -.2 + grow * .7, 0.0,
                    .62 * grow, .85 * grow, .62 * grow, roll = .3, yaw = age * .025)
            } else repeat(4) { root ->
                val a = root * PI / 2 + .4
                repeat(4) { joint ->
                    val f = (joint + 1) / 4.0
                    val growth = smooth((grow - joint * .12) / .64)
                    val bend = f * 1.5
                    part("root:$root:$joint", if (joint == 3) Material.FLOWERING_AZALEA_LEAVES else Material.DARK_OAK_WOOD,
                        cos(a) * (.3 + f * .85), -.55 + growth * (f * 2.3), sin(a) * (.3 + f * .85),
                        (.37 - f * .14) * growth, .78 * growth, (.37 - f * .14) * growth,
                        pitch = -sin(a) * bend * .35, roll = cos(a) * bend * .35)
                }
            }
        }
        else -> Unit
    }
    return parts
}

/** Pure native-particle frames: substantial silhouettes with a bright core and a softer wake. */
internal fun staffArsenalParticles(spell: StaffSpell, ageTicks: Int, durationTicks: Int,
    radius: Double, secondary: Boolean, charge: Double): List<StaffParticleSample> {
    val age = ageTicks.toDouble()
    val q = charge.coerceIn(0.0, 1.0)
    val out = mutableListOf<StaffParticleSample>()
    val fade = smooth((durationTicks - age) / 8.0)
    if (fade <= 0.0 || age < 0) return out
    fun mote(particle: Particle, x: Double, y: Double, z: Double, data: Any? = null,
        count: Int = 1, spread: Double = 0.0, speed: Double = 0.0) {
        out += StaffParticleSample(particle, Vector(x, y, z), count,
            Vector(spread, spread, spread), speed, data)
    }
    val silver = staffFade(0xecfaff, 0x727cba, 1.3f)
    val water = staffFade(0x70eaff, 0x147eb0, 1.6f)
    val soul = staffFade(0xb8f7e7, 0x593581, 1.4f)
    when (spell) {
        StaffSpell.GALE -> if (secondary) {
            val grow = smooth(age / 5.0) * fade
            repeat(96) { i ->
                val lane = i / 24
                val a = -.95 + (i % 24) / 23.0 * 1.9
                val ring = (1.2 + lane * .7) * grow
                mote(if (i % 3 == 0) Particle.CLOUD else Particle.DUST_COLOR_TRANSITION,
                    sin(a) * ring, .7 + lane * .5 + cos(a * 2) * .3,
                    cos(a) * ring - 2.4, if (i % 3 == 0) null else silver,
                    count = if (i % 12 == 0) 3 else 1, spread = .06, speed = .015)
            }
            if (ageTicks % 4 == 0) mote(Particle.GUST, 0.0, 1.4, 0.0, count = 3, spread = .55)
        } else {
            val grow = smooth(age / 8.0) * fade
            // Six coherent ropes, 4.8 blocks high. Clouds fill the gaps rather than thin block rings.
            repeat(108) { i ->
                val h = i / 107.0
                val angle = h * PI * 8 - age * .38 + (i % 3) * PI * 2 / 3
                val r = (.32 + h * h * (1.5 + q * .9)) * grow
                val x = cos(angle) * r; val z = sin(angle) * r
                val y = .15 + h * (4.2 + q * 1.1) * grow
                mote(if (i % 3 == 0) Particle.CLOUD else Particle.DUST_COLOR_TRANSITION,
                    x, y, z, if (i % 3 == 0) null else silver,
                    count = if (i % 12 == 0) 3 else 1, spread = .045, speed = .012)
            }
            if (ageTicks % 4 == 0) mote(Particle.GUST, 0.0, 1.2, 0.0, count = 2, spread = .35)
        }
        StaffSpell.TIDE -> if (!secondary) {
            val rise = smooth(age / 6.0)
            val height = (3.7 + q * 2.0) * rise * fade
            repeat(90) { i ->
                val f = i / 89.0
                val a = i * 2.39996 + age * .14
                val r = (.32 + f * .65) * (1.0 + q * .35)
                val bend = if (age > 7) (age - 7) * .075 * f else 0.0
                val x = cos(a) * (r + bend); val z = sin(a) * (r + bend)
                mote(if (i % 3 == 0) Particle.SPLASH else Particle.DUST_COLOR_TRANSITION,
                    x, f * height, z, if (i % 3 == 0) null else water,
                    count = if (i % 9 == 0) 4 else 1, spread = .035)
                if (i % 9 == 0) mote(Particle.CLOUD, x, f * height, z, spread = .06)
            }
            if (ageTicks in 4..10) mote(Particle.SPLASH, 0.0, height, 0.0, count = 48, spread = .75, speed = .15)
        } else {
            // Curling sheet: the lip rolls over; trailing droplets are native ballistic particles.
            val grow = smooth(age / 6.0) * fade
            repeat(112) { i ->
                val across = (i % 16) / 15.0 * 2 - 1
                val curl = (i / 16) / 6.0
                val a = curl * PI * 1.3
                val x = across * radius
                val y = (.2 + (1 - cos(a)) * 1.55) * grow
                val z = sin(a) * 1.35 - curl * .7
                val foam = curl > .68
                mote(if (foam) Particle.SPLASH else Particle.DUST_COLOR_TRANSITION,
                    x, y, z, if (foam) null else water, count = if (foam) 2 else 1, spread = .045)
                if (i % 8 == 0) mote(Particle.CLOUD, x, y, z, spread = .08)
            }
        }
        StaffSpell.LUNAR -> {
            val fall = smooth((age - 4) / 12.0)
            val height = if (secondary) 2.0 else 6.2 - fall * 4.1
            repeat(40) { i ->
                val a = -2.3 + i * 4.6 / 39
                mote(if (i % 5 == 0) Particle.END_ROD else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * (2.0 + q * .6), height + sin(a) * (2.0 + q * .6), .12,
                    if (i % 5 == 0) null else silver)
            }
            if (ageTicks in 16..28) repeat(48) { i ->
                val a = i * PI / 24
                val r = (age - 15) * .34
                mote(Particle.END_ROD, cos(a) * r, .18, sin(a) * r)
            }
        }
        StaffSpell.REQUIEM -> {
            val t = smooth((age - 2) / 20.0)
            val count = 3 + (q * 2).toInt()
            repeat(count) { strand -> repeat(18) { i ->
                val f = i / 17.0
                val angle = strand * PI * 2 / count + f * PI * 1.4 + age * .10
                val r = if (secondary) .45 + t * radius + f * .6 else (1 - t) * radius + (1 - f) * .8
                val y = .3 + sin(f * PI) * 1.7 + t * (if (secondary) .8 else 2.2)
                mote(if (i % 4 == 0) Particle.SOUL else Particle.DUST_COLOR_TRANSITION,
                    cos(angle) * r, y, sin(angle) * r, if (i % 4 == 0) null else soul)
            } }
            if (ageTicks in 18..26) mote(Particle.SOUL_FIRE_FLAME, 0.0, 1.8, 0.0, count = 24, spread = .6, speed = .04)
        }
        StaffSpell.PRISM -> {
            repeat(36) { i ->
                val a = i * PI / 18 - age * .09
                val r = (1.2 + q * .6) * smooth(age / 8.0)
                mote(if (i % 3 == 0) Particle.END_ROD else Particle.ENCHANT,
                    cos(a) * r, 1.8 + sin(a * 3) * .7, sin(a) * r)
            }
        }
        StaffSpell.NOVA -> {
            val grow = smooth(age / if (secondary) 16.0 else 10.0)
            repeat(if (secondary) 64 else 40) { i ->
                val a = i * 2.39996 + age * .025
                val r = sqrt((i + .5) / if (secondary) 64.0 else 40.0) * (if (secondary) 2.8 else 1.35) * grow
                mote(if (i % 5 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * r, .15 + grow * (.35 + (i % 5) * .3), sin(a) * r,
                    if (i % 5 == 0) null else staffFade(0x98ee63, 0x315830, 1.05f))
            }
            if (secondary && ageTicks in 18..32) repeat(64) { i ->
                val a = i * PI / 32
                val r = .4 + (age - 17) * .36
                mote(if (i % 4 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * r, .3 + sin(i * 2.3) * .15, sin(a) * r,
                    if (i % 4 == 0) null else staffFade(0xffed9e, 0x61bb42, 1.25f))
            }
        }
        else -> Unit
    }
    return out
}

/** Bright continuous beam with a helical secondary strand; front and collision share progress. */
internal fun staffPrismBeam(delta: Vector, front: Double, charge: Double, age: Int): List<StaffParticleSample> {
    val distance = delta.length()
    if (distance < .001) return emptyList()
    val axis = delta.clone().normalize()
    val right = axis.clone().crossProduct(Vector(0.0, 1.0, 0.0)).apply { if (lengthSquared() < .001) x = 1.0 }.normalize()
    val up = right.clone().crossProduct(axis).normalize()
    val n = (distance * front * 3.5).toInt().coerceIn(2, 32)
    return (0 until n).flatMap { i ->
        val f = i / (n - 1.0)
        val center = axis.clone().multiply(distance * front * f)
        val a = f * PI * 7 - age * .3
        val rim = center.clone().add(right.clone().multiply(cos(a) * (.16 + charge * .1)))
            .add(up.clone().multiply(sin(a) * (.16 + charge * .1)))
        listOf(StaffParticleSample(Particle.END_ROD, center),
            StaffParticleSample(Particle.DUST_COLOR_TRANSITION, rim, data = staffFade(0xffb8ef, 0x734de0, 1.5f)))
    }
}

private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
