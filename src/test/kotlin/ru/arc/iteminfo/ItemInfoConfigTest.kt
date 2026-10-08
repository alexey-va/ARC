package ru.arc.iteminfo

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.entity.ItemDisplay
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.chestpreview.ChestPreviewSettings
import java.nio.file.Files

class ItemInfoConfigTest : StringSpec({
    "empty config uses the supported player-facing defaults" {
        val settings = ItemInfoConfig(ConfigManager.empty()).snapshot()

        settings.enabled shouldBe true
        settings.targetDistance shouldBe 5.0
        settings.nameOnlyTemplate shouldBe "<white><name>"
        settings.hologramTemplate shouldBe "<white><name><newline><gray><id>"
        settings.bossbarTemplate shouldBe "<white><name> <dark_gray>· <gray><id>"
        settings.chestPreview shouldBe ChestPreviewSettings()
        settings.chestPreview.itemTransform shouldBe ItemDisplay.ItemDisplayTransform.GUI
    }

    "invalid chest preview bounds in the module config fail closed" {
        val invalidValues = listOf<(Config) -> Unit>(
            { it.setDouble("chest-preview.scale", 0.49) },
            { it.setDouble("chest-preview.scale", 2.01) },
            { it.setInt("chest-preview.max-items", 0) },
            { it.setInt("chest-preview.max-items", 13) },
            { it.setInt("chest-preview.columns", 0) },
            { it.setInt("chest-preview.columns", 7) },
            { it.setDouble("chest-preview.cell-spacing", 0.29) },
            { it.setDouble("chest-preview.cell-spacing", 1.01) },
            { it.setDouble("chest-preview.max-distance", 4.6) },
            { it.setDouble("chest-preview.vertical-gap", 0.51) },
            { it.setDouble("chest-preview.icon-scale", 0.19) },
            { it.setDouble("chest-preview.icon-scale", 0.71) },
            { it.setDouble("chest-preview.depth-scale", 0.049) },
            { it.setDouble("chest-preview.depth-scale", 1.01) },
            { it.setDouble("chest-preview.block-pitch", -0.01) },
            { it.setDouble("chest-preview.block-pitch", 45.01) },
            { it.setDouble("chest-preview.block-yaw", -0.01) },
            { it.setDouble("chest-preview.block-yaw", 60.01) },
            { it.setDouble("chest-preview.count-scale", 0.059) },
            { it.setDouble("chest-preview.count-scale", 0.251) },
            { it.setDouble("chest-preview.count-offset-y", -0.401) },
            { it.setDouble("chest-preview.count-offset-y", 0.101) },
            { it.setInt("chest-preview.teleport-ticks", -1) },
            { it.setInt("chest-preview.teleport-ticks", 11) },
            { it.setDouble("chest-preview.stability-threshold", -0.001) },
            { it.setDouble("chest-preview.stability-threshold", 0.201) },
            { it.setInt("chest-preview.brightness", -1) },
            { it.setInt("chest-preview.brightness", 16) },
            { it.setInt("chest-preview.background-opacity", -1) },
            { it.setInt("chest-preview.background-opacity", 101) },
            { it.setString("chest-preview.item-transform", "THIRD_PERSON_LEFT_HAND") },
            { it.setString("chest-preview.item-transform", "NOT_A_TRANSFORM") },
        )

        invalidValues.forEach { configure ->
            val directory = Files.createTempDirectory("arc-item-info-config-")
            try {
                val config = Config(directory, "modules/item-info.yml")
                configure(config)
                shouldThrow<IllegalArgumentException> { ItemInfoConfig(config).snapshot() }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    }

    "all container preview tuning keys load from configuration" {
        val config = ConfigManager.empty().apply {
            setBoolean("chest-preview.enabled", false)
            setDouble("chest-preview.scale", 1.25)
            setInt("chest-preview.max-items", 10)
            setInt("chest-preview.columns", 4)
            setDouble("chest-preview.cell-spacing", 0.62)
            setDouble("chest-preview.max-distance", 3.5)
            setDouble("chest-preview.vertical-gap", 0.3)
            setDouble("chest-preview.icon-scale", 0.55)
            setDouble("chest-preview.depth-scale", 0.8)
            setDouble("chest-preview.block-pitch", 30.0)
            setDouble("chest-preview.block-yaw", 50.0)
            setBoolean("chest-preview.show-counts", false)
            setDouble("chest-preview.count-scale", 0.2)
            setDouble("chest-preview.count-offset-y", 0.05)
            setInt("chest-preview.teleport-ticks", 8)
            setDouble("chest-preview.stability-threshold", 0.16)
            setInt("chest-preview.brightness", 5)
            setInt("chest-preview.background-opacity", 75)
            setString("chest-preview.item-transform", "FIXED")
        }

        ItemInfoConfig(config).snapshot().chestPreview shouldBe ChestPreviewSettings(
            enabled = false,
            scale = 1.25f,
            maxItems = 10,
            columns = 4,
            cellSpacing = 0.62f,
            maxDistance = 3.5,
            verticalGap = 0.3,
            iconScale = 0.55f,
            depthScale = 0.8f,
            blockPitch = 30.0f,
            blockYaw = 50.0f,
            showCounts = false,
            countScale = 0.2f,
            countOffsetY = 0.05f,
            teleportTicks = 8,
            stabilityThreshold = 0.16,
            brightness = 5,
            backgroundOpacity = 75,
            itemTransform = ItemDisplay.ItemDisplayTransform.FIXED,
        )
    }
    "real config reload changes item transform without retaining the previous snapshot" {
        val directory = Files.createTempDirectory("arc-chest-item-transform-reload-")
        try {
            val path = directory.resolve("modules/item-info.yml")
            Files.createDirectories(path.parent)
            Files.writeString(path, "chest-preview:\n  background-opacity: 25\n  item-transform: gUi\n")
            val source = Config(directory, "modules/item-info.yml")
            ItemInfoConfig(source).snapshot().chestPreview.apply {
                backgroundOpacity shouldBe 25
                itemTransform shouldBe ItemDisplay.ItemDisplayTransform.GUI
            }
            Files.writeString(path, "chest-preview:\n  background-opacity: 80\n  item-transform: fixed\n")
            source.reload()
            ItemInfoConfig(source).snapshot().chestPreview.apply {
                backgroundOpacity shouldBe 80
                itemTransform shouldBe ItemDisplay.ItemDisplayTransform.FIXED
            }
            Files.writeString(path, "chest-preview:\n  background-opacity: 80\n  item-transform: NONE\n")
            source.reload()
            ItemInfoConfig(source).snapshot().chestPreview.itemTransform shouldBe ItemDisplay.ItemDisplayTransform.NONE
        } finally { directory.toFile().deleteRecursively() }
    }
})
