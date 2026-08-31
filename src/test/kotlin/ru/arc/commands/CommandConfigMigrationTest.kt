package ru.arc.commands

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class CommandConfigMigrationTest :
    FreeSpec({
        afterTest { ConfigManager.clear() }

        "bundled command defaults merge into an existing config without replacing operator values" {
            val directory = Files.createTempDirectory("arc-command-config-migration")
            try {
                Files.writeString(
                    directory.resolve("commands.yml"),
                    """
                    commands:
                      chat:
                        name: operator-chat
                    messages:
                      common:
                        no-permission: operator-message
                    """.trimIndent() + "\n",
                )
                ConfigManager.clear()
                val config = ConfigManager.of(directory, "commands.yml")

                config.mergeMissingFromBundled("config/commands.yml") shouldBe true
                config.string("commands.chat.name") shouldBe "operator-chat"
                config.string("messages.common.no-permission") shouldBe "operator-message"
                config.string("commands.commandhide.permission") shouldBe "arc.command.hide.admin"
                config.string("messages.commandhide.status-managed").isNotBlank() shouldBe true

                val afterFirstMerge = Files.readString(directory.resolve("commands.yml"))
                config.mergeMissingFromBundled("config/commands.yml") shouldBe false
                Files.readString(directory.resolve("commands.yml")) shouldBe afterFirstMerge
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
    })
