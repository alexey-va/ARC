package ru.arc.staffspells

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/** Shared, bounded staff targeting; eligibility remains owned by the damage bridge. */
internal class StaffSpellTargeting(private val settings: StaffSpellSettings, private val damage: StaffSpellDamage) {
    /** Exact swept collision along the cast line, never soft acquisition. */
    fun lineTargets(player: Player, from: Location, to: Location, width: Double,
        cast: StaffSpellCast? = null): List<LivingEntity> {
        val direction = to.toVector().subtract(from.toVector())
        val length = direction.length()
        if (length < 0.001) return emptyList()
        direction.multiply(1.0 / length)
        val midpoint = from.clone().add(direction.clone().multiply(length / 2))
        return from.world.getNearbyLivingEntities(midpoint, length / 2 + width + 2)
            .filter { damage.eligible(player, it, cast) }
            .mapNotNull { entity ->
                entity.boundingBox.expand(width).rayTrace(from.toVector(), direction, length)
                    ?.let { entity to it.hitPosition.distanceSquared(from.toVector()) }
            }.sortedBy { it.second }.map { it.first }.filter { visible(from, center(it)) }
    }

    fun aimPoint(player: Player) = rayEnd(player.eyeLocation, player.eyeLocation.direction, settings.range)

    fun groundBelow(from: Location, range: Double): Location? {
        val hit = from.world.rayTraceBlocks(from, Vector(0.0, -1.0, 0.0), range, FluidCollisionMode.NEVER, true)
            ?: return null
        return hit.hitPosition.toLocation(from.world).add(0.0, 0.04, 0.0)
    }

    fun rayEnd(from: Location, direction: Vector, range: Double): Location {
        val hit = from.world.rayTraceBlocks(from, direction, range, FluidCollisionMode.NEVER, true)
        // Keep bursts just outside the wall, so line-of-sight starts in air.
        val distance = hit?.hitPosition?.distance(from.toVector())?.let { (it - 0.04).coerceAtLeast(0.0) } ?: range
        return from.clone().add(direction.clone().multiply(distance))
    }

    fun aimed(player: Player, range: Double, angle: Double, cast: StaffSpellCast? = null): List<LivingEntity> {
        val eye = player.eyeLocation
        val look = eye.direction
        return nearby(player, eye, range, cast).mapNotNull { target ->
            val offset = center(target).subtract(eye).toVector()
            // A direct hit on a tall/near mob's body must not fail because its centre is off-axis.
            val direct = target.boundingBox.expand(0.25).rayTrace(eye.toVector(), look, range) != null
            (if (direct) 1.0 else staffAimScore(offset.x, offset.y, offset.z, look.x, look.y, look.z, range, angle))
                ?.let { Triple(target, it, offset.lengthSquared()) }
        }.sortedWith(compareByDescending<Triple<LivingEntity, Double, Double>> { it.second }.thenBy { it.third })
            .take(32) // Bound expensive line-of-sight checks in dense mob farms.
            .filter { visible(eye, center(it.first)) }.map { it.first }
    }

    fun nearby(player: Player, origin: Location, radius: Double, cast: StaffSpellCast? = null) = origin.world
        .getNearbyLivingEntities(origin, radius, radius, radius)
        .filter { center(it).distanceSquared(origin) <= radius * radius && damage.eligible(player, it, cast) }

}
