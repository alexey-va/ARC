package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class HelpCenterConfigTest : StringSpec({
    "bundled help center is enabled and keeps player-facing text in yaml" {
        val root = Files.createTempDirectory("arc-help-center-config")
        try {
            ConfigManager.clear()
            val settings = HelpCenterConfig.load(root).snapshot()

            settings.enabled shouldBe true
            settings.maxHomes shouldBe 12
            settings.maxSearchResults shouldBe 8
            settings.text("root-title").contains("Помощь") shouldBe true
            settings.text("travel-body").contains("<homes>") shouldBe true
            settings.command("privat").label.contains("Приват") shouldBe true
            settings.command("privat").keywords.contains("посел") shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "rejects unbounded dynamic lists" {
        val root = Files.createTempDirectory("arc-help-center-invalid")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(
                modules.resolve("help-center.yml"),
                "limits:\n  max-homes: 200\n  max-search-results: 200\n",
            )
            ConfigManager.clear()

            runCatching { HelpCenterConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
