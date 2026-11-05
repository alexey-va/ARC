package ru.arc.graves

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.roundToInt

/** Captured once at entry. Submitting a form never follows a new gaze. */
internal data class DecorativeGraveTarget(val worldId: UUID, val world: String, val x: Int, val y: Int, val z: Int, val yaw: Float) {
    fun reachable(player: Player): Boolean {
        val current = player.world
        return player.isOnline && !player.isDead && current.uid == worldId && current.name == world &&
            y > current.minHeight && y < current.maxHeight && current.isChunkLoaded(x shr 4, z shr 4) &&
            player.eyeLocation.distanceSquared(Location(current, x + .5, y.toDouble(), z + .5)) <= MAX_DISTANCE * MAX_DISTANCE
    }

    fun placeable(player: Player): Boolean = reachable(player) &&
        player.world.getBlockAt(x, y - 1, z).type.isSolid && player.world.getBlockAt(x, y, z).isPassable

    companion object {
        const val MAX_DISTANCE = 6.0

        fun capture(player: Player): DecorativeGraveTarget? {
            if (!player.isOnline || player.isDead || !loadedRay(player)) return null
            val block = player.rayTraceBlocks(MAX_DISTANCE, FluidCollisionMode.NEVER)?.hitBlock ?: return null
            val toward = player.eyeLocation.toVector().subtract(Vector(block.x + .5, block.y + 1.0, block.z + .5))
            // Models face local -Z; use cardinal yaw so map tiles and display geometry agree.
            val degrees = Math.toDegrees(kotlin.math.atan2(-toward.x, -toward.z))
            val yaw = (Math.floorMod((degrees / 90).roundToInt(), 4) * 90).toFloat()
            return DecorativeGraveTarget(block.world.uid, block.world.name, block.x, block.y + 1, block.z, yaw)
                .takeIf { it.placeable(player) }
        }

        fun of(record: DecorativeGraveRecord) = DecorativeGraveTarget(record.worldId, record.world, record.x, record.y, record.z, record.yaw)

        /** Paper ray tracing can load chunks, including before reaching its eventual hit. */
        fun loadedRay(player: Player): Boolean {
            val eye = player.eyeLocation
            val end = eye.clone().add(eye.direction.multiply(MAX_DISTANCE))
            return ((minOf(eye.blockX, end.blockX) shr 4)..(maxOf(eye.blockX, end.blockX) shr 4)).all { x ->
                ((minOf(eye.blockZ, end.blockZ) shr 4)..(maxOf(eye.blockZ, end.blockZ) shr 4)).all { z -> player.world.isChunkLoaded(x, z) }
            }
        }

        /** Aim at visible geometry, not the support block. Opaque world blocks stop the selection ray. */
        fun select(player: Player, records: List<DecorativeGraveRecord>, appearance: (DecorativeGraveRecord) -> GraveAppearance): DecorativeGraveRecord? {
            if (!player.isOnline || player.isDead || !loadedRay(player)) return null
            val eye = player.eyeLocation
            val origin = eye.toVector()
            val direction = eye.direction
            val obstacle = player.rayTraceBlocks(MAX_DISTANCE, FluidCollisionMode.NEVER)?.hitPosition
                ?.distance(origin) ?: MAX_DISTANCE
            return records.asSequence().filter { it.createdBy == player.uniqueId && of(it).reachable(player) }
                .mapNotNull { record ->
                    val hit = bounds(record, appearance(record).model).rayTrace(origin, direction, minOf(MAX_DISTANCE, obstacle + .05))
                    hit?.let { record to it.hitPosition.distanceSquared(origin) }
                }.minByOrNull { it.second }?.first
        }

        /** The union of the actual rotated cuboids includes wide feet, tilted tops and the inscription backing. */
        fun bounds(record: DecorativeGraveRecord, model: GraveModel): BoundingBox {
            val yaw = Quaternionf().rotationY(Math.toRadians(record.yaw.toDouble()).toFloat())
            val points = model.parts.flatMap { part ->
                val center = Vector3f((part.x + part.width / 2).toFloat(), (part.y + part.height / 2).toFloat(), (part.z + part.depth / 2).toFloat())
                val tilt = Quaternionf().rotationXYZ(Math.toRadians(part.rotationX.toDouble()).toFloat(),
                    Math.toRadians(part.rotationY.toDouble()).toFloat(), Math.toRadians(part.rotationZ.toDouble()).toFloat())
                listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).flatMap { y -> listOf(-1f, 1f).map { z ->
                    yaw.transform(tilt.transform(Vector3f(x * part.width / 2, y * part.height / 2, z * part.depth / 2)).add(center))
                } } }
            }
            return BoundingBox(record.x + .5 + points.minOf { it.x }, record.y + points.minOf { it.y }.toDouble(), record.z + .5 + points.minOf { it.z },
                record.x + .5 + points.maxOf { it.x }, record.y + points.maxOf { it.y }.toDouble(), record.z + .5 + points.maxOf { it.z }).expand(.04)
        }
    }
}
