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
import kotlin.math.PI
import kotlin.math.abs
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

    "one orbit follows eye position around single and rectangular double chests without a yaw seam" {
        for (box in listOf(chest, BoundingBox(0.0, 0.0, 0.0, 2.0, 1.0, 1.0))) {
            val source = above.copy(x = box.centerX, z = box.centerZ)
            var first: Vector? = null
            var previous: Vector? = null
            for (degree in 0..360) {
                val angle = Math.toRadians(degree.toDouble())
                val x = sin(angle)
                val z = -cos(angle)
                val eye = Location(null, box.centerX + 3.5 * x, 1.62, box.centerZ + 3.5 * z)
                eye.direction = box.center.subtract(eye.toVector())
                val at = ChestPreviewPlacement.choose(eye, source, box, panel, options.scale)
                ChestPreviewPlacement.choose(eye.clone().apply { yaw += 15f; pitch -= 10f },
                    source, box, panel, options.scale) shouldBe at
                val delta = Vector(at.x - box.centerX, at.y, at.z - box.centerZ)
                (abs(delta.x * z - delta.z * x) < 1e-8) shouldBe true
                (delta.x * x + delta.z * z > (abs(x) * box.widthX + abs(z) * box.widthZ) / 2) shouldBe true
                previous?.let { (delta.distance(it) < .04) shouldBe true }
                if (degree == 0) first = delta
                if (degree == 360) (delta.distance(first!!) < 1e-8) shouldBe true
                previous = delta
            }
        }
    }

    "a walk across the lid centers overhead and crosses both footprint edges continuously" {
        var previous: Vector? = null
        for (step in -100..100) {
            val eye = Location(null, chest.centerX + step / 100.0, 3.1, chest.centerZ)
            val at = ChestPreviewPlacement.choose(eye, above, chest, panel, options.scale)
            val next = Vector(at.x, at.y, at.z)
            previous?.let { (next.distance(it) < .06) shouldBe true }
            if (step == 0) {
                at.x shouldBe chest.centerX
                at.z shouldBe chest.centerZ
                (at.y > chest.maxY) shouldBe true
            }
            previous = next
        }
    }

    "motion coalesces one tick and sub-pixel jitter against the last accepted world position" {
        val moved = above.copy(x = above.x + 0.05)
        ChestPreviewPlacement.follow(null, above, 0) shouldBe above
        ChestPreviewPlacement.follow(above, moved, 1) shouldBe above
        ChestPreviewPlacement.follow(above, above.copy(z = above.z + 0.003), 10) shouldBe above
        ChestPreviewPlacement.follow(above, moved, 2) shouldBe moved
    }

    "source geometry export includes the actual orbit path, timing and representative static views" {
        // One 6-second walking circuit, then the same starting viewpoint. At each tick the
        // first-person camera points at the lower container, including when the distance changes.
        val orbit = mutableListOf<Map<String, Any>>()
        var previousAnchor: InspectionHologramAnchor? = null
        var moveTick = 0
        for (tick in 0..120) {
            val bearing = tick * 2.0 * PI / 120
            val radius = 3.15 + 0.65 * cos(bearing)
            val eye = Location(null, chest.centerX + radius * sin(bearing), 1.62, chest.centerZ - radius * cos(bearing))
            eye.direction = chest.center.subtract(eye.toVector())
            (chest.rayTrace(eye.toVector(), eye.direction, options.maxDistance) != null) shouldBe true
            chest.clone().shift(0.0, 1.0, 0.0).rayTrace(eye.toVector(), eye.direction, options.maxDistance) shouldBe null
            val desired = ChestPreviewPlacement.choose(eye, above, chest, panel, options.scale, options)
            val anchor = ChestPreviewPlacement.follow(previousAnchor, desired, tick - moveTick)
            if (anchor != previousAnchor) moveTick = tick
            previousAnchor = anchor
            orbit += mapOf("tick" to tick, "camera" to listOf(eye.x, eye.y, eye.z),
                "anchor" to listOf(anchor.x, anchor.y, anchor.z), "yaw" to eye.yaw, "pitch" to eye.pitch)
        }
        val states = listOf(
            Triple("front-54", 0f, 18f), Triple("single-1", 0f, 18f), Triple("diagonal-54", 40f, 18f),
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
            val count = when (id) { "single-1" -> 1; "partial-13" -> 13; else -> 54 }
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
                "orbit" to orbit,
                "motionTicks" to ChestPreviewPlacement.MOTION_TICKS,
                "states" to states,
            )
            Files.writeString(output.resolve("geometry.json"), GsonBuilder().setPrettyPrinting().create().toJson(exported))
        }
    }
})
