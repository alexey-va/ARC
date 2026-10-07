package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import ru.arc.paper.api.InspectionHologramAnchor
import java.util.UUID

class ChestPreviewPlacementTest : StringSpec({
    val eye = Location(null, 0.5, 1.6, -3.0)
    val above = InspectionHologramAnchor(UUID.randomUUID(), 0.5, 1.15, 0.5)
    val chest = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    val panel = ChestPreviewIconGeometry.panelBounds(6, 0.9f)

    "clear chest keeps its top anchor but a stacked chest moves the entire grid in front" {
        fun choose(blocks: List<BoundingBox>, previous: InspectionHologramAnchor? = null) =
            ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f, previous) { _, bounds ->
                blocks.none { it.overlaps(bounds) }
            }
        choose(listOf(chest)) shouldBe above
        val stacked = listOf(chest, BoundingBox(0.0, 1.0, 0.0, 1.0, 2.0, 1.0))
        val front = requireNotNull(choose(stacked))
        (front.z < 0.0) shouldBe true
        val volume = ChestPreviewPlacement.bounds(eye, front, panel, 0.9f)
        stacked.none { it.overlaps(volume) } shouldBe true
        (volume.minY >= 0.049) shouldBe true
        // Removing the obstruction cannot make a currently clear panel flicker between anchors.
        choose(listOf(chest), front) shouldBe front
    }

    "panel edge collision counts even when its center is free, and no fit hides the panel" {
        val edgeBlock = BoundingBox(1.0, 1.0, 0.0, 2.0, 2.0, 1.0)
        val placed = ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f, null) { _, bounds ->
            !edgeBlock.overlaps(bounds) && !chest.overlaps(bounds)
        }
        (placed != null && placed != above) shouldBe true
        ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f, null) { _, _ -> false } shouldBe null
    }

    "pitched and rotated camera bounds include both depth and every row" {
        val tilted = eye.clone().apply { yaw = 45f; pitch = 40f }
        val large = ChestPreviewIconGeometry.panelBounds(12, 2f)
        val bounds = ChestPreviewPlacement.bounds(tilted, above, large, 2f)
        (bounds.widthX > 2.0 && bounds.widthZ > 2.0 && bounds.height > 3.0) shouldBe true
    }
})
