package ru.arc.autobuild

import io.kotest.matchers.shouldBe
import org.bukkit.Location
import ru.arc.KotestTestBase
import java.util.UUID

class ConstructionPreviewRefreshTest : KotestTestBase({
    fun draft(
        buildingId: String,
        blueprintId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        transform: BuildBookTransform = BuildBookTransform(),
    ): BuildBookData = BuildBookData(
        buildingId = buildingId,
        title = "Дом у озера",
        transform = transform,
        playerCreated = true,
        creatorId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        creatorName = "Builder",
        blueprintId = blueprintId,
        contentSha256 = "a".repeat(64),
        schematicSha256 = "b".repeat(64),
        blockCount = 12,
        cooldownSeconds = 0,
    ).validated()

    describe("construction preview transform updates") {
        it("treats an absent preview as a valid book-only update") {
            val player = server.addPlayer("NoPreviewBuilder")
            val next = draft("amogus_1.schem", transform = BuildBookTransform(offsetX = 1))

            BuildingManager.getPendingConstruction(player.uniqueId) shouldBe null
            BuildingManager.updatePendingTransform(player, next) shouldBe PreviewTransformUpdateResult.NO_PREVIEW
            PreviewTransformUpdateResult.NO_PREVIEW.allowsBookUpdate shouldBe true
        }

        it("distinguishes an inactive preview without mutating its transform") {
            val world = server.addSimpleWorld("inactive-preview-world")
            val player = server.addPlayer("InactivePreviewBuilder")
            val current = draft("amogus_1.schem")
            val site = ConstructionSite(
                Building(current.buildingId),
                Location(world, 0.0, 64.0, 0.0),
                player,
                0,
                world,
                0,
                0,
                bookData = current,
                initialTransform = current.transform,
            )
            val next = current.copy(transform = BuildBookTransform(offsetX = 1)).validated()

            site.refreshPreviewResult(next) shouldBe PreviewTransformUpdateResult.PREVIEW_INACTIVE
            site.bookTransform shouldBe current.transform
            site.bookData shouldBe current
        }

        it("distinguishes another book from the visible preview") {
            val world = server.addSimpleWorld("mismatched-preview-world")
            val player = server.addPlayer("MismatchedPreviewBuilder")
            val current = draft("amogus_1.schem")
            val site = ConstructionSite(
                Building(current.buildingId),
                Location(world, 0.0, 64.0, 0.0),
                player,
                0,
                world,
                0,
                0,
                bookData = current,
                initialTransform = current.transform,
            )
            site.startDisplayingBorder() shouldBe true
            val other = current.copy(
                blueprintId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                transform = BuildBookTransform(offsetX = 1),
            ).validated()

            site.refreshPreviewResult(other) shouldBe PreviewTransformUpdateResult.BOOK_MISMATCH
            site.bookTransform shouldBe current.transform
            site.bookData shouldBe current
        }

        it("rolls back a transform denied by the protection owner") {
            val world = server.addSimpleWorld("protected-preview-world")
            val player = server.addPlayer("ProtectedPreviewBuilder")
            val current = draft("amogus_1.schem")
            val site = ConstructionSite(
                Building(current.buildingId),
                Location(world, 0.0, 64.0, 0.0),
                player,
                0,
                world,
                0,
                0,
                bookData = current,
                initialTransform = current.transform,
            )
            site.startDisplayingBorder() shouldBe true
            val next = current.copy(transform = BuildBookTransform(offsetX = 1)).validated()

            site.refreshPreviewResult(next, buildPermissionCheck = { false }) shouldBe
                PreviewTransformUpdateResult.PROTECTION_DENIED

            site.bookTransform shouldBe current.transform
            site.bookData shouldBe current
        }

        it("updates both the visible preview and its bound book on success") {
            val world = server.addSimpleWorld("updated-preview-world")
            val player = server.addPlayer("UpdatedPreviewBuilder")
            val current = draft("amogus_1.schem")
            val site = ConstructionSite(
                Building(current.buildingId),
                Location(world, 0.0, 64.0, 0.0),
                player,
                0,
                world,
                0,
                0,
                bookData = current,
                initialTransform = current.transform,
            )
            site.startDisplayingBorder() shouldBe true
            val next = current.copy(transform = BuildBookTransform(offsetX = 1, rotation = 90)).validated()

            site.refreshPreviewResult(next) shouldBe PreviewTransformUpdateResult.UPDATED
            site.bookTransform shouldBe next.transform
            site.bookData shouldBe next
            PreviewTransformUpdateResult.UPDATED.allowsBookUpdate shouldBe true
        }
    }
})
