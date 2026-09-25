package ru.arc.hooks.economyshop

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import java.util.UUID

class FurnitureGalleryTargetTest : StringSpec({
    "planner rotates local vertices by live yaw and translates from the exact spawned root" {
        val profile = profile(
            "furniture:chair",
            listOf(
                FurnitureGalleryVertex(-1.0, 0.0, -0.5),
                FurnitureGalleryVertex(1.0, 0.0, -0.5),
                FurnitureGalleryVertex(-1.0, 2.0, 0.5),
                FurnitureGalleryVertex(1.0, 2.0, 0.5),
            ),
        )
        val rootId = UUID.fromString("69f7b21b-7de1-49ce-aa61-f29f5d1526d7")

        val plan = FurnitureGalleryTargetPlanner.plan(
            profile,
            rootId,
            FurnitureGalleryAnchor(21.0, 67.001, 21.5, 90.0),
        )

        plan.rootId shouldBe rootId
        plan.targetKey shouldBe "native:$rootId"
        plan.furnitureId shouldBe "furniture:chair"
        plan.bounds.minX shouldBe 20.5
        plan.bounds.maxX shouldBe 21.5
        plan.bounds.minY shouldBe 67.001
        plan.bounds.maxY shouldBe 69.001
        plan.bounds.minZ shouldBe 20.5
        plan.bounds.maxZ shouldBe 22.5
        plan.segments shouldHaveSize 2
        plan.segments.map { it.z } shouldContainExactly listOf(21.0, 22.0)
        plan.segments.all { it.width == 1.0 && it.height == 2.0 } shouldBe true
    }

    "planner caps segment count and waits until every touched chunk is loaded" {
        val elongated = profile(
            "fixture:long",
            listOf(
                FurnitureGalleryVertex(0.0, 0.0, 0.0),
                FurnitureGalleryVertex(10.0, 10.0, 0.3125),
            ),
        )
        val plan = FurnitureGalleryTargetPlanner.plan(
            elongated,
            UUID.fromString("64b591bf-ae68-4382-91e0-9d7426a7589d"),
            FurnitureGalleryAnchor(15.0, 64.0, 0.0, 0.0),
        )

        plan.segments shouldHaveSize FURNITURE_GALLERY_MAX_SEGMENTS
        val crossing = plan.segments.first { it.touchedChunks.size > 1 }
        val onlyRootChunk = setOf(plan.rootChunkX to plan.rootChunkZ)
        FurnitureGalleryTargetPlanner.desiredSegments(plan, rootChunkLoaded = true, loadedChunks = onlyRootChunk)
            .none { it.index == crossing.index } shouldBe true
        val loaded = plan.dependencyChunks
        FurnitureGalleryTargetPlanner.desiredSegments(plan, rootChunkLoaded = true, loadedChunks = loaded)
            .shouldHaveSize(FURNITURE_GALLERY_MAX_SEGMENTS)
        FurnitureGalleryTargetPlanner.desiredSegments(plan, rootChunkLoaded = false, loadedChunks = loaded)
            .shouldHaveSize(0)
    }

    "planner rejects malformed IDs, unbounded vertices and unsafe world extents" {
        shouldThrow<IllegalArgumentException> {
            FurnitureGalleryTargetPlanner.profile("Bad:ID", listOf(FurnitureGalleryVertex(0.0, 0.0, 0.0)))
        }
        shouldThrow<IllegalArgumentException> {
            FurnitureGalleryTargetPlanner.profile("fixture:item", listOf(FurnitureGalleryVertex(Double.NaN, 0.0, 0.0)))
        }
        shouldThrow<IllegalArgumentException> {
            FurnitureGalleryTargetPlanner.profile("fixture:item", listOf(FurnitureGalleryVertex(11.0, 0.0, 0.0)))
        }
        val flat = profile("fixture:flat", listOf(FurnitureGalleryVertex(0.0, 0.0, 0.0)))
        shouldThrow<IllegalArgumentException> {
            FurnitureGalleryTargetPlanner.plan(flat, UUID.randomUUID(), FurnitureGalleryAnchor(0.0, 64.0, 0.0, 0.0))
        }
        val remote = profile(
            "fixture:remote",
            listOf(
                FurnitureGalleryVertex(0.0, 0.0, 0.0),
                FurnitureGalleryVertex(0.5, 1.0, 0.5),
            ),
        )
        shouldThrow<IllegalArgumentException> {
            FurnitureGalleryTargetPlanner.plan(remote, UUID.randomUUID(), FurnitureGalleryAnchor(30_000_001.0, 64.0, 0.0, 0.0))
        }
    }

    "config accepts exact ID vertex profiles, skips invalid profiles, and ignores legacy anchors" {
        val yaml = """
            profiles:
              fixture:oak_chair:
                vertices:
                  - [-0.25, 0.0, -0.25]
                  - [0.25, 1.0, 0.25]
              fixture:bad:
                vertices:
                  - [0.0, 0.0]
            targets:
              legacy_target: {furniture-id: fixture:wrong}
        """.trimIndent()
        val invalidIds = mutableListOf<String>()

        val profiles = config(yaml).snapshot { invalidIds += it }

        profiles.keys shouldContainExactly listOf("fixture:oak_chair")
        profiles.getValue("fixture:oak_chair").vertices shouldHaveSize 2
        invalidIds shouldContainExactly listOf("fixture:bad")
    }

    "config rejects profile and aggregate vertex counts beyond fixed bounds" {
        val tooManyProfiles = buildString {
            appendLine("profiles:")
            repeat(FURNITURE_GALLERY_MAX_PROFILES + 1) { index ->
                appendLine("  fixture:item_$index: {vertices: [[0.0, 0.0, 0.0]]}")
            }
        }
        shouldThrow<IllegalArgumentException> { config(tooManyProfiles).snapshot() }

        val tooManyVertices = buildString {
            appendLine("profiles:")
            appendLine("  fixture:too_many:")
            appendLine("    vertices:")
            repeat(FURNITURE_GALLERY_MAX_VERTICES + 1) { appendLine("      - [0.0, 0.0, 0.0]") }
        }
        val invalid = mutableListOf<String>()
        config(tooManyVertices).snapshot { invalid += it } shouldBe emptyMap()
        invalid shouldBe listOf("fixture:too_many")
    }
})

private fun profile(id: String, vertices: List<FurnitureGalleryVertex>): FurnitureGalleryProfile =
    FurnitureGalleryTargetPlanner.profile(id, vertices)

private fun config(yaml: String): FurnitureGalleryTargetConfig {
    val root = Files.createTempDirectory("furniture-gallery-profile-config-")
    val configPath = root.resolve("modules/furniture-gallery.yml")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, yaml)
    return FurnitureGalleryTargetConfig(Config(root, "modules/furniture-gallery.yml"))
}
