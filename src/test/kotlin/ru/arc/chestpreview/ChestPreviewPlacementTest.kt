package ru.arc.chestpreview

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Vector3d
import ru.arc.paper.display.DisplayOrientedVolume
import ru.arc.paper.api.InspectionHologramAnchor
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class ChestPreviewPlacementTest : StringSpec({
    val options = ChestPreviewSettings()
    val above = InspectionHologramAnchor(UUID(0, 1), 0.5, 1.15, 0.5)
    val chest = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
    val panel = ChestPreviewIconGeometry.panelBounds(54, options.scale, options)

    "the dense panel is in front of the container and reserves its bottom above the floor" {
        for (yaw in listOf(0f, 30f, 45f, 90f)) for (pitch in listOf(-20f, 0f, 20f, 60f, 90f)) {
            val eye = Location(null, 0.5, 0.5, 0.5, yaw, pitch)
            eye.subtract(eye.direction.multiply(3.0))
            val anchor = ChestPreviewPlacement.choose(eye, above, chest, panel, options.scale, options)
            val center = Vector(anchor.x, anchor.y, anchor.z)
            val normal = eye.direction.multiply(-1)
            val depth = ChestPreviewIconGeometry.depth(options.scale, options)
            val yawRadians = Math.toRadians(yaw.toDouble())
            val pitchRadians = Math.toRadians(pitch.toDouble())
            val volume = DisplayOrientedVolume(
                Vector3d(center.x, center.y, center.z),
                Vector3d(-cos(yawRadians), 0.0, -sin(yawRadians)),
                Vector3d(-sin(yawRadians) * sin(pitchRadians), cos(pitchRadians), cos(yawRadians) * sin(pitchRadians)),
                Vector3d(normal.x, normal.y, normal.z),
                Vector3d(panel.width / 2.0, panel.height / 2.0, depth.toDouble()),
            )
            volume.overlaps(chest) shouldBe false
            // Ordinary front views must clear a whole stack, including the upper container.
            if (pitch <= 45f) volume.overlaps(chest.clone().shift(0.0, 1.0, 0.0)) shouldBe false
            (anchor.y - panel.height / 2 - depth >= chest.minY + 0.039) shouldBe true
        }
    }

    "a steep ray hitting the lower side does not place its panel inside the upper container" {
        val upper = chest.clone().shift(0.0, 1.0, 0.0)
        val eye = Location(null, 0.5, 4.0, -2.0).apply {
            direction = Vector(0.5, 0.95, 0.0).subtract(toVector())
        }
        (eye.pitch > 45f) shouldBe true
        upper.rayTrace(eye.toVector(), eye.direction, 4.5) shouldBe null
        (chest.rayTrace(eye.toVector(), eye.direction, 4.5) != null) shouldBe true
        val anchor = ChestPreviewPlacement.choose(eye, above, chest, panel, options.scale, options)
        val pitch = Math.toRadians(eye.pitch.toDouble())
        val normal = eye.direction.multiply(-1)
        val volume = DisplayOrientedVolume(
            Vector3d(anchor.x, anchor.y, anchor.z), Vector3d(-1.0, 0.0, 0.0),
            Vector3d(0.0, cos(pitch), sin(pitch)), Vector3d(normal.x, normal.y, normal.z),
            Vector3d(panel.width / 2.0, panel.height / 2.0, ChestPreviewIconGeometry.depth(options.scale, options).toDouble()),
        )
        volume.overlaps(chest) shouldBe false
        volume.overlaps(upper) shouldBe false
        (anchor.z < chest.minZ) shouldBe true
    }

    "source geometry export covers dense front, diagonal, overhead, steep side and partial views" {
        val states = listOf(
            Triple("front-54", 0f, 18f), Triple("diagonal-54", 40f, 18f),
            Triple("overhead-54", 0f, 90f), Triple("steep-side-54", 0f, 56f), Triple("partial-13", 0f, 18f),
        ).map { (id, yaw, pitch) ->
            // An overhead ray selects the upper container; the steep side ray can reach the lower one.
            val selected = if (id.startsWith("overhead")) chest.clone().shift(0.0, 1.0, 0.0) else chest
            val source = above.copy(y = selected.maxY + options.verticalGap)
            val target = if (id.startsWith("steep-side")) Vector(0.5, 0.95, 0.0) else selected.center
            val eye = if (id.startsWith("steep-side")) Location(null, 0.5, 4.0, -2.0).apply {
                direction = target.clone().subtract(toVector())
            } else Location(null, target.x, target.y, target.z, yaw, pitch).apply {
                subtract(direction.multiply(3.5))
            }
            val anchor = ChestPreviewPlacement.choose(eye, source, selected, panel, options.scale, options)
            val count = if (id == "partial-13") 13 else 54
            val offsets = ChestPreviewIconGeometry.offsets(count, options.scale, options)
            offsets.size shouldBe count
            mapOf(
                "id" to id, "camera" to listOf(eye.x, eye.y, eye.z),
                "target" to listOf(target.x, target.y, target.z), "yaw" to eye.yaw, "pitch" to eye.pitch,
                "anchor" to listOf(anchor.x, anchor.y, anchor.z),
                "offsets" to offsets.map { listOf(it.x, it.y) },
                "panel" to ChestPreviewIconGeometry.panelBounds(count, options.scale, options),
            )
        }
        System.getenv("CHEST_PREVIEW_EXPORT_DIR")?.let { destination ->
            val output = Path.of(destination)
            Files.createDirectories(output)
            val exported = mapOf(
                "scope" to "Actual ChestPreviewIconGeometry and ChestPreviewPlacement; offline, not native client evidence",
                "settings" to options,
                "depth" to ChestPreviewIconGeometry.depth(options.scale, options),
                "blockRotation" to ChestPreviewIconGeometry.guiFacingRotation(true, options.blockPitch, options.blockYaw)
                    .let { listOf(it.x, it.y, it.z, it.w) },
                "flatRotation" to ChestPreviewIconGeometry.guiFacingRotation(false)
                    .let { listOf(it.x, it.y, it.z, it.w) },
                "states" to states,
            )
            Files.writeString(output.resolve("geometry.json"), GsonBuilder().setPrettyPrinting().create().toJson(exported))
        }
    }
})
