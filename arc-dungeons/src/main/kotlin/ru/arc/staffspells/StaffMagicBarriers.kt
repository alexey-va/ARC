package ru.arc.staffspells

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs

/** Temporary magic cover, shared by all staff ray/visibility paths. Does not alter world collision. */
internal object StaffMagicBarriers {
    private data class Barrier(val id: UUID, val owner: UUID, val world: World,
        val base: Vector, val normal: Vector, val halfWidth: Double, val height: Double)

    private val barriers = linkedMapOf<UUID, Barrier>()

    fun register(owner: UUID, center: Location, normal: Vector, halfWidth: Double, height: Double): UUID? {
        val world = center.world ?: return null
        if (!center.x.isFinite() || !center.y.isFinite() || !center.z.isFinite() ||
            !halfWidth.isFinite() || halfWidth !in 0.2..4.0 || !height.isFinite() || height !in 0.2..4.0 ||
            !normal.x.isFinite() || !normal.z.isFinite() ||
            !world.isChunkLoaded(center.blockX shr 4, center.blockZ shr 4)) return null
        val horizontal = Vector(normal.x, 0.0, normal.z)
        if (horizontal.lengthSquared() < 1e-8) return null
        if (barriers.size >= 32 && barriers.values.none { it.owner == owner }) return null
        cancel(owner)
        val barrier = Barrier(UUID.randomUUID(), owner, world, center.toVector(), horizontal.normalize(), halfWidth, height)
        barriers[barrier.id] = barrier
        return barrier.id
    }

    fun remove(id: UUID?) { if (id != null) barriers.remove(id) }
    fun cancel(owner: UUID) { barriers.entries.removeIf { it.value.owner == owner } }
    fun clear() = barriers.clear()

    /** Both faces block equally. Vertical terrain probes are parallel and do not see a fake floor. */
    fun firstHit(from: Location, unitDirection: Vector, range: Double): RayTraceResult? {
        var closest = range + 1e-7
        var result: Vector? = null
        for (barrier in barriers.values) {
            if (barrier.world != from.world) continue
            val denominator = unitDirection.dot(barrier.normal)
            if (abs(denominator) < 1e-8) continue
            val distance = barrier.base.clone().subtract(from.toVector()).dot(barrier.normal) / denominator
            if (distance < 1e-5 || distance > closest) continue
            val point = from.toVector().add(unitDirection.clone().multiply(distance))
            val relative = point.clone().subtract(barrier.base)
            val horizontal = abs(relative.x * barrier.normal.z - relative.z * barrier.normal.x)
            if (horizontal > barrier.halfWidth + 1e-7 || relative.y < -1e-7 || relative.y > barrier.height + 1e-7) continue
            closest = distance
            result = point
        }
        return result?.let(::RayTraceResult)
    }
}
