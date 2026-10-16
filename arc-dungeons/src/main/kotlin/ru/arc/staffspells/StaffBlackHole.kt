package ru.arc.staffspells

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.Mob
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.floor

internal const val STAFF_BLACK_HOLE_TICKS = 40
internal const val STAFF_BLACK_HOLE_RELEASE_TICKS = 10
internal const val STAFF_BLACK_HOLE_RADIUS = 4.5
internal const val STAFF_BLACK_HOLE_TARGETS = 6
internal const val STAFF_BLACK_HOLE_CORE_RADIUS = .65
internal const val STAFF_BLACK_HOLE_MIN_SCALE = .18

internal fun staffBlackHoleGrowth(fed: Int): Double = 1.0 + fed.coerceIn(0, STAFF_BLACK_HOLE_TARGETS) * .08

/** Source-shared two-tick poses; the server still owns native movement and collision. */
internal fun staffBlackHoleScale(distance: Double, previous: Double): Double {
    val approach = ((distance - STAFF_BLACK_HOLE_CORE_RADIUS) /
        (STAFF_BLACK_HOLE_RADIUS - STAFF_BLACK_HOLE_CORE_RADIUS)).coerceIn(0.0, 1.0)
    val desired = STAFF_BLACK_HOLE_MIN_SCALE + (1.0 - STAFF_BLACK_HOLE_MIN_SCALE) *
        approach * approach * (3.0 - 2.0 * approach)
    return previous + (desired - previous).coerceIn(-.16, .16)
}

internal fun staffBlackHoleVelocity(offset: Vector): Vector {
    val velocity = offset.clone().multiply(.32)
    if (velocity.lengthSquared() > .48 * .48) velocity.normalize().multiply(.48)
    // Counter native gravity over the two-tick control interval without changing the mob's flags.
    velocity.y = (velocity.y + .12).coerceIn(-.24, .36)
    return velocity
}

internal fun staffBlackHoleReleaseScale(from: Double, age: Int): Double {
    val t = (age.toDouble() / STAFF_BLACK_HOLE_RELEASE_TICKS).coerceIn(0.0, 1.0)
    return from + (1.0 - from) * t * t * (3.0 - 2.0 * t)
}

/** Owned by PendingStaffMark; no scheduler or second cast lifecycle. */
internal class StaffBlackHole(attackId: UUID) {
    val scaleKey = NamespacedKey("arcdungeons", "staff_black_hole/$attackId")
    val targets = linkedSetOf<UUID>()
    val captives = linkedMapOf<UUID, StaffBlackHoleCaptive>()
    val fed = mutableSetOf<UUID>()
    var growth = 1.0

    fun capture(mob: Mob, core: Location) {
        if (mob.uniqueId in captives) return
        val attribute = mob.getAttribute(Attribute.SCALE) ?: return
        val box = mob.boundingBox
        val outward = center(mob).toVector().subtract(core.toVector()).apply { y = 0.0 }
        if (outward.lengthSquared() < .001) outward.x = 1.0
        captives[mob.uniqueId] = StaffBlackHoleCaptive(mob, attribute, box.widthX, box.height,
            box.widthZ, outward.normalize())
    }

    fun resize(captive: StaffBlackHoleCaptive, scale: Double) {
        if (kotlin.math.abs(scale - captive.scale) < .0001) return
        captive.attribute.getModifier(scaleKey)?.let(captive.attribute::removeModifier)
        if (scale < .9999) captive.attribute.addTransientModifier(AttributeModifier(
            scaleKey, scale - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1))
        captive.scale = scale
    }

    fun release(targetId: UUID) {
        val captive = captives.remove(targetId) ?: return
        captive.attribute.getModifier(scaleKey)?.let(captive.attribute::removeModifier)
        if (captive.mob.isValid && !captive.mob.isDead) captive.mob.velocity = Vector()
    }

    fun releaseAll() = captives.keys.toList().forEach(::release)
}

internal class StaffBlackHoleCaptive(
    val mob: Mob,
    val attribute: AttributeInstance,
    private val width: Double,
    private val height: Double,
    private val depth: Double,
    val outward: Vector,
) {
    private val originalAttributeValue = attribute.value
    var scale = 1.0
    var releaseScale = 1.0

    /** Reserve the original body for the whole next step, so shrinking never opens a wall shortcut. */
    fun canMove(velocity: Vector): Boolean {
        val at = mob.location
        // A foreign scale change during capture belongs to its owner and changes our clearance too.
        val foreignScale = (attribute.value / scale / originalAttributeValue).coerceAtLeast(1.0)
        val box = BoundingBox(at.x - width * foreignScale * .5, at.y + .001, at.z - depth * foreignScale * .5,
            at.x + width * foreignScale * .5, at.y + height * foreignScale, at.z + depth * foreignScale * .5)
        box.union(box.clone().shift(velocity.clone().multiply(2.0)))
        val world = mob.world
        for (x in floor(box.minX / 16.0).toInt()..floor(box.maxX / 16.0).toInt())
            for (z in floor(box.minZ / 16.0).toInt()..floor(box.maxZ / 16.0).toInt())
                if (!world.isChunkLoaded(x, z)) return false
        return !world.hasCollisionsIn(box)
    }
}
