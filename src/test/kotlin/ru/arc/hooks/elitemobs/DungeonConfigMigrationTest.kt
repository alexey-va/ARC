package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class DungeonConfigMigrationTest :
    FreeSpec({
        afterTest { ConfigManager.clear() }

        "bundled dungeon defaults merge into an existing config without replacing operator values" {
            val directory = Files.createTempDirectory("arc-dungeon-config-migration")
            try {
                Files.writeString(
                    directory.resolve("elitemobs.yml"),
                    """
                    shop:
                      reset-ticks: 1234
                    dungeon-qol:
                      resume-enabled: false
                      messages:
                        entry: operator-message
                    """.trimIndent() + "\n",
                )
                ConfigManager.clear()
                val config = ConfigManager.of(directory, "elitemobs.yml")

                config.mergeMissingFromBundled("modules/elitemobs.yml") shouldBe true
                config.integer("shop.reset-ticks", 6000) shouldBe 1234
                config.bool("dungeon-qol.resume-enabled", true) shouldBe false
                config.string("dungeon-qol.messages.entry") shouldBe "operator-message"
                config.string("dungeon-qol.titles.complete.subtitle").contains("/данж выйти") shouldBe true

                val afterFirstMerge = Files.readString(directory.resolve("elitemobs.yml"))
                config.mergeMissingFromBundled("modules/elitemobs.yml") shouldBe false
                Files.readString(directory.resolve("elitemobs.yml")) shouldBe afterFirstMerge
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
