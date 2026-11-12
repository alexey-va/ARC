package ru.arc.chestpreview

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.scene.PaperSpatialSpace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Acquire one world-fixed center; camera billboarding never chooses another position. */
internal object ChestPreviewPlacement {
    private const val SURFACE_GAP = 0.04
    private val space = PaperSpatialSpace()

    fun place(
        eye: Location,
        frame: ChestPreviewFrame,
        scale: Float,
        options: ChestPreviewSettings,
    ): InspectionHologramAnchor {
        // Reserve the configured envelope so adding rows cannot move the panel or bury them.
        val panel = ChestPreviewIconGeometry.panelBounds(options.maxItems, scale, options)
        return choose(eye, frame.anchor, frame.containerBounds, panel, scale, options)
    }

    internal fun choose(
        eye: Location,
        above: InspectionHologramAnchor,
        container: BoundingBox?,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        options: ChestPreviewSettings = ChestPreviewSettings(),
    ): InspectionHologramAnchor {
        val box = container ?: return above
        val depth = ChestPreviewIconGeometry.depth(scale, options) + SURFACE_GAP
        val floorY = box.minY + panel.height / 2.0 + depth
        val normal = eye.direction.multiply(-1)
        val horizontal = sqrt(normal.x * normal.x + normal.z * normal.z)
        if (normal.y > 0 && eye.x in box.minX..box.maxX && eye.z in box.minZ..box.maxZ) {
            // Only an overhead viewer starts above the lid. A steep ray can still hit
            // a lower container's side, with another container directly above it.
            val aboveY = box.maxY + panel.height / 2.0 * horizontal + depth * normal.y + SURFACE_GAP
            return above.copy(y = maxOf(above.y, floorY, aboveY))
        }
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val outward = Vector(sin(yaw), 0.0, -cos(yaw))
        val face = (abs(outward.x) * box.widthX + abs(outward.z) * box.widthZ) / 2.0
        // Clear the entire vertical face, not just this box: a chest stacked above it
        // must not swallow the center or the upper rows of a camera-pitched grid.
        val inward = panel.height / 2.0 * abs(normal.y) + depth * horizontal
        return above.copy(
            x = box.centerX + outward.x * (face + inward + SURFACE_GAP),
            y = maxOf(above.y, floorY),
            z = box.centerZ + outward.z * (face + inward + SURFACE_GAP),
        )
    }

    /** The target's access/ray checks run upstream; do not expose a panel through a wall. */
    fun visible(player: Player, anchor: InspectionHologramAnchor): Boolean {
        val eye = player.eyeLocation
        val center = Vector(anchor.x, anchor.y, anchor.z)
        return center.clone().subtract(eye.toVector()).dot(eye.direction) > 0.1 &&
            space.rayClear(player, eye.toVector(), center)
    }
}
