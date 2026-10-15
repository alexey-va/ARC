package ru.arc.staffspells

import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal const val STAFF_TIDE_PRIMARY_LAUNCH_TICK = 2
internal const val STAFF_TIDE_PRIMARY_TRAVEL_TICKS = 12
internal const val STAFF_TIDE_PRIMARY_ARRIVAL_TICK =
    STAFF_TIDE_PRIMARY_LAUNCH_TICK + STAFF_TIDE_PRIMARY_TRAVEL_TICKS
internal const val STAFF_TIDE_PRIMARY_IMPACT_TICKS = 12
internal const val STAFF_TIDE_PRIMARY_ATTACK_TICKS =
    STAFF_TIDE_PRIMARY_ARRIVAL_TICK + STAFF_TIDE_PRIMARY_IMPACT_TICKS
internal const val STAFF_TIDE_SECONDARY_BASE_HALF_WIDTH = 3.0
internal const val STAFF_TIDE_SECONDARY_CHARGE_HALF_WIDTH = .30

internal fun staffTideWaveHalfWidth(charge: Double): Double {
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    return STAFF_TIDE_SECONDARY_BASE_HALF_WIDTH + q * STAFF_TIDE_SECONDARY_CHARGE_HALF_WIDTH
}

/** Local surfaces shared by the solid ribbons and their native-particle accents. */
private fun galeFunnelPoint(t: Double, strand: Int, age: Double, q: Double, grow: Double): Vector3f {
    val scale = .8 + q * .45
    val phase = strand * PI * 2 / 3 + t * PI * 2.3 - age * (.22 + (1 - t) * .065)
    val turbulence = sin(age * .38 + t * 13.0 + strand * 2.1) * .18 +
        sin(age * .53 - t * 7.4 + strand * 1.3) * .07
    val angle = phase + turbulence
    val gust = 1.0 + .09 * sin(age * .23 + t * 10.5 + strand * 2.7) +
        .04 * sin(age * .37 - t * 17.0 + strand * .8)
    val taper = (.34 + 2.05 * t.pow(.82) + q * .06) * grow * gust
    val height = t * (5.35 + q * .30) / scale * grow +
        sin(age * .15 + t * 14.0 + strand * 1.7) * .07 * grow
    return Vector3f((cos(angle) * taper).toFloat(), height.toFloat(), (sin(angle) * taper).toFloat())
}

private fun tideWavePoint(across: Double, t: Double, q: Double, grow: Double): Vector3f {
    val scale = .8 + q * .45
    val shoulder = .78 + .22 * (1.0 - abs(across))
    val curl = smoothTideGale((t - .60) / .40)
    val heightT = if (t >= .90) t - .06 else t
    val z = -.10 + .72 * sin(t * PI * .72) + .70 * curl * curl + .18 * (1.0 - abs(across))
    return Vector3f((across * staffTideWaveHalfWidth(q) / scale * grow).toFloat(),
        (heightT * (2.75 + q * .32) / scale * shoulder * grow).toFloat(), (z * grow).toFloat())
}

