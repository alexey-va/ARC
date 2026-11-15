package ru.arc.chestpreview

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.scene.PaperSpatialSpace
import kotlin.math.abs
import kotlin.math.hypot

/** Chest-relative center on the side occupied by this viewer. Native billboarding owns the rotation. */
internal object ChestPreviewPlacement {
    const val MOTION_TICKS = 2
    private const val SURFACE_GAP = 0.04
    private const val MOTION_DEADBAND = 0.02
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
        val halfHeight = panel.height / 2.0
        val sideY = maxOf(above.y, box.minY + halfHeight + depth)
        val dx = eye.x - box.centerX
        val dz = eye.z - box.centerZ
        val radialDistance = hypot(dx, dz)
        if (radialDistance < 1e-8) return above.copy(y = maxOf(sideY, box.maxY + depth + SURFACE_GAP))
        val outwardX = dx / radialDistance
        val outwardZ = dz / radialDistance
        val dy = eye.y - box.centerY
        val distance = hypot(radialDistance, dy)
        val sinPitch = abs(dy) / distance
        val cosPitch = radialDistance / distance
        val face = (abs(outwardX) * box.widthX + abs(outwardZ) * box.widthZ) / 2.0
        // Clear a vertical stack at the angle from the chest to the viewer. Looking across
        // the same chest can rotate the billboard, but cannot translate its world center.
        val sideRadius = face + halfHeight * sinPitch + depth * cosPitch + SURFACE_GAP
        val footprint = maxOf(2 * abs(dx) / box.widthX, 2 * abs(dz) / box.widthZ)
        val overLid = eye.y > box.maxY
        val radius = sideRadius * if (overLid) smoothStep(footprint) else 1.0
        // Lift to clear the lid before drawing the panel inward across the footprint. Both
        // transitions meet the ordinary side orbit continuously, including a walk across its center.
        val lidY = maxOf(sideY, box.maxY + halfHeight * cosPitch + depth * sinPitch + SURFACE_GAP)
        val lift = if (overLid) smoothStep(2.0 - footprint) else 0.0
        return above.copy(
            x = box.centerX + outwardX * radius,
            y = sideY + (lidY - sideY) * lift,
            z = box.centerZ + outwardZ * radius,
        )
    }

    private fun smoothStep(value: Double): Double = value.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    fun follow(previous: InspectionHologramAnchor?, desired: InspectionHologramAnchor, elapsedTicks: Int): InspectionHologramAnchor {
        if (previous == null || elapsedTicks < MOTION_TICKS) return previous ?: desired
        val dx = desired.x - previous.x
        val dy = desired.y - previous.y
        val dz = desired.z - previous.z
        return if (dx * dx + dy * dy + dz * dz >= MOTION_DEADBAND * MOTION_DEADBAND) desired else previous
    }

    /** The target's access/ray checks run upstream; do not expose a panel through a wall. */
    fun visible(player: Player, anchor: InspectionHologramAnchor): Boolean {
        val eye = player.eyeLocation
        val center = Vector(anchor.x, anchor.y, anchor.z)
        return center.clone().subtract(eye.toVector()).dot(eye.direction) > 0.1 &&
            space.rayClear(player, eye.toVector(), center)
    }
}
