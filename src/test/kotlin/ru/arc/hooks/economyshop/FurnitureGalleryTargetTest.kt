package ru.arc.hooks.economyshop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import java.nio.file.Path

class FurnitureGalleryTargetTest : StringSpec({
    "planner covers an X-long rectangular AABB with an exact union of square targets" {
        val bounds = FurnitureGalleryBounds(
            minX = 10.0,
            minY = 64.0,
            minZ = 20.0,
            maxX = 18.0,
            maxY = 66.0,
            maxZ = 22.0,
        )
        val target = plan(bounds)

        target.segments.map { it.x } shouldContainExactly listOf(11.0, 13.0, 15.0, 17.0)
        target.segments.forEach { segment ->
            segment.width shouldBe 2.0
            segment.z shouldBe 21.0
            segment.y shouldBe bounds.minY
            segment.height shouldBe bounds.height
        }
        assertExactSquareUnion(target, bounds, longAxis = 'x')
    }

    "planner covers a Z-long rectangular AABB with an exact union of square targets" {
        val bounds = FurnitureGalleryBounds(
            minX = -1.0,
            minY = 63.0,
            minZ = -10.0,
            maxX = 1.0,
            maxY = 66.0,
            maxZ = -2.0,
        )
        val target = plan(bounds)

        target.segments.map { it.z } shouldContainExactly listOf(-9.0, -7.0, -5.0, -3.0)
        target.segments.forEach { segment ->
            segment.width shouldBe 2.0
            segment.x shouldBe 0.0
            segment.y shouldBe bounds.minY
            segment.height shouldBe bounds.height
        }
        assertExactSquareUnion(target, bounds, longAxis = 'z')
    }

    "planner accepts the maximum dimension and segment count boundaries" {
        val bounds = FurnitureGalleryBounds(
            minX = 0.0,
            minY = 64.0,
            minZ = 0.0,
            maxX = 10.0,
            maxY = 74.0,
            maxZ = 0.3125,
        )

        plan(
            bounds,
            FurnitureGalleryAnchor(5.0, 69.0, 0.15625, 0.0),
        ).segments.size shouldBe FURNITURE_GALLERY_MAX_SEGMENTS
    }

    "planner rejects invalid, non-finite, oversized and over-segmented bounds" {
        val validAnchor = FurnitureGalleryAnchor(5.0, 65.0, 1.0, 0.0)
        val validBounds = FurnitureGalleryBounds(0.0, 64.0, 0.0, 3.0, 66.0, 2.0)
        val invalidCases = listOf<() -> FurnitureGalleryTarget>(
            { plan(validBounds.copy(maxX = validBounds.minX), validAnchor) },
            { plan(validBounds.copy(maxY = validBounds.minY), validAnchor) },
            { plan(validBounds.copy(maxX = 10.01), validAnchor) },
            { plan(validBounds.copy(minX = 30_000_001.0, maxX = 30_000_004.0), validAnchor) },
            { plan(validBounds.copy(minY = -2_049.0, maxY = -2_048.0), validAnchor) },
            { plan(validBounds.copy(maxX = 10.0, maxZ = 0.3), validAnchor) },
            { plan(validBounds, validAnchor.copy(x = Double.NaN)) },
            { plan(validBounds, validAnchor.copy(yaw = Double.POSITIVE_INFINITY)) },
            { plan(validBounds.copy(minZ = Double.NaN), validAnchor) },
            { plan(validBounds.copy(maxY = Double.NEGATIVE_INFINITY), validAnchor) },
            { FurnitureGalleryTargetPlanner.create("", "fixture:item", validAnchor, validBounds) },
            { FurnitureGalleryTargetPlanner.create("valid", "bad item id", validAnchor, validBounds) },
        )

        invalidCases.forEach { invalid ->
            shouldThrow<IllegalArgumentException> { invalid() }
        }
    }

    "root matching requires the exact furniture ID and tolerates wrapped yaw" {
        val anchor = FurnitureGalleryAnchor(14.0, 64.5, 21.0, 359.995)
        val target = FurnitureGalleryTargetPlanner.create(
            key = "japan_sofa",
            furnitureId = "elitecreatures:jf3_sofa_red",
            anchor = anchor,
            bounds = FurnitureGalleryBounds(10.0, 64.0, 20.0, 18.0, 66.0, 22.0),
        )
        val observed = FurnitureGalleryAnchor(14.02, 64.52, 20.99, -0.005)

        FurnitureGalleryTargetPlanner.matchesAnchor(anchor, observed) shouldBe true
        FurnitureGalleryTargetPlanner.matchesFurnitureRoot(target, "elitecreatures:jf3_sofa_red", observed) shouldBe true
        FurnitureGalleryTargetPlanner.matchesFurnitureRoot(target, "elitecreatures:jf3_sofa_blue", observed) shouldBe false
        FurnitureGalleryTargetPlanner.matchesFurnitureRoot(
            target,
            "elitecreatures:jf3_sofa_red",
            observed.copy(x = 14.051),
        ) shouldBe false
    }

    "config parsing keeps valid entries and reports malformed entries" {
        val yaml = """
            targets:
              valid_target:
                furniture-id: fixture:item
                anchor: {x: 1.0, y: 64.0, z: 1.0, yaw: 0.0}
                bounds: {min: [0.0, 64.0, 0.0], max: [2.0, 66.0, 2.0]}
              invalid_target:
                furniture-id: fixture:invalid
                anchor: {x: 1.0, y: 64.0, z: 1.0, yaw: 0.0}
                bounds: {min: [0.0, 64.0], max: [2.0, 66.0, 2.0]}
        """.trimIndent()
        val invalidKeys = mutableListOf<String>()

        val targets = config(yaml).snapshot { invalidKeys += it }

        targets.map { it.key } shouldBe listOf("valid_target")
        invalidKeys shouldBe listOf("invalid_target")
    }

    "config rejects target maps beyond the configured count limit" {
        val yaml = buildString {
            appendLine("targets:")
            repeat(FURNITURE_GALLERY_MAX_TARGETS + 1) { index ->
                appendLine("  target_${index.toString().padStart(3, '0')}: {}")
            }
        }

        shouldThrow<IllegalArgumentException> { config(yaml).snapshot() }
    }

    "portable 139-target config parses to 296 square interaction segments" {
        val targetConfig = config(portableShowroomConfig())

        val targets = targetConfig.snapshot()

        targets.size shouldBe 139
        targets.sumOf { it.segments.size } shouldBe 296
    }
})

