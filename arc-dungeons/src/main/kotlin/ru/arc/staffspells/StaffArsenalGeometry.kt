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
    StaffSpell.TIDE -> if (secondary) 44 else STAFF_TIDE_PRIMARY_ATTACK_TICKS
    StaffSpell.REQUIEM -> if (secondary) STAFF_REQUIEM_SECONDARY_DURATION_TICKS else STAFF_REQUIEM_PRIMARY_DURATION_TICKS
    StaffSpell.NOVA -> if (secondary) 96 else 106
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
    if (spell == StaffSpell.REQUIEM)
        return staffRequiemBellParts(age, durationTicks, secondary, q)
    if (spell == StaffSpell.OSSUARY)
        return staffOssuaryRibParts(age, durationTicks, radius, secondary, q)
    val scale = .8 + q * .45
    val fade = smooth((durationTicks - 2 - age) / 8.0)
    val parts = mutableListOf<StaffDisplayPart>()
    fun part(id: String, material: Material, x: Double, y: Double, z: Double,
        sx: Double, sy: Double, sz: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0,
        orientation: Quaternionf? = null) {
        parts += StaffDisplayPart(material, Vector3f((x * scale).toFloat(), (y * scale).toFloat(), (z * scale).toFloat()),
            Vector3f((sx * scale * fade).coerceAtLeast(.001).toFloat(),
                (sy * scale * fade).coerceAtLeast(.001).toFloat(), (sz * scale * fade).coerceAtLeast(.001).toFloat()),
            orientation ?: Quaternionf().rotationXYZ(pitch.toFloat(), yaw.toFloat(), roll.toFloat()), identity = id)
    }
    when (spell) {
        StaffSpell.LUNAR -> {
            val fall = smooth((age - 4) / 12.0)
            val birth = smooth(age / 6.0)
            val converge = smooth(age / 16.0)
            val copies = if (secondary) 2 else 1
            // The paired blades break on contact; the existing shards carry the lingering field.
            val bladeLife = if (secondary) 1.0 - smooth((age - 16.0) / 6.0) else 1.0
            repeat(copies) { side ->
                val sideSign = side * 2 - 1
                val turn = if (secondary) sideSign * (.88 - converge * 1.34) else -.82 + converge * 1.64
                repeat(17) { i ->
                    val t = i / 16.0
                    val a = -2.15 + t * 4.3
                    val taper = sin(t * PI).coerceAtLeast(0.0)
                    val width = (.08 + taper * .52) * birth * bladeLife
                    val x = cos(a) * 1.85
                    val y = sin(a) * 1.85
                    val drop = if (secondary) 1.85 else 5.6 - fall * 3.35
                    val centerZ = if (secondary) sideSign * (2.35 - converge * 2.35) else -1.35 + converge * 1.35
                    val localX = x * cos(turn) - y * sin(turn)
                    val localY = drop + x * sin(turn) + y * cos(turn)
                    part("moon:$side:$i", if (i % 4 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                        localX * (.80 + bladeLife * .20), .28 + (localY - .28) * bladeLife, centerZ,
                        width, .62 * birth * bladeLife, .30 * birth * bladeLife,
                        roll = a + turn + sideSign * (1.0 - bladeLife) * .25)
                    if (!secondary) {
                        val echo = smooth((age - 4.0) / 5.0) * (1.0 - smooth((age - 17.0) / 5.0))
                        part("moon:echo:$i", if (i % 5 == 0) Material.SEA_LANTERN else Material.QUARTZ_BLOCK,
                            localX * .94, localY - .16, centerZ - .42,
                            width * echo * .62, .38 * echo, .20 * echo, roll = a + turn - .08)
                    }
                }
            }
            val impactRadius = (radius + .7).coerceIn(.8, 16.0)
            val release = smooth((age - 12.0) / 4.0)
            val drift = smooth((age - 14.0) / 2.0)
            val shardLife = 1.0 - smooth((age - 24.0) / 6.0)
            repeat(14) { shard ->
                // A deterministic irregular scatter avoids a circular, evenly-spaced halo.
                val angle = shard * 2.399963229728653 + sin(shard * 1.91) * .17
                val radialFraction = .53 + (shard * 7 % 13) / 13.0 * .22 + sin(shard * 2.3) * .025
                val radial = impactRadius * radialFraction * drift
                val x = cos(angle) * radial
                val z = sin(angle) * radial
                val lift = .18 + (shard % 4) * .12 + drift * (.18 + abs(sin(shard * 1.7)) * .24)
                val sizeBirth = release * shardLife
                part("moon:shard:$shard", when (shard % 4) {
                    0 -> Material.AMETHYST_BLOCK
                    1 -> Material.CALCITE
                    2 -> Material.SMOOTH_QUARTZ
                    else -> Material.QUARTZ_BLOCK
                }, x / scale, lift, z / scale,
                    (.34 + (shard % 3) * .08) * sizeBirth,
                    (.48 + (shard % 4) * .08) * sizeBirth,
                    (.30 + (shard % 2) * .08) * sizeBirth,
                    yaw = angle + .28, pitch = -.2 + sin(shard * 2.0) * .12,
                    roll = .22 + cos(shard * 1.4) * .16)
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
            if (secondary) {
                parts += staffNovaDisplayParts(ageTicks, durationTicks, radius, q)
            } else {
                val grow = smooth(age / 10.0)
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
        else -> Unit
    }
    return parts
}

/** Pure native-particle frames: substantial silhouettes with a bright core and a softer wake. */
internal fun staffArsenalParticles(spell: StaffSpell, ageTicks: Int, durationTicks: Int,
    radius: Double, secondary: Boolean, charge: Double, reach: Double = radius,
    impact: Boolean = false, rayLimits: List<Double> = emptyList()): List<StaffParticleSample> {
    val age = ageTicks.toDouble()
    val q = charge.coerceIn(0.0, 1.0)
    if (spell == StaffSpell.GALE || spell == StaffSpell.TIDE)
        return staffTideGaleParticleSamples(spell, ageTicks, durationTicks, radius, secondary, q, impact)
    if (spell == StaffSpell.REQUIEM)
        return staffRequiemParticles(ageTicks, durationTicks, secondary, radius, q, rayLimits)
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
            val converge = smooth(age / 16.0)
            val impactRadius = (radius + .7).coerceIn(.8, 16.0)
            // Stop replenishing the intact outline when the blades strike and start to crumble.
            repeat(if (secondary && age >= 16.0) 0 else if (secondary) 28 else 16) { i ->
                val side = if (secondary) i / 14 else 0
                val slot = if (secondary) i % 14 else i
                val t = slot / (if (secondary) 13.0 else 15.0)
                val a = -2.15 + t * 4.3
                val sign = side * 2 - 1
                val turn = if (secondary) sign * (.88 - converge * 1.34) else -.82 + converge * 1.64
                val x = cos(a) * 1.72
                val y = sin(a) * 1.72
                val baseY = if (secondary) 1.75 else 5.4 - fall * 3.3
                val z = if (secondary) sign * (2.25 - converge * 2.25) else -1.25 + converge * 1.25
                mote(if (i % 5 == 0) Particle.END_ROD else Particle.DUST_COLOR_TRANSITION,
                    x * cos(turn) - y * sin(turn), baseY + x * sin(turn) + y * cos(turn), z,
                    if (i % 5 == 0) null else silver, spread = .012)
            }
            if (ageTicks in 14..28) repeat(14) { shard ->
                val angle = shard * 2.399963229728653 + sin(shard * 1.91) * .17
                val fraction = .53 + (shard * 7 % 13) / 13.0 * .22 + sin(shard * 2.3) * .025
                val r = impactRadius * fraction * smooth((age - 12.0) / 4.0)
                mote(if (shard % 3 == 0) Particle.END_ROD else Particle.DUST_COLOR_TRANSITION,
                    cos(angle) * r, .22 + (shard % 4) * .12 + smooth((age - 14.0) / 2.0) * .3,
                    sin(angle) * r, if (shard % 3 == 0) null else silver, spread = .018)
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
            val extent = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.5, 16.0) ?: 3.0
            val close = smooth((age - 12.0) / 8.0)
            val outer = (extent - .45).coerceAtLeast(.25) * (1.0 - close * .34) * fade
            val angles = doubleArrayOf(-2.88, -1.45, -0.20, 1.14, 2.50)
            repeat(25) { i ->
                val vine = i / 5
                val step = i % 5
                val f = (step + .5) / 5.0
                val angle = angles[vine]
                val r = outer * (1.0 - f * .22 * close) + sin(f * PI + vine) * .05
                mote(if (step == 4) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(angle) * r, .12 + f * 1.5 + close * f * .12, sin(angle) * r,
                    if (step == 4) null else root, spread = .018)
            }
            repeat(5) { i ->
                val mid = (angles[i] + if (i == angles.lastIndex) angles[0] + 2.0 * PI else angles[i + 1]) * .5
                val r = outer * (.70 - close * .18)
                mote(Particle.DUST_COLOR_TRANSITION, cos(mid) * r, .72 + (i % 3) * .09 + close * .16,
                    sin(mid) * r, staffFade(0xffe69a, 0x61bb42, 1.2f), spread = .018)
            }
            repeat(5) { i ->
                val angle = angles[i]
                val r = outer * (.70 - close * .12)
                mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r, 1.04 + (i % 2) * .16,
                    sin(angle) * r, root, spread = .018)
            }
            if (ageTicks in 20..28) repeat(8) { i ->
                val angle = i * 2.399963229728653
                val r = .38 + smooth((age - 20.0) / 4.0) * .24
                mote(if (i % 2 == 0) Particle.HAPPY_VILLAGER else Particle.DUST_COLOR_TRANSITION,
                    cos(angle) * r, .62 + sin(angle * 2) * .42, sin(angle) * r,
                    if (i % 2 == 0) null else root, spread = .015)
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
            val extent = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.5, 16.0) ?: 5.5
            val angles = doubleArrayOf(-2.90, -1.63, -0.28, 1.02, 2.43)
            if (ageTicks < OSSUARY_SECONDARY_ERUPT_TICK) {
                repeat(10) { i ->
                    val angle = angles[i % angles.size] + (i / angles.size) * .14
                    val r = extent * (.28 + (i % 3) * .035)
                    mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r, .04 + (i % 2) * .03,
                        sin(angle) * r, bone, spread = .012)
                }
            } else if (ageTicks <= OSSUARY_SECONDARY_STRIKE_TICK + 2) {
                val spread = smooth(age / (OSSUARY_SECONDARY_STRIKE_TICK - 2.0))
                angles.forEachIndexed { cluster, angle ->
                    val stagger = (cluster % 3) * OSSUARY_SECONDARY_STAGGER_TICKS
                    val rise = smooth((age - OSSUARY_SECONDARY_ERUPT_TICK - stagger).toDouble() /
                        OSSUARY_SECONDARY_RISE_TICKS)
                    val r = extent * (.27 + spread * .07 + sin(cluster * 1.7) * .035)
                    val h = (2.15 + q * .35 + (cluster % 3) * .14) * rise
                    mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r, -.25 + h * .83,
                        sin(angle) * r, bone, spread = .014)
                    repeat(2) { branch ->
                        val branchAngle = angle + if (branch == 0) -.31 else .31
                        val tipR = r + extent * (.35 + rise * .10)
                        mote(Particle.DUST_COLOR_TRANSITION, cos(branchAngle) * tipR,
                            -.08 + h * .5 + rise * .48, sin(branchAngle) * tipR, bone, spread = .012)
                    }
                    if (ageTicks in OSSUARY_SECONDARY_STRIKE_TICK..(OSSUARY_SECONDARY_STRIKE_TICK + 2))
                        mote(Particle.SOUL, cos(angle) * r, -.25 + h * .85, sin(angle) * r)
                }
            }
        } else {
            if (ageTicks <= OSSUARY_PRIMARY_RISE_TICKS + 4) {
                val extent = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.5, 16.0) ?: 2.75
                val rise = smooth(age / OSSUARY_PRIMARY_RISE_TICKS.toDouble())
                val angles = doubleArrayOf(-2.78, -1.48, -0.16, 1.13, 2.48)
                angles.forEachIndexed { cluster, angle ->
                    val r = extent * (.22 + rise * .09 + abs(sin(cluster * 1.63 + .5)) * .018)
                    val h = (2.0 + q * .35 + (cluster % 3) * .19) * rise
                    mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r, -.2 + h * .84,
                        sin(angle) * r, bone, spread = .012)
                    repeat(2) { branch ->
                        val branchAngle = angle + if (branch == 0) -.42 else .42
                        val tipR = r + extent * (.34 + rise * .07)
                        mote(Particle.DUST_COLOR_TRANSITION, cos(branchAngle) * tipR,
                            .15 + h * .48 + rise * .42, sin(branchAngle) * tipR, bone, spread = .012)
                    }
                    if (ageTicks in 6..8) mote(Particle.SOUL, cos(angle) * (r + extent * .28),
                        .45 + h * .55, sin(angle) * (r + extent * .28))
                }
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
