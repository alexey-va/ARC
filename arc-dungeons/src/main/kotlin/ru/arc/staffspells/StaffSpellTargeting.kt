package ru.arc.staffspells

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal data class StaffBlockRayResult(val pathLoaded: Boolean, val hit: RayTraceResult?)

/** A native block ray guarded against invalid inputs and unloaded chunks; never requests a chunk. */
internal fun staffBlockHit(from: Location, direction: Vector, range: Double,
    includeMagicBarriers: Boolean = true, ignoreVegetation: Boolean = false): StaffBlockRayResult {
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
    // Attack rays ignore soft decoration, but retain Paper's exact solid-block shapes.
    // Floor placement, HUD and player movement keep the physical ray by default.
    val hit = if (ignoreVegetation) world.rayTraceBlocks(from, unitDirection, range,
        FluidCollisionMode.NEVER, true) { block -> block.blockData !is Leaves }
    else world.rayTraceBlocks(from, unitDirection, range, FluidCollisionMode.NEVER, false)
    val barrier = if (includeMagicBarriers) StaffMagicBarriers.firstHit(from, unitDirection, range) else null
    val nearest = if (barrier != null && (hit == null ||
        barrier.hitPosition.distanceSquared(from.toVector()) < hit.hitPosition.distanceSquared(from.toVector()))) barrier else hit
    return StaffBlockRayResult(pathLoaded = true, hit = nearest)
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

internal fun visible(from: Location, to: Location): Boolean {
    if (from.world != to.world) return false
    val offset = to.toVector().subtract(from.toVector())
    val distance = offset.length()
    if (distance < 0.01) return true
    val trace = staffBlockHit(from, offset.multiply(1.0 / distance), distance, ignoreVegetation = true)
    return trace.pathLoaded && trace.hit == null
}

/** An exposed head/shoulder counts, but a fully covered body never does. */
internal fun visible(from: Location, target: LivingEntity): Boolean =
    staffVisibleTargetPoint(from, target) != null

/** One real point of the body must be reachable from both the projectile and its observer. */
internal fun staffVisibleTargetPoint(from: Location, target: LivingEntity, observer: Location? = null): Location? {
    val middle = center(target)
    if (from.world != middle.world || observer != null && observer.world != middle.world) return null
    fun clear(at: Location) = visible(from, at) && (observer == null || visible(observer, at))
    if (clear(middle)) return middle
    val box = target.boundingBox
    val x = (box.minX + box.maxX) * .5
    val z = (box.minZ + box.maxZ) * .5
    val shoulderY = box.minY + box.height * .65
    // Bounded samples stay inside the hitbox; this is not an enlarged collision volume.
    return sequenceOf(
        Location(middle.world, x, box.minY + box.height * .88, z),
        Location(middle.world, x - box.widthX * .4, shoulderY, z),
        Location(middle.world, x + box.widthX * .4, shoulderY, z),
        Location(middle.world, x, shoulderY, z - box.widthZ * .4),
        Location(middle.world, x, shoulderY, z + box.widthZ * .4),
        Location(middle.world, x, box.minY + box.height * .22, z),
    ).firstOrNull(::clear)
}

/** Shared, bounded staff targeting; eligibility remains owned by the damage bridge. */
internal class StaffSpellTargeting(private val settings: StaffSpellSettings, private val damage: StaffSpellDamage) {
    /** A launch nudge only: keeps exact hits, never locks or changes collision/damage eligibility. */
    fun assistedDirection(player: Player, spell: StaffSpell, cast: StaffSpellCast?,
        direction: Vector = player.eyeLocation.direction, from: Location = player.eyeLocation,
        range: Double = settings.range): Vector {
        val look = direction.clone()
        if (!look.lengthSquared().isFinite() || look.lengthSquared() < 1e-9) return look
        look.normalize()
        if (!homingEnabled(spell)) return look
        val eye = player.eyeLocation
        // Do not pull an already accurate ray off a nearer, directly aimed target.
        if (lineTargets(player, eye, rayEnd(eye, look, range), .05, cast).isNotEmpty()) return look
        val target = assistTarget(player, cast, look, from, range) ?: return look
        val targetPoint = staffVisibleTargetPoint(from, target, eye) ?: return look
        val offset = targetPoint.toVector().subtract(eye.toVector())
        if (abs(look.y) < 1e-9) offset.y = 0.0
        return staffNudgeDirection(look, offset, settings.aimAssist.strength)
    }

    fun homingEnabled(spell: StaffSpell) = settings.aimAssist.let {
        it.enabled && it.strength > 0.0 && spell.id !in it.excludedSpells
    }

    /** Acquire once at launch. Mobs retain their existing launch correction and flight behavior. */
    fun homingTarget(player: Player, spell: StaffSpell, cast: StaffSpellCast?,
        from: Location = player.eyeLocation, range: Double = settings.range,
        excluded: Set<java.util.UUID> = emptySet()): Player? {
        if (!homingEnabled(spell)) return null
        val eye = player.eyeLocation
        val look = eye.direction
        val direct = lineCandidates(player, eye, rayEnd(eye, look, range), .05, cast)
            .firstOrNull { it.uniqueId !in excluded && damage.eligible(player, it, cast) }
        // An aimed mob takes priority over a neighboring player too.
        if (direct != null) return (direct as? Player)?.takeIf {
            staffVisibleTargetPoint(from, it, eye) != null
        }
        return assistTarget(player, cast, look, from, range) { it.uniqueId !in excluded } as? Player
    }

    /** A lock never grants a hit or a route around cover, and cannot turn back or extend travel. */
    fun homingPoint(player: Player, target: Player, cast: StaffSpellCast, from: Location,
        direction: Vector, launchDirection: Vector, remainingDistance: Double): Location? {
        if (target.world != from.world || !damage.eligible(player, target, cast)) return null
        val point = staffVisibleTargetPoint(from, target, player.eyeLocation) ?: return null
        val offset = point.toVector().subtract(from.toVector())
        if (offset.lengthSquared() > remainingDistance * remainingDistance ||
            offset.dot(direction) <= 0.0 || offset.dot(launchDirection) <= 0.0) return null
        return point
    }

    private fun assistTarget(player: Player, cast: StaffSpellCast?, look: Vector, from: Location,
        range: Double, include: (LivingEntity) -> Boolean = { true }): LivingEntity? {
        val assist = settings.aimAssist
        val horizontal = abs(look.y) < 1e-9
        val eye = player.eyeLocation
        val candidates = nearby(player, eye, range, cast).asSequence().filter(include).mapNotNull { entity ->
            val at = center(entity)
            val offset = at.toVector().subtract(eye.toVector())
            if (horizontal) offset.y = 0.0
            val along = offset.dot(look)
            if (along <= .1 || along > range) return@mapNotNull null
            val miss = offset.clone().subtract(look.clone().multiply(along)).length()
            if (miss > assist.maxMiss || miss > along * kotlin.math.tan(Math.toRadians(assist.halfAngleDegrees)))
                return@mapNotNull null
            entity to miss / along
        }.sortedBy { it.second }.take(32).toList()
        return candidates.firstOrNull { staffVisibleTargetPoint(from, it.first, eye) != null }?.first
    }

    /** Exact swept collision along the cast line, never soft acquisition. */
    fun lineTargets(player: Player, from: Location, to: Location, width: Double,
        cast: StaffSpellCast? = null): List<LivingEntity> = lineCandidates(player, from, to, width, cast)
        .filter { target ->
            if (damage.eligible(player, target, cast)) true
            else {
                if (cast != null) damage.reportDeniedTarget(player, target, cast)
                false
            }
        }

    /** A deliberate cast at a protected player gets a reason even when ordinary selection excludes them. */
    fun reportDeniedAim(player: Player, cast: StaffSpellCast): Boolean {
        if (cast.duelAuthorization != null) return false
        val eye = player.eyeLocation
        val end = rayEnd(eye, eye.direction, settings.range)
        val target = lineCandidates(player, eye, end, .05, cast).firstOrNull() ?: return false
        return damage.reportDeniedTarget(player, target, cast)
    }

    private fun lineCandidates(player: Player, from: Location, to: Location, width: Double,
        cast: StaffSpellCast?): List<LivingEntity> {
        val hitWidth = width * (cast?.enchantments?.radiusMultiplier ?: 1.0)
        val direction = to.toVector().subtract(from.toVector())
        var length = direction.length()
        if (!length.isFinite() || length < 0.001 || from.world != to.world) return emptyList()
        direction.multiply(1.0 / length)
        length = from.distance(rayEnd(from, direction, length))
        if (length < .001) return emptyList()
        val midpoint = from.clone().add(direction.clone().multiply(length / 2))
        return from.world.getNearbyLivingEntities(midpoint, length / 2 + hitWidth + 2)
            .filter { it.uniqueId != player.uniqueId }
            .mapNotNull { entity ->
                entity.boundingBox.expand(hitWidth).rayTrace(from.toVector(), direction, length)
                    ?.let { entity to it.hitPosition.distanceSquared(from.toVector()) }
            }.sortedBy { it.second }.map { it.first }.filter { visible(from, it) }
    }

    fun aimPoint(player: Player) = rayEnd(player.eyeLocation, player.eyeLocation.direction, settings.range)

    fun groundBelow(from: Location, range: Double): Location? {
        val ray = staffBlockHit(from, Vector(0.0, -1.0, 0.0), range)
        if (!ray.pathLoaded) return null
        val hit = ray.hit ?: return null
        return hit.hitPosition.toLocation(from.world).add(0.0, 0.04, 0.0)
    }

    fun rayEnd(from: Location, direction: Vector, range: Double): Location {
        val ray = staffBlockHit(from, direction, range, ignoreVegetation = true)
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
            .filter { visible(eye, it.first) }.map { it.first }
    }

    fun nearby(player: Player, origin: Location, radius: Double, cast: StaffSpellCast? = null): List<LivingEntity> {
        val area = radius * (cast?.enchantments?.radiusMultiplier ?: 1.0)
        return origin.world.getNearbyLivingEntities(origin, area, area, area)
            .filter { center(it).distanceSquared(origin) <= area * area && damage.eligible(player, it, cast) }
    }

}

/** Fractional correction preserves a deliberate miss; no perfect-target snap. */
internal fun staffNudgeDirection(direction: Vector, offset: Vector, strength: Double): Vector {
    val look = direction.clone().normalize()
    if (!offset.lengthSquared().isFinite() || offset.lengthSquared() < 1e-9) return look
    val amount = strength.takeIf(Double::isFinite)?.coerceIn(0.0, .8) ?: 0.0
    return look.multiply(1.0 - amount).add(offset.clone().normalize().multiply(amount)).normalize()
}