private fun plan(
    bounds: FurnitureGalleryBounds,
    anchor: FurnitureGalleryAnchor = FurnitureGalleryAnchor(1.0, 65.0, 1.0, 0.0),
): FurnitureGalleryTarget = FurnitureGalleryTargetPlanner.create(
    key = "fixture_target",
    furnitureId = "fixture:item",
    anchor = anchor,
    bounds = bounds,
)

private fun assertExactSquareUnion(
    target: FurnitureGalleryTarget,
    bounds: FurnitureGalleryBounds,
    longAxis: Char,
) {
    target.segments.map { it.width }.distinct() shouldBe listOf(minOf(bounds.widthX, bounds.widthZ))
    val longIntervals = target.segments.map { segment ->
        val center = if (longAxis == 'x') segment.x else segment.z
        (center - segment.width / 2.0) to (center + segment.width / 2.0)
    }.sortedBy { it.first }
    val expectedMin = if (longAxis == 'x') bounds.minX else bounds.minZ
    val expectedMax = if (longAxis == 'x') bounds.maxX else bounds.maxZ

    longIntervals.first().first shouldBe expectedMin
    longIntervals.last().second shouldBe expectedMax
    longIntervals.zipWithNext().all { (left, right) -> left.second >= right.first } shouldBe true
    target.segments.all { segment ->
        if (longAxis == 'x') {
            segment.z - segment.width / 2.0 == bounds.minZ && segment.z + segment.width / 2.0 == bounds.maxZ
        } else {
            segment.x - segment.width / 2.0 == bounds.minX && segment.x + segment.width / 2.0 == bounds.maxX
        }
    } shouldBe true
}

private fun config(yaml: String): FurnitureGalleryTargetConfig {
    val root = Files.createTempDirectory("furniture-gallery-target-config-")
    val configPath = root.resolve("modules/furniture-gallery.yml")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, yaml)
    return FurnitureGalleryTargetConfig(Config(root, "modules/furniture-gallery.yml"))
}

/** Builds a compact portable stand-in for the authored 139-target map (121×2 + 18×3 segments). */
private fun portableShowroomConfig(): String = buildString {
    appendLine("targets:")
    repeat(139) { index ->
        val suffix = index.toString().padStart(3, '0')
        val longWidth = if (index < 121) 3.0 else 5.0
        val minX = index * 16.0
        val maxX = minX + longWidth
        appendLine("  target_$suffix:")
        appendLine("    furniture-id: fixture:item_$suffix")
        appendLine("    anchor: {x: ${minX + longWidth / 2.0}, y: 65.0, z: 1.0, yaw: 0.0}")
        appendLine("    bounds: {min: [$minX, 64.0, 0.0], max: [$maxX, 66.0, 2.0]}")
    }
}
