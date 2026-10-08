package ru.arc.chestpreview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.entity.ItemDisplay

class ChestPreviewPreferencesTest : StringSpec({
    "unset overrides inherit the current server settings" {
        val server = ChestPreviewSettings(
            enabled = false,
            scale = 1.4f,
            maxItems = 8,
            columns = 4,
            showCounts = false,
            itemTransform = ItemDisplay.ItemDisplayTransform.FIXED,
        )
        val preferences = ChestPreviewPreferences.fromStored { key ->
            key shouldBe ChestPreviewPreferences.META_KEY
            "scale=1.1;columns=2"
        }

        preferences.applyTo(server) shouldBe server.copy(scale = 1.1f, columns = 2)
    }

    "every personal override survives metadata roundtrip" {
        val preferences = ChestPreviewPreferences(
            enabled = false,
            scale = 1.5f,
            maxItems = 9,
            columns = 6,
            cellSpacing = 0.72f,
            maxDistance = 3.8,
            verticalGap = 0.44,
            iconScale = 0.62f,
            depthScale = 0.95f,
            blockPitch = 41f,
            blockYaw = 55f,
            showCounts = false,
            countScale = 0.21f,
            countOffsetY = 0.08f,
            teleportTicks = 9,
            stabilityThreshold = 0.2,
            brightness = 3,
            backgroundOpacity = 88,
            itemTransform = ItemDisplay.ItemDisplayTransform.NONE,
        )
        (preferences.stored().length <= 512) shouldBe true

        ChestPreviewPreferences.fromStored { key ->
            key shouldBe ChestPreviewPreferences.META_KEY
            preferences.stored()
        } shouldBe preferences
    }

    "malformed and out-of-range values are ignored independently" {
        val raw = listOf(
            "enabled=maybe", "scale=NaN", "max-items=99", "columns=5", "cell-spacing=Infinity",
            "max-distance=10", "vertical-gap=-2", "icon-scale=0", "depth-scale=2", "block-pitch=46",
            "block-yaw=-1", "show-counts=yes", "count-scale=0.3", "count-offset-y=-1",
            "teleport-ticks=11", "stability-threshold=0.5", "brightness=-1", "background-opacity=101",
            "item-transform=THIRD_PERSON_LEFT_HAND",
        ).joinToString(";")

        ChestPreviewPreferences.fromStored { raw } shouldBe ChestPreviewPreferences(columns = 5)
        ChestPreviewPreferences.fromStored { "x".repeat(513) } shouldBe ChestPreviewPreferences()
        ChestPreviewPreferences.fromStored { "default" } shouldBe ChestPreviewPreferences()
    }
})
