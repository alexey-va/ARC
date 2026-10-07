package ru.arc.chestpreview

import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Intersectiond
import org.joml.Vector3d
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
    ): InspectionHologramAnchor? {
        val eye = player.eyeLocation
        val panel = ChestPreviewIconGeometry.panelBounds(itemCount, scale)
        return choose(eye, frame.anchor, frame.containerBounds, panel, scale) { anchor, volume ->
            clear(player, volume) && visible(player, eye, anchor, panel)
        }
    }

    internal fun choose(
        eye: Location,
        above: InspectionHologramAnchor,
        container: BoundingBox?,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        available: (InspectionHologramAnchor, ChestPreviewVolume) -> Boolean,
    ): InspectionHologramAnchor? {
        fun fits(anchor: InspectionHologramAnchor) = available(anchor, volume(eye, anchor, panel, scale))
        val box = container ?: return above.takeIf(::fits)
        val up = up(eye)
        val halfHeight = panel.height / 2.0
        fun anchorAt(center: Vector): InspectionHologramAnchor {
            val bottom = center.clone().subtract(up.clone().multiply(halfHeight))
            return above.copy(x = bottom.x, y = bottom.y, z = bottom.z)
        }
        // CENTER billboards pitch with the camera. Position their center over the lid,
        // then lift by the entire rotated volume, including icon/backdrop depth.
        // Keeping the old bottom pivot on the lid made top-down views intersect it.
        val centered = anchorAt(Vector(above.x, above.y, above.z))
        val top = centered.copy(y = centered.y + above.y - bounds(eye, centered, panel, scale).minY)
        val towardEye = eye.toVector().subtract(box.center).setY(0.0)
        if (towardEye.lengthSquared() < 0.0001) {
            val yaw = Math.toRadians(eye.yaw.toDouble())
            towardEye.setX(sin(yaw)).setZ(-cos(yaw))
        }
        towardEye.normalize()
        // A diagonal panel may graze leaves beside an otherwise clear lid. Try small
        // adjustments above the lid before ever dropping to the container's side.
        for (rise in listOf(0.0, 0.15, 0.30)) for (shift in listOf(0.0, 0.15, 0.30, 0.45)) {
            val candidate = top.copy(x = top.x + towardEye.x * shift, y = top.y + rise, z = top.z + towardEye.z * shift)
            val center = Vector(candidate.x, candidate.y, candidate.z).add(up.clone().multiply(halfHeight))
            if (center.clone().subtract(eye.toVector()).dot(eye.direction) <= 0.10) continue
            if (fits(candidate)) return candidate
        }
        // Re-evaluate above first every time. A previous clear side position must not
        // remain stuck there after the viewer moves above the container.
        val faceDistance = abs(towardEye.x) * box.widthX / 2.0 + abs(towardEye.z) * box.widthZ / 2.0
        // Try the viewer-facing side, then pull forward a little if adjacent blocks are tight.
        for (extra in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val center = box.center.add(towardEye.clone().multiply(faceDistance + 0.30 * scale + extra))
            var anchor = anchorAt(center)
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
    ): BoundingBox = volume(eye, anchor, panel, scale).bounds

    private fun volume(
        eye: Location,
        anchor: InspectionHologramAnchor,
        panel: ChestPreviewPanelBounds,
        scale: Float,
    ): ChestPreviewVolume {
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val up = up(eye)
        val normal = eye.direction.multiply(-1)
        return ChestPreviewVolume(
            Vector3d(anchor.x + up.x * panel.height / 2, anchor.y + up.y * panel.height / 2, anchor.z + up.z * panel.height / 2),
            Vector3d(-cos(yaw), 0.0, -sin(yaw)), Vector3d(up.x, up.y, up.z), Vector3d(normal.x, normal.y, normal.z),
            Vector3d(panel.width / 2.0 + 0.02, panel.height / 2.0 + 0.02, 0.26 * scale + 0.02),
        )
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

    private fun clear(player: Player, volume: ChestPreviewVolume): Boolean {
        val bounds = volume.bounds
        val world = player.world
        if (bounds.minY < world.minHeight || bounds.maxY >= world.maxHeight || !loaded(player, bounds)) return false
        for (x in floor(bounds.minX).toInt()..floor(bounds.maxX).toInt()) {
            for (y in floor(bounds.minY).toInt()..floor(bounds.maxY).toInt()) {
                for (z in floor(bounds.minZ).toInt()..floor(bounds.maxZ).toInt()) {
                    val block = world.getBlockAt(x, y, z)
                    if (!block.isPassable && volume.overlaps(block.boundingBox)) return false
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

/** The AABB is only the block-scan envelope; collisions use the actual oriented panel. */
internal class ChestPreviewVolume(
    private val center: Vector3d,
    private val right: Vector3d,
    private val up: Vector3d,
    private val normal: Vector3d,
    private val halfSize: Vector3d,
) {
    val bounds: BoundingBox = run {
        val x = abs(right.x) * halfSize.x + abs(up.x) * halfSize.y + abs(normal.x) * halfSize.z
        val y = abs(right.y) * halfSize.x + abs(up.y) * halfSize.y + abs(normal.y) * halfSize.z
        val z = abs(right.z) * halfSize.x + abs(up.z) * halfSize.y + abs(normal.z) * halfSize.z
        BoundingBox(center.x - x, center.y - y, center.z - z, center.x + x, center.y + y, center.z + z)
    }

    fun overlaps(block: BoundingBox): Boolean = bounds.overlaps(block) && Intersectiond.testObOb(
        center, right, up, normal, halfSize,
        Vector3d(block.centerX, block.centerY, block.centerZ), X, Y, Z,
        Vector3d(block.widthX / 2, block.height / 2, block.widthZ / 2),
    )

    private companion object {
        val X = Vector3d(1.0, 0.0, 0.0)
        val Y = Vector3d(0.0, 1.0, 0.0)
        val Z = Vector3d(0.0, 0.0, 1.0)
    }
}
