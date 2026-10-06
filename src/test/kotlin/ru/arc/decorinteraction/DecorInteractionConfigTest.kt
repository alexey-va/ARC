package ru.arc.decorinteraction

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class DecorInteractionConfigTest :
    FreeSpec({
        afterTest { ConfigManager.clear() }

        "bundled decor interactions stay disabled by default" {
            val directory = Files.createTempDirectory("arc-decor-interactions-default")
            try {
                DecorInteractionConfig.load(directory).enabled shouldBe false
                Files.exists(directory.resolve("modules/decor-interactions.yml")) shouldBe true
                ConfigManager.of(directory, "modules/decor-interactions.yml").bool("enabled", true) shouldBe false
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

        "merge-forward preserves the node override and is idempotent" {
            val directory = Files.createTempDirectory("arc-decor-interactions-migration")
            try {
                val modules = Files.createDirectories(directory.resolve("modules"))
                val file = modules.resolve("decor-interactions.yml")
                Files.writeString(file, "enabled: true\noperator-note: keep\n")

                DecorInteractionConfig.load(directory).enabled shouldBe true
                val afterFirst = Files.readString(file)
                afterFirst shouldBe "enabled: true\noperator-note: keep\n"

                ConfigManager.of(directory, "modules/decor-interactions.yml")
                    .mergeMissingFromBundled("modules/decor-interactions.yml") shouldBe false
                Files.readString(file) shouldBe afterFirst
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
