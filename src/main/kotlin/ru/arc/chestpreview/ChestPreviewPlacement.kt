package ru.arc.chestpreview

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Vector3d
import ru.arc.paper.api.InspectionHologramAnchor
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.scene.PaperSpatialSpace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Places the whole camera-facing grid, including its depth, in already loaded free space. */
internal object ChestPreviewPlacement {
    private const val MOTION_CLEARANCE = 0.08
    private val space = PaperSpatialSpace()

    fun place(
        player: Player,
        frame: ChestPreviewFrame,
        itemCount: Int,
        scale: Float,
        options: ChestPreviewSettings = ChestPreviewSettings(),
        previous: InspectionHologramAnchor? = null,
    ): InspectionHologramAnchor? {
        val eye = player.eyeLocation
        val panel = ChestPreviewIconGeometry.panelBounds(itemCount, scale, options)
        return choose(eye, frame.anchor, frame.containerBounds, panel, scale, options, previous) { anchor, volume ->
            clear(player, volume) && visible(player, eye, anchor, panel)
        }
    }

    internal fun choose(
        eye: Location,
        above: InspectionHologramAnchor,
        container: BoundingBox?,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        options: ChestPreviewSettings = ChestPreviewSettings(),
        previous: InspectionHologramAnchor? = null,
        available: (InspectionHologramAnchor, DisplayOrientedVolume) -> Boolean,
    ): InspectionHologramAnchor? {
        fun fits(anchor: InspectionHologramAnchor, clearance: Double = 0.0) =
            available(anchor, volume(eye, anchor, panel, scale, options, clearance))
        fun stable(candidate: InspectionHologramAnchor, clearance: Double = 0.0): InspectionHologramAnchor {
            // Hold only tiny safe changes; never latch a distant side position over a clear lid.
            if (previous != null && previous.worldId == candidate.worldId &&
                distanceSquared(previous, candidate) <= options.stabilityThreshold * options.stabilityThreshold && fits(previous, clearance)) return previous
            return candidate
        }
        fun firstFit(candidates: List<InspectionHologramAnchor>, clearance: Double = 0.0): InspectionHologramAnchor? {
            var blocked: InspectionHologramAnchor? = null
            for (candidate in candidates) {
                if (!fits(candidate, clearance)) { blocked = candidate; continue }
                var free = candidate
                var low = blocked
                if (low != null) repeat(6) {
                    val midpoint = between(requireNotNull(low), free, 0.5)
                    if (fits(midpoint, clearance)) free = midpoint else low = midpoint
                }
                return stable(free, clearance)
            }
            return null
        }
        val box = container ?: return above.takeIf { fits(it) }?.let { stable(it) }
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
        val top = centered.copy(y = centered.y + above.y - bounds(eye, centered, panel, scale, options).minY)
        val towardEye = eye.toVector().subtract(box.center).setY(0.0)
        if (towardEye.lengthSquared() < 0.0001) {
            val yaw = Math.toRadians(eye.yaw.toDouble())
            towardEye.setX(sin(yaw)).setZ(-cos(yaw))
        }
        towardEye.normalize()
        // A diagonal panel may graze leaves beside an otherwise clear lid. Try small
        // adjustments above the lid before ever dropping to the container's side.
        // A free lid is not a useful preview surface when the viewer is below it.
        if (eye.y > box.maxY) for (rise in listOf(0.0, 0.15, 0.30)) {
            val candidates = mutableListOf<InspectionHologramAnchor>()
            for (shift in listOf(0.0, 0.15, 0.30, 0.45)) {
                val candidate = top.copy(x = top.x + towardEye.x * shift, y = top.y + rise, z = top.z + towardEye.z * shift)
                val center = Vector(candidate.x, candidate.y, candidate.z).add(up.clone().multiply(halfHeight))
                if (center.clone().subtract(eye.toVector()).dot(eye.direction) <= 0.10) continue
                candidates += candidate
            }
            firstFit(candidates)?.let { return it }
        }
        // Re-evaluate above first every time. A previous clear side position must not
        // remain stuck there after the viewer moves above the container.
        val faceDistance = abs(towardEye.x) * box.widthX / 2.0 + abs(towardEye.z) * box.widthZ / 2.0
        // Try the viewer-facing side, then pull forward a little if adjacent blocks are tight.
        val sideCandidates = mutableListOf<InspectionHologramAnchor>()
        for (extra in listOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.25)) {
            val center = box.center.add(towardEye.clone().multiply(faceDistance + 0.30 * scale + extra))
            var anchor = anchorAt(center)
            val volume = volume(eye, anchor, panel, scale, options, MOTION_CLEARANCE).bounds
            // Do not sink the bottom row into the floor in front of a ground-level chest.
            if (volume.minY < box.minY + 0.05) anchor = anchor.copy(y = anchor.y + box.minY + 0.05 - volume.minY)
            val panelCenter = Vector(anchor.x, anchor.y, anchor.z).add(up.clone().multiply(halfHeight))
            if (panelCenter.distanceSquared(eye.toVector()) < 0.36) continue
            // A candidate must stay between the container and viewer, never behind the camera.
            if (eye.toVector().subtract(panelCenter).dot(towardEye) <= 0.30) continue
            sideCandidates += anchor
        }
        // Leave room for the camera-facing grid to rotate while the client interpolates.
        // Searching up to exact contact makes the previous pose collide on the next tick.
        return firstFit(sideCandidates, MOTION_CLEARANCE) ?: firstFit(sideCandidates)
    }

    internal fun bounds(
        eye: Location,
        anchor: InspectionHologramAnchor,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        options: ChestPreviewSettings = ChestPreviewSettings(),
    ): BoundingBox = volume(eye, anchor, panel, scale, options).bounds

    private fun volume(
        eye: Location,
        anchor: InspectionHologramAnchor,
        panel: ChestPreviewPanelBounds,
        scale: Float,
        options: ChestPreviewSettings,
        clearance: Double = 0.0,
    ): DisplayOrientedVolume {
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val up = up(eye)
        val normal = eye.direction.multiply(-1)
        return DisplayOrientedVolume(
            Vector3d(anchor.x + up.x * panel.height / 2, anchor.y + up.y * panel.height / 2, anchor.z + up.z * panel.height / 2),
            Vector3d(-cos(yaw), 0.0, -sin(yaw)), Vector3d(up.x, up.y, up.z), Vector3d(normal.x, normal.y, normal.z),
            Vector3d(panel.width / 2.0 + 0.02 + clearance, panel.height / 2.0 + 0.02 + clearance,
                ChestPreviewIconGeometry.depth(scale, options).toDouble() + 0.04 + clearance),
        )
    }

    /** Enclose every translated pose, including obstacles between native interpolation ticks. */
    fun clearPath(player: Player, from: InspectionHologramAnchor, to: InspectionHologramAnchor,
                  itemCount: Int, scale: Float, options: ChestPreviewSettings): Boolean {
        if (from.worldId != to.worldId || distanceSquared(from, to) > 1.0) return false
        val eye = player.eyeLocation
        val panel = ChestPreviewIconGeometry.panelBounds(itemCount, scale, options)
        val swept = volume(eye, from, panel, scale, options)
            .swept(Vector3d(to.x - from.x, to.y - from.y, to.z - from.z))
        return clear(player, swept) && listOf(from, between(from, to, 0.5), to).all {
            visible(player, eye, it, panel)
        }
    }

    private fun between(from: InspectionHologramAnchor, to: InspectionHologramAnchor, fraction: Double) =
        to.copy(x = from.x + (to.x - from.x) * fraction,
            y = from.y + (to.y - from.y) * fraction, z = from.z + (to.z - from.z) * fraction)

    private fun distanceSquared(a: InspectionHologramAnchor, b: InspectionHologramAnchor): Double =
        (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)

    private fun up(eye: Location): Vector {
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val pitch = Math.toRadians(eye.pitch.toDouble())
        return Vector(-sin(yaw) * sin(pitch), cos(pitch), cos(yaw) * sin(pitch))
    }

    private fun clear(player: Player, volume: DisplayOrientedVolume): Boolean =
        space.clear(player.world, volume, player)

    private fun visible(player: Player, eye: Location, anchor: InspectionHologramAnchor, panel: ChestPreviewPanelBounds): Boolean {
        val center = Vector(anchor.x, anchor.y, anchor.z).add(up(eye).multiply(panel.height / 2.0))
        return eye.toVector().distance(center) > 0.1 && space.rayClear(player, eye.toVector(), center)
    }
}
