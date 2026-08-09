package ru.arc.commandhide

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class CommandHideConfigTest :
    FreeSpec({
        "loads module yaml through the standard Config get accessors" {
            val dataPath = Files.createTempDirectory("command-hide-config")
            val moduleDir = Files.createDirectories(dataPath.resolve("modules"))
            Files.writeString(
                moduleDir.resolve("command-hide.yml"),
                """
                enabled: true
                strip-command-namespace: false
                bypass-permission: arc.hide.staff
                policy-cache: 2s
                blocked-message: blocked
                groups:
                  base:
                    commands: ["plugins **"]
                  player:
                    inherits: [base]
                    commands: ["version **"]
                """.trimIndent(),
            )

            val config = CommandHideModuleConfig.load(dataPath)

            config.enabled shouldBe true
            config.stripCommandNamespace shouldBe false
            config.bypassPermission shouldBe "arc.hide.staff"
            config.policyCacheMillis shouldBe 2_000L
            config.groups.map(CommandHideGroupConfig::id) shouldContainExactly listOf("base", "player")
            config.groups.last().inherits shouldContainExactly listOf("base")
            ConfigManager.clear()
        }

        "module exposes a stable lifecycle identity" {
            CommandHideModule.name shouldBe "CommandHide"
            CommandHideModule.priority shouldBe 67
        }
    })
