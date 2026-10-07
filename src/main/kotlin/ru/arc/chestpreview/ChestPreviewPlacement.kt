package ru.arc.chestpreview

import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.api.InspectionHologramAnchor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Places the whole camera-facing grid, including its depth, in already loaded free space. */
internal object ChestPreviewPlacement {
    fun place(
        player: Player,
        frame: ChestPreviewFrame,
        itemCount: Int,
        scale: Float,
        previous: InspectionHologramAnchor?,
    ): InspectionHologramAnchor? {
        val eye = player.eyeLocation
        val panel = ChestPreviewIconGeometry.panelBounds(itemCount, scale)
        return choose(eye, frame.anchor, frame.containerBounds, panel, scale, previous) { anchor, bounds ->
            clear(player, bounds) && visible(player, eye, anchor, panel)
        }
    }

    internal fun choose(
        eye: Location,
        above: InspectionHologramAnchor,
        container: BoundingBox?,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        previous: InspectionHologramAnchor?,
        available: (InspectionHologramAnchor, BoundingBox) -> Boolean,
    ): InspectionHologramAnchor? {
        fun fits(anchor: InspectionHologramAnchor) = available(anchor, bounds(eye, anchor, panel, scale))
        // Retain a clear fallback while inspecting the same container: tiny camera changes
        // must not alternate the panel between the chest top and its front.
        if (previous != null && fits(previous)) return previous
        if (fits(above)) return above
        val box = container ?: return null
        val towardEye = eye.toVector().subtract(box.center).setY(0.0)
        if (towardEye.lengthSquared() < 0.0001) return null
        towardEye.normalize()
        val up = up(eye)
        val halfHeight = panel.height / 2.0
        val faceDistance = abs(towardEye.x) * box.widthX / 2.0 + abs(towardEye.z) * box.widthZ / 2.0
        // Try the viewer-facing side, then pull forward a little if adjacent blocks are tight.
        for (extra in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val center = box.center.add(towardEye.clone().multiply(faceDistance + 0.30 * scale + extra))
            val bottom = center.subtract(up.clone().multiply(halfHeight))
            var anchor = above.copy(x = bottom.x, y = bottom.y, z = bottom.z)
            val volume = bounds(eye, anchor, panel, scale)
            // Do not sink the bottom row into the floor in front of a ground-level chest.
            if (volume.minY < box.minY + 0.05) anchor = anchor.copy(y = anchor.y + box.minY + 0.05 - volume.minY)
            val panelCenter = Vector(anchor.x, anchor.y, anchor.z).add(up.clone().multiply(halfHeight))
            if (panelCenter.distanceSquared(eye.toVector()) < 0.36) continue
            // A candidate must stay between the container and viewer, never behind the camera.
            if (eye.toVector().subtract(panelCenter).dot(towardEye) <= 0.30) continue
            if (fits(anchor)) return anchor
        }
        return null
    }

    internal fun bounds(
        eye: Location,
        anchor: InspectionHologramAnchor,
        panel: ChestPreviewPanelBounds,
        scale: Float,
    ): BoundingBox {
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val right = Vector(-cos(yaw), 0.0, -sin(yaw))
        val up = up(eye)
        val normal = eye.direction.multiply(-1)
        val corners = buildList {
            for (x in listOf(-panel.width / 2.0, panel.width / 2.0)) {
                for (y in listOf(0.0, panel.height.toDouble())) {
                    for (z in listOf(-0.26 * scale, 0.26 * scale)) {
                        add(Vector(anchor.x, anchor.y, anchor.z).add(right.clone().multiply(x))
                            .add(up.clone().multiply(y)).add(normal.clone().multiply(z)))
                    }
                }
            }
        }
        return BoundingBox(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z },
            corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }).expand(0.02)
    }

    private fun up(eye: Location): Vector {
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val pitch = Math.toRadians(eye.pitch.toDouble())
        return Vector(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
    }

    private fun loaded(player: Player, bounds: BoundingBox): Boolean {
        for (x in (floor(bounds.minX).toInt() shr 4)..(floor(bounds.maxX).toInt() shr 4)) {
            for (z in (floor(bounds.minZ).toInt() shr 4)..(floor(bounds.maxZ).toInt() shr 4)) {
                if (!player.world.isChunkLoaded(x, z) || !player.isChunkSent(Chunk.getChunkKey(x, z))) return false
            }
        }
        return true
    }

    private fun clear(player: Player, bounds: BoundingBox): Boolean {
        val world = player.world
        if (bounds.minY < world.minHeight || bounds.maxY >= world.maxHeight || !loaded(player, bounds)) return false
        for (x in floor(bounds.minX).toInt()..floor(bounds.maxX).toInt()) {
            for (y in floor(bounds.minY).toInt()..floor(bounds.maxY).toInt()) {
                for (z in floor(bounds.minZ).toInt()..floor(bounds.maxZ).toInt()) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.isPassable && block.boundingBox.overlaps(bounds)) return false
                }
            }
        }
        return true
    }

    private fun visible(player: Player, eye: Location, anchor: InspectionHologramAnchor, panel: ChestPreviewPanelBounds): Boolean {
        val center = Vector(anchor.x, anchor.y, anchor.z).add(up(eye).multiply(panel.height / 2.0))
        val corridor = BoundingBox.of(eye.toVector(), center)
        if (!loaded(player, corridor)) return false
        val direction = center.subtract(eye.toVector())
        val distance = direction.length()
        return distance > 0.1 && player.world.rayTraceBlocks(eye, direction.normalize(), distance,
            FluidCollisionMode.NEVER, true) == null
    }
}