/** Readable local block-display silhouettes for the two water and wind arsenal spells. */
internal fun staffTideGaleParts(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    radius: Double,
    impact: Boolean,
    secondary: Boolean,
    charge: Double,
): List<StaffDisplayPart> {
    if (spell != StaffSpell.GALE && spell != StaffSpell.TIDE) return emptyList()
    val age = ageTicks.coerceAtLeast(0)
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    val scale = .8 + q * .45
    val fade = smoothTideGale((durationTicks - 2.0 - age) / 8.0)
    val parts = mutableListOf<StaffDisplayPart>()

    fun add(part: StaffDisplayPart) {
        parts += part.copy(
            center = Vector3f(part.center).mul(scale.toFloat()),
            scale = Vector3f(part.scale).mul((scale * fade).coerceAtLeast(.001).toFloat()),
        )
    }

    when (spell) {
        StaffSpell.GALE -> if (secondary && impact) {
            val grow = smoothTideGale(age / 3.0)
            val blastRadius = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.6, 3.2) ?: 2.3
            val radiusInScene = blastRadius / scale * grow
            // A single expanding, uneven wind ring carries the contact release without a smoke cloud.
            repeat(18) { index ->
                val a0 = index * PI * 2 / 18 + age * .08
                val a1 = (index + 1) * PI * 2 / 18 + age * .08
                val r0 = radiusInScene * (.82 + .18 * sin(a0 * 3 + age * .24))
                val r1 = radiusInScene * (.82 + .18 * sin(a1 * 3 + age * .24))
                val p0 = Vector3f((cos(a0) * r0).toFloat(), (.20 + .18 * sin(a0 * 2 + age * .22)).toFloat(),
                    (sin(a0) * r0).toFloat())
                val p1 = Vector3f((cos(a1) * r1).toFloat(), (.20 + .18 * sin(a1 * 2 + age * .22)).toFloat(),
                    (sin(a1) * r1).toFloat())
                add(tideGaleLink(if (index % 4 == 0) Material.WHITE_STAINED_GLASS else Material.GRAY_STAINED_GLASS,
                    p0, p1, .20f, .22f, "gale:burst:arc:$index"))
            }
            repeat(6) { index ->
                val angle = index * PI / 3 - age * .11
                val r = radiusInScene * (.54 + (index % 2) * .12)
                val size = (.18 + (index % 3) * .035) * grow
                add(StaffDisplayPart(
                    if (index % 2 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_GRAY_STAINED_GLASS,
                    Vector3f((cos(angle) * r).toFloat(), (.32 + (index % 3) * .10).toFloat(), (sin(angle) * r).toFloat()),
                    Vector3f((size * .7).toFloat(), (size * 1.5).toFloat(), (size * 2.8).toFloat()),
                    Quaternionf().rotateY((-angle).toFloat()).rotateZ((-.22 + index * .07).toFloat()),
                    identity = "gale:burst:shard:$index"))
            }
        } else if (secondary) {
            val grow = smoothTideGale(age / 4.0)
            // Two interwoven bands run through a hollow, forward-facing ram head.
            repeat(2) { strand ->
                val phase = strand * PI + age * .065
                val nodes = 12
                repeat(nodes) { index ->
                    val t0 = index / nodes.toDouble()
                    val t1 = (index + 1) / nodes.toDouble()
                    fun point(t: Double): Vector3f {
                        val angle = phase + t * PI * 4.0
                        val r = (.27 + .18 * sin(t * PI)) * grow
                        return Vector3f((cos(angle) * r).toFloat(), (.10 + sin(angle) * r).toFloat(),
                            ((-.90 + t * 2.05) * grow).toFloat())
                    }
                    add(tideGaleLink(
                        if (strand == 0) Material.LIGHT_GRAY_STAINED_GLASS else Material.GRAY_STAINED_GLASS,
                        point(t0), point(t1), .28f, .17f, "gale:ram:helix:$strand:$index"))
                }
            }
            val headRadius = .52 * grow
            repeat(10) { index ->
                val a0 = index * PI * 2 / 10 + age * .035
                val a1 = (index + 1) * PI * 2 / 10 + age * .035
                add(tideGaleLink(
                    if (index % 3 == 0) Material.WHITE_STAINED_GLASS else Material.LIGHT_GRAY_STAINED_GLASS,
                    Vector3f((cos(a0) * headRadius).toFloat(), (.10 + sin(a0) * headRadius).toFloat(), (1.02 * grow).toFloat()),
                    Vector3f((cos(a1) * headRadius).toFloat(), (.10 + sin(a1) * headRadius).toFloat(), (1.02 * grow).toFloat()),
                    .20f, .18f, "gale:ram:head:$index"))
            }
        } else {
            val grow = smoothTideGale(age / 6.0)
            val height = (5.35 + q * .30) / scale
            // Three fast helical ribbons shear around an open core and flare into the crown.
            repeat(3) { strand ->
                val nodes = 11
                repeat(nodes) { index ->
                    val t0 = index / nodes.toDouble()
                    val t1 = (index + 1) / nodes.toDouble()
                    fun point(t: Double) = galeFunnelPoint(t, strand, age.toDouble(), q, grow)
                    val midT = (t0 + t1) / 2.0
                    val midAngle = strand * PI * 2 / 3 + midT * PI * 2.3 - age * (.22 + (1 - midT) * .065)
                    val slope = 2.05 * .82 * midT.pow(-.18) / height
                    val radialNormal = Vector3f(cos(midAngle).toFloat(), (-slope).toFloat(), sin(midAngle).toFloat())
                    add(tideGaleRibbonLink(
                        when (strand) {
                            0 -> Material.WHITE_STAINED_GLASS
                            1 -> Material.GRAY_STAINED_GLASS
                            else -> Material.LIGHT_GRAY_STAINED_GLASS
                        },
                        point(t0), point(t1), (.84 - .06 * t0).toFloat(), .14f, radialNormal,
                        "gale:tornado:band:$strand:$index"))
                }
            }
            // Small separated facets suggest lifted grit without filling the open funnel.
            repeat(6) { index ->
                val t = .12 + .68 * (.5 + .5 * sin(age * .24 + index * PI / 3))
                val angle = index * PI / 3 - age * .30
                val r = (.45 + t * 2.10) * grow
                val size = (.22 + (index % 2) * .06) * grow
                add(StaffDisplayPart(
                    when (index % 3) {
                        0 -> Material.BIRCH_LEAVES
                        1 -> Material.GRAY_STAINED_GLASS
                        else -> Material.WHITE_STAINED_GLASS
                    },
                    Vector3f((cos(angle) * r).toFloat(), (t * height * grow).toFloat(), (sin(angle) * r).toFloat()),
                    Vector3f(size.toFloat(), (size * 1.8).toFloat(), (size * 1.6).toFloat()),
                    Quaternionf().rotateXYZ((angle * .35).toFloat(), angle.toFloat(), (age * .035).toFloat()),
                    identity = "gale:tornado:debris:$index"))
            }
        }
        StaffSpell.TIDE -> if (secondary) {
            val grow = smoothTideGale(age / 6.0)
            val halfWidth = staffTideWaveHalfWidth(q) / scale
            val crestHeight = (2.75 + q * .32) / scale
            val acrossSegments = 7
            val rows = listOf(.06, .28, .52, .76, .96)
            fun wavePoint(x: Double, t: Double) = tideWavePoint(x / halfWidth, t, q, grow)
            rows.forEachIndexed { row, t ->
                repeat(acrossSegments) { index ->
                    val x0 = -halfWidth + (index / acrossSegments.toDouble()) * halfWidth * 2
                    val x1 = -halfWidth + ((index + 1) / acrossSegments.toDouble()) * halfWidth * 2
                    add(tideGaleLink(
                        when (row) {
                            0 -> Material.BLUE_STAINED_GLASS
                            1 -> Material.CYAN_STAINED_GLASS
                            else -> Material.LIGHT_BLUE_STAINED_GLASS
                        }, wavePoint(x0, t), wavePoint(x1, t), (.34 + t * .12).toFloat(), (.72 / scale).toFloat(),
                        "tide:wave:skin:$row:$index"))
                }
            }
            repeat(acrossSegments) { index ->
                val x0 = -halfWidth + (index / acrossSegments.toDouble()) * halfWidth * 2
                val x1 = -halfWidth + ((index + 1) / acrossSegments.toDouble()) * halfWidth * 2
                val foamStart = wavePoint(x0, rows.last()).add(0f, (.02 * grow).toFloat(), (.05 * grow).toFloat())
                val foamEnd = wavePoint(x1, rows.last()).add(0f, (.02 * grow).toFloat(), (.05 * grow).toFloat())
                add(tideGaleLink(
                    if (index % 3 == 0) Material.SEA_LANTERN else Material.WHITE_STAINED_GLASS,
                    foamStart, foamEnd, .27f, .30f, "tide:wave:foam:$index"))
            }
            // A few detached crest beads keep the lip lively without making a fog bank.
            repeat(4) { index ->
                val x = (-.75 + index * .5) * halfWidth * grow
                val size = (.13 + (index % 2) * .035) * grow
                add(StaffDisplayPart(Material.WHITE_STAINED_GLASS,
                    Vector3f(x.toFloat(), (crestHeight * (1.02 + (index % 2) * .06) * grow).toFloat(),
                        (-.34 * grow).toFloat()), Vector3f(size.toFloat(), (size * 1.8).toFloat(), size.toFloat()),
                    Quaternionf().rotateZ((-.25 + index * .16).toFloat()), identity = "tide:wave:bead:$index"))
            }
        } else if (impact) {
            val fullRadius = (radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.8, 2.6) ?: 2.3) / scale
            val radialGrow = smoothTideGale(age / 6.0)
            val crownRadius = .42 + (fullRadius - .42) * radialGrow
            val rise = smoothTideGale(age / 3.0) *
                (1.0 - smoothTideGale((age - 5.0) / STAFF_TIDE_PRIMARY_IMPACT_TICKS))
            repeat(24) { index ->
                val a0 = index * PI * 2 / 24 + age * .035
                val a1 = (index + 1) * PI * 2 / 24 + age * .035
                val lobe0 = (cos(a0 * 6.0 - age * .11) + .15).coerceAtLeast(0.0).pow(2)
                val lobe1 = (cos(a1 * 6.0 - age * .11) + .15).coerceAtLeast(0.0).pow(2)
                val p0 = Vector3f((cos(a0) * crownRadius).toFloat(),
                    (.22 + rise * (.35 + lobe0 * .92)).toFloat(), (sin(a0) * crownRadius).toFloat())
                val p1 = Vector3f((cos(a1) * crownRadius).toFloat(),
                    (.22 + rise * (.35 + lobe1 * .92)).toFloat(), (sin(a1) * crownRadius).toFloat())
                val material = when (index % 4) {
                    0 -> Material.SEA_LANTERN
                    1 -> Material.LIGHT_BLUE_STAINED_GLASS
                    2 -> Material.CYAN_STAINED_GLASS
                    else -> Material.BLUE_STAINED_GLASS
                }
                add(tideGaleLink(material, p0, p1, (.18 + .08 * maxOf(lobe0, lobe1)).toFloat(), .17f,
                    "tide:burst:crown:$index"))
            }
            val dropletRise = smoothTideGale(age / 6.0)
            val fall = smoothTideGale((age - 6.0) / 6.0)
            repeat(8) { index ->
                val angle = index * PI / 4 + age * .065
                val r = .38 + dropletRise * (.72 + (index % 2) * .24)
                val y = .42 + dropletRise * (1.05 + (index % 3) * .14) - fall * .28
                val size = .12 + (index % 3) * .025
                add(StaffDisplayPart(
                    when (index % 3) {
                        0 -> Material.CYAN_STAINED_GLASS
                        1 -> Material.LIGHT_BLUE_STAINED_GLASS
                        else -> Material.SEA_LANTERN
                    }, Vector3f((cos(angle) * r).toFloat(), y.toFloat(), (sin(angle) * r).toFloat()),
                    Vector3f(size.toFloat(), (size * 1.9).toFloat(), size.toFloat()),
                    Quaternionf().rotateZ((-.35 + index * .09).toFloat()).rotateY((-angle).toFloat()),
                    identity = "tide:burst:drop:$index"))
            }
        } else {
            val grow = smoothTideGale(age / 5.0)
            val orbRadius = (.58 + q * .12) * grow
            mutableListOf<StaffDisplayPart>().also { shell ->
                addTideSphereShell(shell, orbRadius, age * .025, "tide:shell")
            }.forEach(::add)
        }
        else -> Unit
    }
    return parts
}

