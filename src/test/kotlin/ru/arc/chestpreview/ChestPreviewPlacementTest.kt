package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox
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
                blocks.none { it.overlaps(bounds) }
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
                !chest.overlaps(bounds) && !leaves.overlaps(bounds)
            })
            val volume = ChestPreviewPlacement.bounds(lookingDown, placed, smallPanel, 0.9f)
            (abs(volume.centerX - chest.centerX) < 0.00001) shouldBe true
            (abs(volume.centerZ - chest.centerZ) < 0.00001) shouldBe true
            (abs(volume.minY - above.y) < 0.00001) shouldBe true
        }
    }

    "slight rise clears a neighbouring lip while keeping the panel over the container" {
        val lip = BoundingBox(1.0, 1.0, 0.0, 2.0, 1.25, 1.0)
        val placed = requireNotNull(ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, bounds ->
            !chest.overlaps(bounds) && !lip.overlaps(bounds)
        })
        val volume = ChestPreviewPlacement.bounds(eye, placed, panel, 0.9f)
        (volume.minY >= 1.25) shouldBe true
        (abs(volume.centerZ - chest.centerZ) < 0.00001) shouldBe true
    }

    "panel edge collision counts even when its center is free, and no fit hides the panel" {
        val edgeBlock = BoundingBox(1.0, 1.0, 0.0, 2.0, 2.0, 1.0)
        val placed = ChestPreviewPlacement.choose(eye, above, chest, panel, 0.9f) { _, bounds ->
            !edgeBlock.overlaps(bounds) && !chest.overlaps(bounds)
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
