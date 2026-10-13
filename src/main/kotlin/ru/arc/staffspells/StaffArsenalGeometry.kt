package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val OSSUARY_PRIMARY_RISE_TICKS = 5
internal const val OSSUARY_SECONDARY_ERUPT_TICK = 6
internal const val OSSUARY_SECONDARY_RISE_TICKS = 4
internal const val OSSUARY_SECONDARY_STAGGER_TICKS = 1
internal const val OSSUARY_SECONDARY_STRIKE_TICK = 12

internal fun staffArsenalFront(ageTicks: Int, durationTicks: Int, radius: Double): Double =
    (radius.takeIf(Double::isFinite)?.coerceIn(0.0, 64.0) ?: 0.0) *
        (ageTicks.coerceAtLeast(0).toDouble() / durationTicks.coerceAtLeast(1)).coerceIn(0.0, 1.0)

internal fun staffParticleOnlyQuick(spell: StaffSpell, input: StaffCastInput) = input.quick

internal fun staffArsenalLifetime(spell: StaffSpell, secondary: Boolean) = when (spell) {
    StaffSpell.GALE -> if (secondary) 40 else 52
    StaffSpell.TIDE -> 44
    StaffSpell.REQUIEM -> if (secondary) 40 else 46
    StaffSpell.NOVA -> if (secondary) 40 else 48
    StaffSpell.OSSUARY -> if (secondary) 36 else 54
    else -> 40
}