/** Native particles add motion at the edge of each authored block-display silhouette. */
internal fun staffTideGaleParticleSamples(
    spell: StaffSpell,
    ageTicks: Int,
    durationTicks: Int,
    radius: Double,
    secondary: Boolean,
    charge: Double,
    impact: Boolean = false,
): List<StaffParticleSample> {
    if (spell != StaffSpell.GALE && spell != StaffSpell.TIDE) return emptyList()
    val age = ageTicks.toDouble()
    val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
    // Native particles outlive an emission. Stop feeding them during the display's exit;
    // shrinking their spawn positions would pull the whole wake back into the center.
    val exitTicks = if (spell == StaffSpell.TIDE && !secondary &&
        (impact || age >= STAFF_TIDE_PRIMARY_ARRIVAL_TICK)) 2.0 else 6.0
    if (age < 0.0 || age == 0.0 && !impact || age >= durationTicks - exitTicks) return emptyList()
    val scale = .8 + q * .45
    val out = mutableListOf<StaffParticleSample>()
    fun mote(particle: Particle, x: Double, y: Double, z: Double, data: Any? = null,
        spread: Double = .01, speed: Double = 0.0, count: Int = 1) {
        out += StaffParticleSample(particle, Vector(x, y, z), count,
            Vector(spread, spread, spread), speed, data)
    }
    val wind = staffFade(0xf4f8fa, 0x7f9299, .75f)
    val water = staffFade(0x70eaff, 0x147eb0, .9f)

    when (spell) {
        StaffSpell.GALE -> if (secondary && impact) {
            val grow = smoothTideGale(age / 3.0)
            val blastRadius = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.6, 3.2) ?: 2.3
            if (ageTicks == 0) mote(Particle.GUST, 0.0, .16, 0.0, spread = .08)
            repeat(18) { index ->
                val angle = index * PI * 2 / 18 + age * .08
                val r = blastRadius * (.82 + .18 * sin(angle * 3 + age * .24)) * grow
                mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r,
                    .20 + .18 * sin(angle * 2 + age * .22), sin(angle) * r, wind, .008)
            }
            repeat(6) { index ->
                val angle = index * PI / 3 - age * .11
                val r = blastRadius * (.54 + (index % 2) * .12) * grow
                mote(Particle.CRIT, cos(angle) * r, .32 + (index % 3) * .10, sin(angle) * r,
                    spread = .012, speed = .004)
            }
        } else if (secondary) {
            val grow = smoothTideGale(age / 4.0) * scale
            repeat(20) { index ->
                val strand = index / 10
                val step = (index % 10) / 9.0
                val angle = strand * PI + step * PI * 4 + age * .065
                val r = (.27 + .18 * sin(step * PI)) * grow
                mote(Particle.DUST_COLOR_TRANSITION, cos(angle) * r, .1 * scale + sin(angle) * r,
                    (-.9 + step * 2.05) * grow, wind, .012)
            }
            repeat(4) { index ->
                val angle = index * PI / 3 + age * .07
                mote(Particle.CRIT, cos(angle) * .42 * grow, .1 + sin(angle) * .42 * grow,
                    .78 * grow, spread = .015, speed = .005)
            }
            if (ageTicks % 4 == 0) mote(Particle.GUST, 0.0, .1, 0.0, spread = .12)
        } else {
            val grow = smoothTideGale(age / 6.0)
            repeat(33) { index ->
                val strand = index / 11
                val t = (index % 11) / 11.0
                val point = galeFunnelPoint(t, strand, age, q, grow)
                    .add(galeFunnelPoint(t + 1.0 / 11, strand, age, q, grow)).mul((scale * .5).toFloat())
                mote(Particle.DUST_COLOR_TRANSITION, point.x.toDouble(), point.y.toDouble(),
                    point.z.toDouble(), wind, .009)
            }
            repeat(4) { index ->
                val t = .12 + .68 * (.5 + .5 * sin(age * .24 + index * PI / 3))
                val angle = index * PI / 3 - age * .30
                val r = (.45 + t * 2.10) * grow * scale
                mote(Particle.CRIT, cos(angle) * r, t * (5.35 + q * .30) * grow, sin(angle) * r,
                    spread = .012, speed = .002)
            }
        }
        StaffSpell.TIDE -> if (secondary) {
            val grow = smoothTideGale(age / 6.0)
            val halfWidth = staffTideWaveHalfWidth(q)
            repeat(28) { index ->
                val crest = index >= 14
                val across = (index % 14) / 13.0 * 2 - 1
                val point = tideWavePoint(across, if (crest) .96 else .28, q, grow).mul(scale.toFloat())
                mote(if (crest) Particle.SPLASH else Particle.DUST_COLOR_TRANSITION,
                    point.x.toDouble(), point.y.toDouble(), point.z.toDouble(), if (crest) null else water,
                    spread = if (crest) .022 else .008, speed = if (crest) .018 else 0.0)
            }
            repeat(6) { index ->
                val x = (index / 5.0 * 2 - 1) * halfWidth * grow
                mote(Particle.BUBBLE, x, .04 * grow, -.24 * grow, spread = .015, speed = .004)
            }
        } else if (!impact && age < STAFF_TIDE_PRIMARY_ARRIVAL_TICK) {
            val grow = smoothTideGale(age / 5.0) * scale
            repeat(24) { index ->
                val y = 1.0 - 2.0 * (index + .5) / 24
                val ring = sqrt(1.0 - y * y)
                val angle = index * 2.39996 + age * .025
                val r = (.58 + q * .12) * grow
                mote(if (index % 4 == 0) Particle.BUBBLE else Particle.DUST_COLOR_TRANSITION,
                    cos(angle) * ring * r, y * r, sin(angle) * ring * r,
                    if (index % 4 == 0) null else water, .008)
            }
            repeat(8) { index ->
                val z = -.48 - index * .13
                val angle = index * 2.1 + age * .09
                mote(if (index % 3 == 0) Particle.SPLASH else Particle.BUBBLE,
                    cos(angle) * (.22 + index * .015), sin(angle) * .22, z,
                    count = 1, spread = .018, speed = .012)
            }
        } else {
            val stageAge = if (impact) age else age - STAFF_TIDE_PRIMARY_ARRIVAL_TICK
            val grow = smoothTideGale(stageAge / 6.0)
            val fullRadius = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.8, 2.6) ?: 2.3
            val r = .42 + (fullRadius - .42) * grow
            val rise = smoothTideGale(stageAge / 3.0) *
                (1.0 - smoothTideGale((stageAge - 5.0) / STAFF_TIDE_PRIMARY_IMPACT_TICKS))
            repeat(24) { index ->
                val angle = index * PI * 2 / 24 + STAFF_TIDE_PRIMARY_ARRIVAL_TICK * .025 + stageAge * .035
                val lobe = (cos(angle * 6.0 - stageAge * .11) + .15).coerceAtLeast(0.0).pow(2)
                val y = .22 + rise * (.35 + lobe * .92)
                mote(Particle.SPLASH, cos(angle) * r, y, sin(angle) * r,
                    spread = .012, speed = .012 + lobe * .012)
            }
            repeat(8) { index ->
                val angle = index * PI / 4 + stageAge * .065
                val dropletRadius = .38 + grow * (.72 + (index % 2) * .24)
                val y = .42 + grow * (1.05 + (index % 3) * .14) -
                    smoothTideGale((stageAge - 6.0) / 6.0) * .28
                mote(Particle.FALLING_WATER, cos(angle) * dropletRadius, y, sin(angle) * dropletRadius,
                    spread = .008, speed = .012)
            }
        }
        else -> Unit
    }
    return out
}

