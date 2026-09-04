package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.withClue
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import ru.arc.config.ConfigManager
import java.nio.file.Files

class LandsUiConfigTest : StringSpec({
    "bundled dialog is enabled and explains explicit settlement selection" {
        val root = Files.createTempDirectory("arc-lands-ui-config")
        try {
            ConfigManager.clear()
            val settings = LandsUiConfig.load(root).snapshot()

            settings.enabled shouldBe true
            settings.maxListedPlayers shouldBe 12
            settings.text("guide-commands-body").contains("/lands edit НАЗВАНИЕ") shouldBe true
            settings.text("guide-commands-body").contains("/lands land delete") shouldBe true
            settings.text("guide-body").contains("текущее поселение", ignoreCase = true) shouldBe true
            settings.text("created-body").contains("первый чанк") shouldBe true
            listOf(
                "land-label",
                "land-selected-label",
                "create-label",
                "guide-label",
                "help-label",
                "open-lands-label",
                "members-label",
                "territory-label",
                "rename-label",
                "created-claim-label",
                "created-details-label",
                "add-member-label",
                "member-label",
                "candidate-label",
                "claim-label",
                "unclaim-label",
                "setspawn-label",
                "spawn-label",
                "areas-label",
                "mainblock-label",
                "guide-create-label",
                "guide-expand-label",
                "guide-members-label",
                "guide-commands-label",
                "root-title",
                "details-title",
                "create-title",
                "created-title",
                "members-title",
                "territory-title",
                "mainblock-title",
            ).forEach { key ->
                MiniMessage.miniMessage().deserialize(settings.text(key)).containsBold() shouldBe false
            }
            withClue(settings.text("land-label")) {
                settings.text("land-label").contains("#4dd8f0") shouldBe true
            }
            withClue(settings.text("land-selected-label")) {
                settings.text("land-selected-label").contains("#5ee39c") shouldBe true
            }
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

private fun Component.containsBold(): Boolean =
    decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE || children().any(Component::containsBold)
