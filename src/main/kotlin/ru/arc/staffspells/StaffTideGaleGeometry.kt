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

private const val TIDE_ORB_ARRIVAL_TICK = 28

/** Local surfaces shared by the solid ribbons and their native-particle accents. */
private fun galeFunnelPoint(t: Double, strand: Int, age: Double, q: Double, grow: Double): Vector3f {
    val scale = .8 + q * .45
    val angle = strand * PI * 2 / 3 + t * PI * 2.3 - age * (.055 + (1 - t) * .018)
    val taper = (.30 + 1.32 * t.pow(.82) + q * .06) * grow
    return Vector3f((cos(angle) * taper).toFloat(), (t * (5.35 + q * .30) / scale * grow).toFloat(),
        (sin(angle) * taper).toFloat())
}

private fun tideWavePoint(across: Double, t: Double, q: Double, grow: Double): Vector3f {
    val scale = .8 + q * .45
    val shoulder = .78 + .22 * (1.0 - abs(across))
    val curl = smoothTideGale((t - .60) / .40)
    val heightT = if (t >= .90) t - .06 else t
    val z = -.10 + .72 * sin(t * PI * .72) + .70 * curl * curl + .18 * (1.0 - abs(across))
    return Vector3f((across * (2.30 + q * .15) / scale * grow).toFloat(),
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
        StaffSpell.GALE -> if (secondary) {
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
            // Three broad translucent ribbons counter-rotate and widen into the crown.
            repeat(3) { strand ->
                val nodes = 11
                repeat(nodes) { index ->
                    val t0 = index / nodes.toDouble()
                    val t1 = (index + 1) / nodes.toDouble()
                    fun point(t: Double) = galeFunnelPoint(t, strand, age.toDouble(), q, grow)
                    val midT = (t0 + t1) / 2.0
                    val midAngle = strand * PI * 2 / 3 + midT * PI * 2.3 - age * (.055 + (1 - midT) * .018)
                    val slope = 1.32 * .82 * midT.pow(-.18) / height
                    val radialNormal = Vector3f(cos(midAngle).toFloat(), (-slope).toFloat(), sin(midAngle).toFloat())
                    add(tideGaleRibbonLink(
                        when (strand) {
                            0 -> Material.LIGHT_GRAY_STAINED_GLASS
                            1 -> Material.GRAY_STAINED_GLASS
                            else -> Material.LIGHT_GRAY_STAINED_GLASS
                        },
                        point(t0), point(t1), (.70 - .04 * t0).toFloat(), .14f, radialNormal,
                        "gale:tornado:band:$strand:$index"))
                }
            }
            // Small separated facets suggest lifted grit without filling the open funnel.
            repeat(6) { index ->
                val t = .10 + (index % 3) * .16
                val angle = index * PI / 3 - age * .08
                val r = (1.50 - t * .55) * grow
                val size = (.15 + (index % 2) * .045) * grow
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
            val halfWidth = (2.30 + q * .15) / scale
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
            val grow = smoothTideGale(age / 7.0)
            val startRadius = .58 + q * .12
            val fullRadius = (radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.6, 2.2) ?: 1.8) / scale
            val prisonRadius = startRadius + (fullRadius - startRadius) * grow
            mutableListOf<StaffDisplayPart>().also { shell ->
                addTideSphereShell(shell, prisonRadius, TIDE_ORB_ARRIVAL_TICK * .025 + age * .012, "tide:shell")
            }.forEach(::add)
            repeat(6) { index ->
                val angle = index * PI / 3 + age * .018
                val r = prisonRadius * .94
                val size = (.13 + (index % 2) * .04) * grow
                add(StaffDisplayPart(Material.WHITE_STAINED_GLASS,
                    Vector3f((cos(angle) * r).toFloat(), (prisonRadius * (.92 + index % 2 * .08)).toFloat(),
                        (sin(angle) * r).toFloat()), Vector3f(size.toFloat(), (size * 2).toFloat(), size.toFloat()),
                    Quaternionf().rotateY((-angle).toFloat()), identity = "tide:prison:foam:$index"))
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
    if (age < 0.0 || age == 0.0 && !impact || age >= durationTicks - 6.0) return emptyList()
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
        StaffSpell.GALE -> if (secondary) {
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
                val t = .1 + (index % 3) * .16
                val angle = index * PI / 3 - age * .08
                val r = (1.5 - t * .55) * grow * scale
                mote(Particle.CRIT, cos(angle) * r, t * (5.35 + q * .30) * grow, sin(angle) * r,
                    spread = .012, speed = .002)
            }
            if (ageTicks % 6 == 0) mote(Particle.GUST, 0.0, 1.0, 0.0, spread = .16)
        }
        StaffSpell.TIDE -> if (secondary) {
            val grow = smoothTideGale(age / 6.0)
            val halfWidth = 2.30 + q * .15
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
        } else if (!impact && age < 28.0) {
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
            val stageAge = if (impact) age else age - 28.0
            val grow = smoothTideGale(stageAge / 7.0)
            val startRadius = (.58 + q * .12) * scale
            val fullRadius = radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(1.6, 2.2) ?: 1.8
            val r = startRadius + (fullRadius - startRadius) * grow
            repeat(24) { index ->
                val y = 1.0 - 2.0 * (index + .5) / 24
                val ring = sqrt(1.0 - y * y)
                val angle = index * 2.39996 + TIDE_ORB_ARRIVAL_TICK * .025 + stageAge * .012
                val x = cos(angle) * ring * r
                val z = sin(angle) * ring * r
                mote(if (index % 4 == 0) Particle.BUBBLE else Particle.DUST_COLOR_TRANSITION,
                    x, y * r, z, if (index % 4 == 0) null else water, .009)
            }
            repeat(6) { index ->
                val angle = index * 2.39996 + stageAge * .025
                mote(Particle.SPLASH, cos(angle) * r, r * (.82 + (index % 3) * .04), sin(angle) * r,
                    count = 1, spread = .025, speed = .012)
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