private fun addTideSphereShell(parts: MutableList<StaffDisplayPart>, radius: Double, spin: Double, prefix: String) {
    val r = radius.coerceAtLeast(.001)
    val latitudeStep = PI / 4.0
    val longitudeStep = PI / 4.0
    repeat(4) { band ->
        val latitude = -PI / 2.0 + (band + .5) * latitudeStep
        val cosLatitude = cos(latitude)
        val panelHeight = 2.0 * r * sin(latitudeStep / 2.0) * 1.18
        repeat(8) { longitudeIndex ->
            val longitude = longitudeIndex * longitudeStep + longitudeStep / 2.0 + spin
            val normal = Vector3f(
                (cosLatitude * cos(longitude)).toFloat(),
                sin(latitude).toFloat(),
                (cosLatitude * sin(longitude)).toFloat(),
            ).normalize()
            val center = Vector3f(normal).mul((r * .94).toFloat())
            val panelWidth = (2.0 * r * cosLatitude * sin(longitudeStep / 2.0) * 1.18).coerceAtLeast(.001)
            val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), normal)
            val material = when ((band + longitudeIndex) % 3) {
                0 -> Material.LIGHT_BLUE_STAINED_GLASS
                1 -> Material.CYAN_STAINED_GLASS
                else -> Material.BLUE_STAINED_GLASS
            }
            parts += StaffDisplayPart(
                material,
                center,
                Vector3f(panelWidth.toFloat(), panelHeight.toFloat(), (r * .16).toFloat()),
                rotation,
                identity = "$prefix:panel:$band:$longitudeIndex",
            )
        }
    }
    repeat(2) { pole ->
        val sign = if (pole == 0) -1.0 else 1.0
        parts += StaffDisplayPart(
            Material.CYAN_STAINED_GLASS,
            Vector3f(0f, (sign * r * .94).toFloat(), 0f),
            Vector3f((r * .76).toFloat(), (r * .76).toFloat(), (r * .16).toFloat()),
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(0f, sign.toFloat(), 0f)),
            identity = "$prefix:pole:$pole",
        )
    }
}