/** Source-authored Display silhouettes for the arsenal spells. */
internal fun staffArsenalParts(spell: StaffSpell, ageTicks: Int, durationTicks: Int, length: Double,
    radius: Double, impact: Boolean, secondary: Boolean, input: StaffCastInput, charge: Double = 0.0): List<StaffDisplayPart> {
    if (input.quick) return emptyList()
    val age = ageTicks.coerceAtLeast(0)
    val q = charge.coerceIn(0.0, 1.0)
    if (spell == StaffSpell.GALE || spell == StaffSpell.TIDE)
        return staffTideGaleParts(spell, age, durationTicks, radius, impact, secondary, q)
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
        StaffSpell.REQUIEM -> if (secondary) {
            val grow = smooth(age / 9.0)
            val radius = (1.5 + q * .35) * grow
            repeat(12) { i ->
                val a = i * PI * 2 / 12
                part("lament:arc:$i", if (i % 3 == 0) Material.CRYING_OBSIDIAN else Material.PURPLE_STAINED_GLASS,
                    cos(a) * radius, .85 + sin(a * 2) * .3, sin(a) * radius,
                    .22 * grow, .48 * grow, .22 * grow, yaw = a, roll = .18 * sin(a))
            }
            repeat(3) { i ->
                val a = i * PI * 2 / 3 + age * .025
                part("lament:shard:$i", Material.AMETHYST_BLOCK, cos(a) * radius, 1.05, sin(a) * radius,
                    .25 * grow, .42 * grow, .25 * grow, yaw = -a, pitch = .3)
            }
            part("lament:heart", Material.SOUL_SAND, 0.0, .9, 0.0,
                .34 * grow, .42 * grow, .34 * grow)
        } else if (impact) {
            val burst = .16 + age * .09
            repeat(5) { shard ->
                val a = shard * PI * 2 / 5
                part("wisp:burst:$shard", if (shard % 2 == 0) Material.PURPLE_STAINED_GLASS else Material.AMETHYST_BLOCK,
                    cos(a) * burst, sin(a) * burst, sin(a * 2) * burst,
                    .20, .24, .20, yaw = a, roll = sin(a) * .4)
            }
            part("wisp:burst:core", Material.SOUL_SAND, 0.0, 0.0, 0.0,
                .24, .25, .24)
        } else {
            val birth = smooth(age / 4.0)
            val pulse = 1.0 + sin(age * .35) * .12
            part("wisp:core", Material.CRYING_OBSIDIAN, 0.0, 0.0, 0.0,
                .42 * birth * pulse, .48 * birth * pulse, .42 * birth * pulse, yaw = age * .06)
            part("wisp:spine", Material.SOUL_SAND, 0.0, 0.0, -.32,
                .24 * birth, .32 * birth, .50 * birth, yaw = age * .06)
            repeat(2) { side ->
                val sign = if (side == 0) -1.0 else 1.0
                part("wisp:eye:$side", Material.AMETHYST_BLOCK, sign * .18, .07, .25,
                    .12 * birth, .12 * birth, .10 * birth, yaw = age * .06)
                part("wisp:fin:$side", Material.PURPLE_STAINED_GLASS, sign * .45, .0, -.08,
                    .46 * birth, .12 * birth, .26 * birth, yaw = sign * .4, roll = sign * .25)
            }
        }
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
            part("prism:core", Material.AMETHYST_BLOCK, 0.0, -.7 + rise * 2.8, 0.0,
                .66 * rise, 1.95 * rise, .66 * rise, roll = .12, yaw = spin)
            part("prism:light", Material.SEA_LANTERN, 0.0, -.15 + rise * 3.1, 0.0,
                .32 * rise, .95 * rise, .32 * rise, yaw = -spin)
            repeat(3) { i ->
                val a = i * PI * 2 / 3 + spin
                val orbit = if (secondary) 2.2 else 1.6
                part("prism:satellite:$i", Material.AMETHYST_BLOCK, cos(a) * orbit, rise * (2.2 + sin(a) * .25), sin(a) * orbit,
                    .52 * rise, .86 * rise, .52 * rise, yaw = -a, roll = .2 * cos(a), pitch = -.2 * sin(a))
                part("prism:facet:$i", if (i % 2 == 0) Material.PURPLE_STAINED_GLASS else Material.WHITE_STAINED_GLASS,
                    cos(a) * orbit * .62, rise * 1.55, sin(a) * orbit * .62,
                    .18 * rise, 1.0 * rise, .18 * rise, yaw = -a, roll = .22 * sin(a))
            }
        }
        StaffSpell.NOVA -> {
            val grow = smooth(age / if (secondary) 16.0 else 10.0)
            if (secondary) {
                // Five leafy brambles rise from a warning ring and weave inward around a green heart.
                repeat(5) { vine ->
                    val a = vine * PI * 2 / 5
                    repeat(3) { segment ->
                        val f = (segment + 1) / 3.0
                        val close = smooth((age - 2.0) / 15.0)
                        val r = (1.65 - close * .55) * f
                        part("garden:vine:$vine:$segment", if (segment == 2) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                            cos(a) * r, .15 + f * (1.55 + sin(f * PI) * .45), sin(a) * r,
                            .28 * grow, .54 * grow, .28 * grow, yaw = -a, roll = cos(a) * .28)
                    }
                }
                repeat(5) { leaf ->
                    val a = leaf * PI * 2 / 5 + age * .02
                    part("garden:petal:$leaf", Material.FLOWERING_AZALEA_LEAVES,
                        cos(a) * .48 * grow, 1.2 * grow, sin(a) * .48 * grow,
                        .22 * grow, .48 * grow, .22 * grow, yaw = -a, roll = .35 * sin(a))
                }
                part("garden:heart", Material.EMERALD_BLOCK, 0.0, .75 * grow, 0.0,
                    .38 * grow, .5 * grow, .38 * grow, roll = .3, yaw = age * .025)
            } else {
                val reach = length.coerceIn(.75, 48.0)
                val segments = (reach / 1.5).toInt().coerceIn(8, 16)
                val routeTick = age.coerceAtMost(30)
                repeat(2) { root -> repeat(segments) { joint ->
                    val f = (joint + .5) / segments
                    val birth = smooth((routeTick - f * 24.0 + 2.0) / 3.0)
                    val phase = root * PI
                    val bend = sin(f * PI * 4 + phase) * .22
                    val x = (if (root == 0) -.22 else .22) + bend
                    val yaw = kotlin.math.atan2((sin((f + .01) * PI * 4 + phase) - sin((f - .01) * PI * 4 + phase)) * .22,
                        reach / segments)
                    part("root:trail:$root:$joint", if (joint % 3 == 2) Material.MOSS_BLOCK else Material.DARK_OAK_WOOD,
                        x / scale, .16, (reach * f) / scale,
                        .22 * birth, .24 * birth, (reach / segments * 1.25) * birth,
                        yaw = yaw, roll = .1 * kotlin.math.cos(f * PI * 4 + phase))
                } }
                repeat(2) { root -> repeat(2) { thorn ->
                    val f = if (thorn == 0) .32 else .72
                    val x = (if (root == 0) -.22 else .22) + sin(f * PI * 4 + root * PI) * .22
                    val birth = smooth((routeTick - f * 24.0 + 2.0) / 3.0)
                    part("root:thorn:$root:$thorn", Material.FLOWERING_AZALEA_LEAVES,
                        x / scale, .20, (reach * f) / scale,
                        .18 * birth, .32 * birth, .42 * birth, yaw = -kotlin.math.atan2(x, reach))
                } }
                // At tick 22 the ground runners reach the target; six thorn fingers close over four more ticks.
                val grasp = smooth((age - 22.0) / 8.0)
                repeat(6) { finger -> repeat(2) { joint ->
                    val a = finger * PI * 2 / 6
                    val h = (joint + 1) / 2.0
                    val r = .88 - h * .3 * grasp
                    part("root:grasp:$finger:$joint", if (joint == 1) Material.FLOWERING_AZALEA_LEAVES else Material.MOSS_BLOCK,
                        cos(a) * r, .16 + h * 1.15, (reach + sin(a) * r) / scale,
                        .2 * grasp, .48 * grasp, .22 * grasp, yaw = -a, roll = cos(a) * .28)
                } }
            }
        }
        StaffSpell.OSSUARY -> if (secondary) {
            // A brief ground warning resolves into seven fast, tall bone spikes.
            repeat(7) { spike ->
                val angle = spike * PI * 2 / 6
                val radius = if (spike == 0) 0.0 else .78
                val x = cos(angle) * radius
                val z = sin(angle) * radius
                val stagger = (spike % 3) * OSSUARY_SECONDARY_STAGGER_TICKS
                val rise = smooth((age - OSSUARY_SECONDARY_ERUPT_TICK - stagger).toDouble() /
                    OSSUARY_SECONDARY_RISE_TICKS)
                val height = (2.35 + q * .35) * rise
                part("bone:erupt:$spike:shaft", Material.BONE_BLOCK, x, -.36 + height * .43, z,
                    .27 * rise, height * .86, .27 * rise, yaw = -angle)
                part("bone:erupt:$spike:tip", Material.CALCITE, x, -.36 + height - .16, z,
                    .12 * rise, .48 * rise, .12 * rise, yaw = -angle)
            }
        } else {
            val rise = smooth(age / OSSUARY_PRIMARY_RISE_TICKS.toDouble())
            val height = (2.65 + q * .4) * rise
            part("bone:spike:shaft", Material.BONE_BLOCK, 0.0, -.32 + height * .42, 0.0,
                .34 * rise, height * .84, .34 * rise)
            part("bone:spike:tip", Material.CALCITE, 0.0, -.32 + height - .18, 0.0,
                .14 * rise, .56 * rise, .14 * rise)
        }
        else -> Unit
    }
    return parts
}

