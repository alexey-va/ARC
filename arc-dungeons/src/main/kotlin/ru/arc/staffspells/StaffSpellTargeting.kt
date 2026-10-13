package ru.arc.staffspells

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal data class StaffBlockRayResult(val pathLoaded: Boolean, val hit: RayTraceResult?)

/** A native block ray guarded against invalid inputs and unloaded chunks; never requests a chunk. */
internal fun staffBlockHit(from: Location, direction: Vector, range: Double): StaffBlockRayResult {
    val world = from.world ?: return StaffBlockRayResult(pathLoaded = false, hit = null)
    if (!from.x.isFinite() || !from.y.isFinite() || !from.z.isFinite() ||
        abs(from.x) > MAX_COORDINATE || abs(from.z) > MAX_COORDINATE ||
        !range.isFinite() || range <= 0.0 || range > MAX_STAFF_BLOCK_RAY ||
        !direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite()) {
        return StaffBlockRayResult(pathLoaded = false, hit = null)
    }
    val magnitude = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
    if (!magnitude.isFinite() || magnitude <= MIN_RAY_DIRECTION) {
        return StaffBlockRayResult(pathLoaded = false, hit = null)
    }
    val unitDirection = direction.clone().multiply(1.0 / magnitude)
    if (!staffRayPathLoaded(world, from.x, from.z, unitDirection.x, unitDirection.z, range)) {
        return StaffBlockRayResult(pathLoaded = false, hit = null)
    }
    val hit = world.rayTraceBlocks(from, unitDirection, range, FluidCollisionMode.NEVER, false)
    return StaffBlockRayResult(pathLoaded = true, hit = hit)
}

/** Traverses chunk boundaries exactly, including corner crossings, without querying block data. */
private fun staffRayPathLoaded(world: World, x: Double, z: Double, dx: Double, dz: Double, range: Double): Boolean {
    var chunkX = floor(x / CHUNK_SIZE).toInt()
    var chunkZ = floor(z / CHUNK_SIZE).toInt()
    if (!world.isChunkLoaded(chunkX, chunkZ)) return false

    val stepX = dx.compareTo(0.0)
    val stepZ = dz.compareTo(0.0)
    val deltaX = if (stepX == 0) Double.POSITIVE_INFINITY else CHUNK_SIZE / abs(dx)
    val deltaZ = if (stepZ == 0) Double.POSITIVE_INFINITY else CHUNK_SIZE / abs(dz)
    var nextX = when {
        stepX > 0 -> ((chunkX + 1) * CHUNK_SIZE - x) / dx
        stepX < 0 -> (chunkX * CHUNK_SIZE - x) / dx
        else -> Double.POSITIVE_INFINITY
    }
    var nextZ = when {
        stepZ > 0 -> ((chunkZ + 1) * CHUNK_SIZE - z) / dz
        stepZ < 0 -> (chunkZ * CHUNK_SIZE - z) / dz
        else -> Double.POSITIVE_INFINITY
    }

    while (minOf(nextX, nextZ) <= range + CHUNK_CROSSING_EPSILON) {
        if (abs(nextX - nextZ) <= CHUNK_CROSSING_EPSILON) {
            if (!world.isChunkLoaded(chunkX + stepX, chunkZ) ||
                !world.isChunkLoaded(chunkX, chunkZ + stepZ) ||
                !world.isChunkLoaded(chunkX + stepX, chunkZ + stepZ)) return false
            chunkX += stepX
            chunkZ += stepZ
            nextX += deltaX
            nextZ += deltaZ
        } else if (nextX < nextZ) {
            chunkX += stepX
            if (!world.isChunkLoaded(chunkX, chunkZ)) return false
            nextX += deltaX
        } else {
            chunkZ += stepZ
            if (!world.isChunkLoaded(chunkX, chunkZ)) return false
            nextZ += deltaZ
        }
    }
    return true
}

private const val CHUNK_SIZE = 16.0
private const val CHUNK_CROSSING_EPSILON = 1.0e-9
private const val MIN_RAY_DIRECTION = 1.0e-9
private const val MAX_STAFF_BLOCK_RAY = 64.0
private const val MAX_COORDINATE = (Int.MAX_VALUE - 8).toDouble() * 16.0

/** Shared, bounded staff targeting; eligibility remains owned by the damage bridge. */
internal class StaffSpellTargeting(private val settings: StaffSpellSettings, private val damage: StaffSpellDamage) {
    /** Exact swept collision along the cast line, never soft acquisition. */
    fun lineTargets(player: Player, from: Location, to: Location, width: Double,
        cast: StaffSpellCast? = null): List<LivingEntity> {
        val hitWidth = width * (cast?.enchantments?.radiusMultiplier ?: 1.0)
        val direction = to.toVector().subtract(from.toVector())
        var length = direction.length()
        if (!length.isFinite() || length < 0.001 || from.world != to.world) return emptyList()
        direction.multiply(1.0 / length)
        length = from.distance(rayEnd(from, direction, length))
        if (length < .001) return emptyList()
        val midpoint = from.clone().add(direction.clone().multiply(length / 2))
        return from.world.getNearbyLivingEntities(midpoint, length / 2 + hitWidth + 2)
            .filter { damage.eligible(player, it, cast) }
            .mapNotNull { entity ->
                entity.boundingBox.expand(hitWidth).rayTrace(from.toVector(), direction, length)
                    ?.let { entity to it.hitPosition.distanceSquared(from.toVector()) }
            }.sortedBy { it.second }.map { it.first }.filter { visible(from, center(it)) }
    }

    fun aimPoint(player: Player) = rayEnd(player.eyeLocation, player.eyeLocation.direction, settings.range)

    fun groundBelow(from: Location, range: Double): Location? {
        val ray = staffBlockHit(from, Vector(0.0, -1.0, 0.0), range)
        if (!ray.pathLoaded) return null
        val hit = ray.hit ?: return null
        return hit.hitPosition.toLocation(from.world).add(0.0, 0.04, 0.0)
    }

    fun rayEnd(from: Location, direction: Vector, range: Double): Location {
        val ray = staffBlockHit(from, direction, range)
        if (!ray.pathLoaded) return from.clone()
        val unitDirection = direction.clone().normalize()
        // Keep bursts just outside the collision shape, so line-of-sight starts in air.
        val distance = ray.hit?.hitPosition?.distance(from.toVector())?.let { (it - 0.04).coerceAtLeast(0.0) } ?: range
        return from.clone().add(unitDirection.multiply(distance))
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

    fun nearby(player: Player, origin: Location, radius: Double, cast: StaffSpellCast? = null): List<LivingEntity> {
        val area = radius * (cast?.enchantments?.radiusMultiplier ?: 1.0)
        return origin.world.getNearbyLivingEntities(origin, area, area, area)
            .filter { center(it).distanceSquared(origin) <= area * area && damage.eligible(player, it, cast) }
    }

}
