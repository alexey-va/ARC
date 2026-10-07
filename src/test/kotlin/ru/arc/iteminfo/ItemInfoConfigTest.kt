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
            { it.setInt("chest-preview.max-items", 0) },
            { it.setInt("chest-preview.max-items", 13) },
            { it.setDouble("chest-preview.max-distance", 4.6) },
            { it.setDouble("chest-preview.vertical-gap", 0.51) },
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