private fun tideGaleLink(
    material: Material,
    start: Vector3f,
    end: Vector3f,
    faceWidth: Float,
    thickness: Float,
    identity: String,
): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val length = delta.length().coerceAtLeast(.001f)
    val rotation = if (delta.lengthSquared() <= 1.0e-8f) Quaternionf()
        else Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(delta).normalize())
    return StaffDisplayPart(material, Vector3f(start).add(end).mul(.5f),
        Vector3f(faceWidth, thickness, length * 1.16f), rotation, identity = identity)
}

private fun tideGaleRibbonLink(
    material: Material,
    start: Vector3f,
    end: Vector3f,
    faceWidth: Float,
    thickness: Float,
    radialNormal: Vector3f,
    identity: String,
): StaffDisplayPart {
    val delta = Vector3f(end).sub(start)
    val lengthSquared = delta.lengthSquared()
    val length = delta.length().coerceAtLeast(.001f)
    val tangent = if (lengthSquared <= 1.0e-8f) Vector3f(0f, 0f, 1f) else delta.normalize()
    var faceNormal = Vector3f(radialNormal).sub(Vector3f(tangent).mul(radialNormal.dot(tangent)))
    if (faceNormal.lengthSquared() <= 1.0e-8f) {
        val fallback = if (abs(tangent.y) < .9f) Vector3f(0f, 1f, 0f) else Vector3f(1f, 0f, 0f)
        faceNormal = fallback.sub(Vector3f(tangent).mul(fallback.dot(tangent)))
    }
    faceNormal.normalize()
    val widthAxis = Vector3f(faceNormal).cross(tangent).normalize()
    val rotation = Quaternionf().setFromNormalized(Matrix3f().set(widthAxis, faceNormal, tangent))
    return StaffDisplayPart(material, Vector3f(start).add(end).mul(.5f),
        Vector3f(faceWidth, thickness, length * 1.16f), rotation, identity = identity)
}

private fun smoothTideGale(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.let {
    it * it * (3.0 - 2.0 * it)
} ?: 0.0
