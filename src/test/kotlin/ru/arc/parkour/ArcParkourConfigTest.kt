package ru.arc.parkour

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.ConfigManager
import java.nio.file.Files

class ArcParkourConfigTest : StringSpec({
    "bundled presentation is fail-closed and resource-pack-neutral" {
        val root = Files.createTempDirectory("arc-parkour-config")
        try {
            ConfigManager.clear()
            val settings = ArcParkourConfig.load(root).snapshot()

            settings.enabled shouldBe false
            settings.interceptJoinAllCommand shouldBe true
            settings.interceptLegacyMenu shouldBe true
            settings.joinAllPermission shouldBe "parkour.basic.joinall"
            settings.legacyMenuTitle shouldBe "Трассы паркура"
            settings.hudEnabled shouldBe true
            settings.categories.map { it.id } shouldContainExactly listOf("easy", "medium", "hard", "extreme")
            settings.categories.flatMap { it.prefixes } shouldContainExactly listOf("easy", "med", "diff", "ex")
            settings.categories.first().courseIcons shouldContainExactly
                listOf(Material.FEATHER, Material.RABBIT_FOOT, Material.SLIME_BALL, Material.HONEYCOMB, Material.SUGAR, Material.WIND_CHARGE, Material.FIREWORK_ROCKET)
            settings.background.material shouldBe Material.GRAY_STAINED_GLASS_PANE
            settings.background.modelData shouldBe null
            settings.back.modelData shouldBe null
            settings.gui.string("copy.action-start", "") shouldBe
                "<color:#8c8c8c>[<color:#92bed8>▶<color:#8c8c8c>] <color:#92bed8>Нажмите<color:#f2f0e6> — начать трассу"
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "runtime mirror can enable presentation without replacing the bundled GUI" {
        val root = Files.createTempDirectory("arc-parkour-enabled")
        try {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(modules.resolve("parkour.yml"), "enabled: true\n")
            ConfigManager.clear()

            val settings = ArcParkourConfig.load(root).snapshot()

            settings.enabled shouldBe true
            settings.categories.size shouldBe 4
            settings.hudEnabled shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "invalid category prefixes fail closed" {
        val root = Files.createTempDirectory("arc-parkour-invalid")
        try {
            val guis = Files.createDirectories(root.resolve("guis"))
            Files.writeString(
                guis.resolve("parkour.yml"),
                """
                categories:
                  easy:
                    prefixes: [easy]
                    description: [safe]
                  duplicate:
                    prefixes: [easy]
                    description: [unsafe]
                """.trimIndent(),
            )
            ConfigManager.clear()

            runCatching { ArcParkourConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
