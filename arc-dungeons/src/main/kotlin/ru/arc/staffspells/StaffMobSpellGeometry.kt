package ru.arc.staffspells

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Keep the existing charged body at the mob's muzzle instead of the player's distant POV anchor. */
internal fun staffMobChargeEye(muzzle: Location, direction: Vector, spell: StaffSpell): Location {
    val eye = muzzle.clone().setDirection(direction)
    if (spell == StaffSpell.EMBER) {
        val offset = staffChargeOrientation(spell, eye.yaw,
            Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
            .transform(staffEmberChargeOffset())
        eye.subtract(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }
    return eye
}

/** The existing solar lens, recentered at a hostile caster's hand and aimed at the locked point. */
internal fun staffMobLancePreparation(age: Int, duration: Int, direction: Vector): List<StaffDisplayPart> {
    val rotation = staffLanceOrientation(Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat()))
    return staffPreparationParts(StaffSpell.LANCE, false, age, duration).map { part ->
        part.copy(center = rotation.transform(Vector3f(part.center).sub(0f, .92f, 3.52f)),
            rotation = Quaternionf(rotation).mul(part.rotation))
    }
}

/** Fixed footprint plus four low beacons, readable while the defender looks at the caster. */
internal fun staffMobMarkBoundary(age: Int, duration: Int, radius: Double): List<StaffParticleSample> {
    if (duration <= 0 || age !in 0..duration || !radius.isFinite() || radius <= 0.0) return emptyList()
    val progress = age.toDouble() / duration
    val dust = staffDust(if (progress >= .75) 0xf1dcff else 0xbd7dff, if (progress >= .75) 1.15f else .9f)
    val ring = List(24) { index ->
        val angle = index * PI / 12.0
        StaffParticleSample(Particle.DUST, Vector(cos(angle) * radius, .06, sin(angle) * radius), data = dust)
    }
    val beacons = (0..3).flatMap { index ->
        val angle = index * PI / 2.0
        listOf(.32, .62, .92).map { height ->
            StaffParticleSample(Particle.DUST, Vector(cos(angle) * radius, height, sin(angle) * radius), data = dust)
        }
    }
    return ring + beacons
}
