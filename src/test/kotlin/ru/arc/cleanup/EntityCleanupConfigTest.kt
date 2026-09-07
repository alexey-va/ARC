package ru.arc.cleanup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.ConfigManager
import java.nio.file.Files

class EntityCleanupConfigTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "merge preserves operator overrides, exclusions, unknown settings and explicit empty lists" {
        val root = Files.createTempDirectory("cleanup-config")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            val file = modules.resolve("entity-cleanup.yml")
            Files.writeString(file, """
                enabled: true
                future-rule: preserved
                rules:
                  mob-equipment:
                    lifetime-ticks: 800
                    spawn-reasons: []
                    worlds:
                      include: [farm]
                      exclude: [farm]
                    lifetime-overrides:
                      BOW: 400
            """.trimIndent())
            val settings = EntityCleanupConfig.load(root).settings
            settings.enabled shouldBe true
            settings.equipment.lifetime(Material.BOW) shouldBe 400
            settings.equipment.lifetime(Material.IRON_SWORD) shouldBe 800
            settings.equipment.includesWorld("farm") shouldBe false
            settings.equipment.spawnReasons shouldBe emptySet()
            ConfigManager.ofModule(root, "entity-cleanup.yml").string("future-rule") shouldBe "preserved"
            ConfigManager.ofModule(root, "entity-cleanup.yml").mergeMissingFromBundled("modules/entity-cleanup.yml") shouldBe false
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "unsafe TTL, players and typo material are rejected rather than broadening the rule" {
        for (patch in listOf("lifetime-ticks: 0", "entity-types: [PLAYER]", "materials: [IRON_SWRD]")) {
            val root = Files.createTempDirectory("cleanup-invalid")
            try {
                val modules = Files.createDirectories(root.resolve("modules"))
                Files.writeString(modules.resolve("entity-cleanup.yml"), "rules:\n  mob-equipment:\n    $patch\n")
                shouldThrow<IllegalArgumentException> { EntityCleanupConfig.load(root).settings }
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }
    }
})
