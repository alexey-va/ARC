package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import org.joml.Vector3d
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID
import kotlin.math.abs

class ChestPreviewPlacementTest : StringSpec({
    val eye = Location(null, 0.5, 1.6, -3.0)
    val above = InspectionHologramAnchor(UUID.randomUUID(), 0.5, 1.15, 0.5)
    val chest = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    val panel = ChestPreviewIconGeometry.panelBounds(6, 0.9f)

    "clear chest keeps its top anchor but a stacked chest moves the entire grid in front" {
        fun choose(blocks: List<BoundingBox>) =
            ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, bounds ->
                blocks.none(bounds::overlaps)
            }
        val top = requireNotNull(choose(listOf(chest)))
        (abs(ChestPreviewPlacement.bounds(eye, top, panel, 0.9f).minY - above.y) < 0.00001) shouldBe true
        val stacked = listOf(chest, BoundingBox(0.0, 1.0, 0.0, 1.0, 2.0, 1.0))
        val front = requireNotNull(choose(stacked))
        (front.z < 0.0) shouldBe true
        val volume = ChestPreviewPlacement.bounds(eye, front, panel, 0.9f)
        stacked.none { it.overlaps(volume) } shouldBe true
        (volume.minY >= 0.049) shouldBe true
        // A clear top wins again immediately; the previous side position is never retained.
        choose(listOf(chest)) shouldBe top
    }

    "top-down and diagonal views keep a centered panel above the lid beside tall leaves" {
        val smallPanel = ChestPreviewIconGeometry.panelBounds(2, 0.9f)
        val leaves = BoundingBox(-1.0, 0.0, 0.0, 0.0, 3.0, 1.0)
        for (pitch in listOf(45f, 70f, 90f)) {
            val lookingDown = Location(null, 0.5, 3.0, if (pitch == 90f) 0.5 else -0.5, 0f, pitch)
            val placed = requireNotNull(ChestPreviewPlacement.choose(lookingDown, above, chest, smallPanel, 0.9f) { _, bounds ->
                !bounds.overlaps(chest) && !bounds.overlaps(leaves)
            })
            val volume = ChestPreviewPlacement.bounds(lookingDown, placed, smallPanel, 0.9f)
            (abs(volume.centerX - chest.centerX) < 0.00001) shouldBe true
            (abs(volume.centerZ - chest.centerZ) < 0.00001) shouldBe true
            (abs(volume.minY - above.y) < 0.00001) shouldBe true
        }
    }

    "a below-lid viewer looking up gets the front panel even when the lid position is clear" {
        val lookingUp = Location(null, 0.5, 0.8, -2.5, 0f, -10f)
        val placed = requireNotNull(ChestPreviewPlacement.choose(lookingUp, above, chest, panel, 0.9f) { _, volume ->
            !volume.overlaps(chest)
        })
        val volume = ChestPreviewPlacement.bounds(lookingUp, placed, panel, 0.9f)

        // The top candidate is geometrically free here; a low viewer still needs the front-facing option.
        (volume.centerZ < chest.minZ) shouldBe true
        (volume.minY >= chest.minY + 0.05) shouldBe true
    }

    "slight rise clears a neighbouring lip while keeping the panel over the container" {
        val lip = BoundingBox(1.0, 1.0, 0.0, 2.0, 1.25, 1.0)
        val placed = requireNotNull(ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, bounds ->
            !bounds.overlaps(chest) && !bounds.overlaps(lip)
        })
        val volume = ChestPreviewPlacement.bounds(eye, placed, panel, 0.9f)
        (volume.minY >= 1.25) shouldBe true
        (abs(volume.centerZ - chest.centerZ) < 0.00001) shouldBe true
    }

    "diagonal views beside two leaf walls stay above the lid with line of sight" {
        val small = ChestPreviewIconGeometry.panelBounds(2, 0.9f)
        val blocks = listOf(chest, BoundingBox(-1.0, 0.0, -1.0, 0.0, 3.0, 2.0), BoundingBox(-1.0, 0.0, 1.0, 2.0, 3.0, 2.0))
        for (pitch in listOf(45f, 60f, 75f, 90f)) for (yaw in 0..90 step 15) {
            val viewer = Location(null, 0.5, 1.0, 0.5, yaw.toFloat(), pitch)
            viewer.subtract(viewer.direction.multiply(3.0))
            val placed = requireNotNull(ChestPreviewPlacement.choose(viewer, above, chest, small, 0.9f) { _, volume ->
                val ray = volume.bounds.center.subtract(viewer.toVector())
                blocks.none(volume::overlaps) && blocks.none { it.rayTrace(viewer.toVector(), ray, ray.length()) != null }
            }) { "No panel at yaw=$yaw pitch=$pitch" }
            val bounds = ChestPreviewPlacement.bounds(viewer, placed, small, 0.9f)
            (bounds.minY >= above.y - 0.00001) shouldBe true
            // It may nudge within the lid footprint, never drop to a distant side position.
            (bounds.centerX in 0.0..1.0 && bounds.centerZ in 0.0..1.0) shouldBe true
        }
    }

    "strafing along stacked containers has no quarter-block placement steps and holds at rest" {
        val options = ChestPreviewSettings(stabilityThreshold = 0.02)
        val blocks = listOf(chest, BoundingBox(0.0, 1.0, 0.0, 1.0, 2.0, 1.0))
        val grid = ChestPreviewIconGeometry.panelBounds(6, options.scale, options)
        var previous: InspectionHologramAnchor? = null
        for (step in 0..80) {
            val viewer = Location(null, -1.5 + step * 0.05, 1.3, -2.5)
            viewer.direction = chest.center.subtract(viewer.toVector())
            val next = requireNotNull(ChestPreviewPlacement.choose(viewer, above, chest, grid, options.scale, options, previous) { _, volume ->
                blocks.none(volume::overlaps)
            })
            previous?.let { old ->
                val distance = org.bukkit.util.Vector(next.x - old.x, next.y - old.y, next.z - old.z).length()
                (distance < 0.12) shouldBe true
            }
            val still = ChestPreviewPlacement.choose(viewer, above, chest, grid, options.scale, options, next) { _, volume ->
                blocks.none(volume::overlaps)
            }
            still shouldBe next
            previous = next
        }
        // Removing the overhead obstruction must restore the top rather than latch the side.
        val restored = requireNotNull(ChestPreviewPlacement.choose(eye, above, chest, grid, options.scale, options, previous) { _, volume ->
            !volume.overlaps(chest)
        })
        (ChestPreviewPlacement.bounds(eye, restored, grid, options.scale, options).minY >= above.y - 0.00001) shouldBe true
    }

    "empty corners of the rotated scan envelope do not count as panel collisions" {
        val diagonal = kotlin.math.sqrt(0.5)
        val volume = ChestPreviewVolume(Vector3d(), Vector3d(diagonal, 0.0, diagonal),
            Vector3d(0.0, 1.0, 0.0), Vector3d(-diagonal, 0.0, diagonal), Vector3d(1.0, 0.1, 0.1))
        val emptyCorner = BoundingBox(-0.75, -0.05, 0.65, -0.65, 0.05, 0.75)
        volume.bounds.overlaps(emptyCorner) shouldBe true
        volume.overlaps(emptyCorner) shouldBe false
        volume.overlaps(BoundingBox(0.4, -0.05, 0.4, 0.6, 0.05, 0.6)) shouldBe true
    }

    "swept clearance includes a thin obstacle between the endpoint poses" {
        val panel = ChestPreviewVolume(Vector3d(), Vector3d(1.0, 0.0, 0.0),
            Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0), Vector3d(0.1, 0.1, 0.01))
        val thin = BoundingBox(-0.05, -0.05, 0.055, 0.05, 0.05, 0.065)
        panel.overlaps(thin) shouldBe false
        panel.swept(Vector3d(0.0, 0.0, 1.0)).overlaps(thin) shouldBe true
    }

    "panel edge collision counts even when its center is free, and no fit hides the panel" {
        val edgeBlock = BoundingBox(1.0, 1.0, 0.0, 2.0, 2.0, 1.0)
        val placed = ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, bounds ->
            !bounds.overlaps(edgeBlock) && !bounds.overlaps(chest)
        }
        (placed != null && placed != above) shouldBe true
        ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, _ -> false } shouldBe null
    }

    "pitched and rotated camera bounds include both depth and every row" {
        val tilted = eye.clone().apply { yaw = 45f; pitch = 40f }
        val large = ChestPreviewIconGeometry.panelBounds(12, 2f)
        val bounds = ChestPreviewPlacement.bounds(tilted, above, large, 2f)
        (bounds.widthX > 2.0 && bounds.widthZ > 2.0 && bounds.height > 3.0) shouldBe true
    }
})
