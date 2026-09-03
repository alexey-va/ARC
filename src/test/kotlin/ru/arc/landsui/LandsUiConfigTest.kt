package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class LandsUiConfigTest : StringSpec({
    "bundled dialog is enabled and explains automatic settlement selection" {
        val root = Files.createTempDirectory("arc-lands-ui-config")
        try {
            ConfigManager.clear()
            val settings = LandsUiConfig.load(root).snapshot()

            settings.enabled shouldBe true
            settings.maxListedPlayers shouldBe 12
            settings.text("guide-body").contains("/lands edit НАЗВАНИЕ") shouldBe true
            settings.text("guide-body").contains("/lands land delete") shouldBe true
            settings.text("guide-body").contains("выбирается автоматически") shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "rejects an unbounded online-player list" {
        val root = Files.createTempDirectory("arc-lands-ui-invalid")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(modules.resolve("lands-ui.yml"), "limits:\n  max-listed-players: 1000\n")
            ConfigManager.clear()

            runCatching { LandsUiConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