/** Pure native-particle frames: substantial silhouettes with a bright core and a softer wake. */
internal fun staffArsenalParticles(spell: StaffSpell, ageTicks: Int, durationTicks: Int,
    radius: Double, secondary: Boolean, charge: Double, reach: Double = radius,
    impact: Boolean = false): List<StaffParticleSample> {
    val age = ageTicks.toDouble()
    val q = charge.coerceIn(0.0, 1.0)
    if (spell == StaffSpell.GALE || spell == StaffSpell.TIDE)
        return staffTideGaleParticleSamples(spell, ageTicks, durationTicks, radius, secondary, q, impact)
    val out = mutableListOf<StaffParticleSample>()
    val fade = smooth((durationTicks - age) / 8.0)
    if (fade <= 0.0 || age < 0) return out
    fun mote(particle: Particle, x: Double, y: Double, z: Double, data: Any? = null,
        count: Int = 1, spread: Double = 0.0, speed: Double = 0.0) {
        out += StaffParticleSample(particle, Vector(x, y, z), count,
            Vector(spread, spread, spread), speed, data)
    }
    val silver = staffFade(0xecfaff, 0x727cba, 1.3f)
    val water = staffFade(0x70eaff, 0x147eb0, 1.5f)
    val wind = staffFade(0xe8f8ff, 0x8ab8d0, 1.2f)
    val soul = staffFade(0xd0fff4, 0x6845a3, 1.5f)
    val root = staffFade(0xb6ee75, 0x315830, 1.25f)
    val bone = staffFade(0xfff5d9, 0x9b7d59, 1.3f)
    when (spell) {
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
        StaffSpell.REQUIEM -> if (secondary) {
            val progress = smooth((age - 8.0) / 20.0)
            val r = (.25 + radius * progress) * fade
            repeat(48) { i ->
                val a = i * PI * 2 / 48 + age * .025
                val y = .9 + sin(a * 3) * .18
                mote(if (i % 8 == 0) Particle.SOUL else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * r, y, sin(a) * r,
                    if (i % 8 == 0) null else soul, spread = .012)
            }
            repeat(8) { i ->
                val a = i * PI / 4 + age * .04
                mote(Particle.SOUL_FIRE_FLAME, cos(a) * r, .9, sin(a) * r, count = 1, spread = .02)
            }
        } else {
            // Compact travelling wisp: a bright face, dark core, and clearly trailing soul stream.
            repeat(20) { i ->
                val a = i * PI * 2 / 20 + age * .12
                val r = .34 + q * .08
                mote(if (i % 4 == 0) Particle.SOUL else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * r, sin(a) * r, .05,
                    if (i % 4 == 0) null else soul, spread = .012)
            }
            repeat(16) { i ->
                val f = i / 15.0
                val a = i * 2.39996 + age * .08
                mote(if (i % 4 == 0) Particle.SOUL_FIRE_FLAME else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * (.15 + f * .22), sin(a) * (.15 + f * .22), -.2 - f * .9,
                    if (i % 4 == 0) null else soul, spread = .015)
            }
        }
        StaffSpell.PRISM -> {
            repeat(36) { i ->
                val a = i * PI / 18 - age * .09
                val r = (1.2 + q * .6) * smooth(age / 8.0)
                mote(if (i % 3 == 0) Particle.END_ROD else Particle.ENCHANT,
                    cos(a) * r, 1.8 + sin(a * 3) * .7, sin(a) * r)
            }
        }
        StaffSpell.NOVA -> if (secondary) {
            val close = smooth((age - 2.0) / 18.0)
            val r = (1.75 - close * .45) * fade
            repeat(45) { i ->
                val vine = i / 9
                val step = i % 9
                val a = vine * PI * 2 / 5 + age * .015
                val h = step / 8.0
                val radiusAtHeight = r * (1.0 - .3 * sin(h * PI))
                mote(if (step % 4 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * radiusAtHeight, .15 + h * 2.0, sin(a) * radiusAtHeight,
                    if (step % 4 == 0) null else root, spread = .018)
            }
            repeat(24) { i ->
                val a = i * PI * 2 / 24
                mote(Particle.DUST_COLOR_TRANSITION, cos(a) * r, .15, sin(a) * r,
                    staffFade(0xffe69a, 0x61bb42, 1.2f), spread = .015)
            }
        } else {
            val path = reach.coerceIn(.75, 48.0)
            val front = smooth(age / 30.0)
            repeat(48) { i ->
                val strand = i / 24
                val step = (i % 24) / 23.0
                val f = step * front
                val x = (if (strand == 0) -.22 else .22) + sin(f * PI * 4 + strand * PI) * .22
                val z = path * f
                val y = .05 + .18 * abs(sin(f * PI * 4 + strand * PI))
                mote(if (i % 8 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    x, y, z, if (i % 8 == 0) null else root, spread = .01)
            }
            val endpoint = path * front
            if (ageTicks >= 22) repeat(18) { i ->
                val a = i * PI * 2 / 18
                val r = .55 - smooth((age - 22.0) / 8.0) * .22
                mote(if (i % 3 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(a) * r, .35 + sin(a * 2) * .48, endpoint + sin(a) * r,
                    if (i % 3 == 0) null else root, spread = .014)
            }
        }
        StaffSpell.OSSUARY -> if (secondary) {
            if (ageTicks < OSSUARY_SECONDARY_ERUPT_TICK) {
                repeat(6) { i ->
                    val a = i * PI / 3
                    mote(Particle.DUST_COLOR_TRANSITION, cos(a) * .78, .04, sin(a) * .78, bone, spread = .008)
                }
            } else if (ageTicks <= OSSUARY_SECONDARY_STRIKE_TICK) {
                repeat(7) { spike ->
                    val angle = spike * PI * 2 / 6
                    val radius = if (spike == 0) 0.0 else .78
                    val stagger = (spike % 3) * OSSUARY_SECONDARY_STAGGER_TICKS
                    val rise = smooth((age - OSSUARY_SECONDARY_ERUPT_TICK - stagger).toDouble() /
                        OSSUARY_SECONDARY_RISE_TICKS)
                    mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * radius,
                        rise * (2.35 + q * .35), sin(angle) * radius, bone, spread = .01)
                    if (ageTicks == OSSUARY_SECONDARY_STRIKE_TICK)
                        mote(Particle.SOUL, cos(angle) * radius, 2.35 + q * .35, sin(angle) * radius)
                }
            }
        } else {
            val rise = smooth(age / OSSUARY_PRIMARY_RISE_TICKS)
            repeat(6) { i ->
                val height = i / 5.0 * (2.65 + q * .4) * rise
                mote(if (i == 5) Particle.SOUL else Particle.DUST_COLOR_TRANSITION,
                    0.0, height, 0.0, if (i == 5) null else bone, spread = .008)
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
    val samples = (0 until n).flatMap { i ->
        val f = i / (n - 1.0)
        val center = axis.clone().multiply(distance * front * f)
        val a = f * PI * 7 - age * .3
        val rim = center.clone().add(right.clone().multiply(cos(a) * (.16 + charge * .1)))
            .add(up.clone().multiply(sin(a) * (.16 + charge * .1)))
        listOf(StaffParticleSample(Particle.END_ROD, center),
            StaffParticleSample(Particle.DUST_COLOR_TRANSITION, rim, data = staffFade(0xffb8ef, 0x734de0, 1.5f)))
    }
    val contact = axis.clone().multiply(distance * front.coerceIn(0.0, 1.0))
    val flare = (0 until 12).map { i ->
        val a = i * PI / 6 - age * .08
        val point = contact.clone().add(right.clone().multiply(cos(a) * (.14 + charge * .08)))
            .add(up.clone().multiply(sin(a) * (.14 + charge * .08)))
        StaffParticleSample(if (i % 3 == 0) Particle.END_ROD else Particle.DUST_COLOR_TRANSITION,
            point, data = if (i % 3 == 0) null else staffFade(0xffe9ff, 0x8e70ee, 1.7f))
    }
    return samples + flare
}

private fun smooth(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
