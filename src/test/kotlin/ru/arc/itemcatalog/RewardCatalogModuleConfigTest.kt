package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class RewardCatalogModuleConfigTest : StringSpec({
    "bundled reward catalogue stays disabled and parses its portable schema" {
        val root = Files.createTempDirectory("arc-reward-catalog-config")
        try {
            val settings = RewardCatalogModuleConfig.load(root).snapshot()
            settings.enabled shouldBe false
            settings.title shouldBe "<dark_gray><bold>Награды лутбоксов"
            settings.categories.size shouldBe settings.categories.distinctBy { it.id }.size
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "parses treasure preset and pouch sources with an optional native name" {
        val root = Files.createTempDirectory("arc-reward-catalog-sources")
        try {
            writeConfig(root, """
                enabled: true
                title: '<dark_gray><bold>Награды'
                root-icon: {material: CHEST, custom-model-data: 99}
                categories:
                  common:
                    name: 'Обычные'
                    description: ['Предметы из лутбоксов.']
                    icon: {material: CHEST, custom-model-data: 11}
                    entries:
                      command:
                        name: '<white>Команда'
                        description: ['Описание.']
                        requires: []
                        treasure: {pool: common, id: command}
                        icon: PAPER
                      native:
                        description: []
                        requires: [Slimefun]
                        treasure: {pool: sf, id: item}
                      preset:
                        name: '<white>Пресет'
                        description: []
                        preset: reward_preset
                      pouch:
                        name: '<white>Мешочек'
                        description: []
                        requires: []
                        pouch: reward_pouch
            """.trimIndent())

            val settings = RewardCatalogModuleConfig.load(root).snapshot()
            val category = settings.categories.single()
            category.entries.map { it.id } shouldBe listOf("command", "native", "preset", "pouch")
            settings.rootIcon shouldBe CatalogIconStyle("CHEST", 99)
            category.icon shouldBe CatalogIconStyle("CHEST", 11)
            category.entries.single { it.id == "command" }.icon shouldBe CatalogIconStyle("PAPER")
            category.entries.single { it.id == "native" }.name shouldBe null
            category.entries.single { it.id == "native" }.requires shouldBe listOf("Slimefun")
            category.entries.single { it.id == "preset" }.requires shouldBe emptyList()
            category.entries.single { it.id == "preset" }.source shouldBe RewardCatalogSource.Preset("reward_preset")
            category.entries.single { it.id == "pouch" }.source shouldBe RewardCatalogSource.Pouch("reward_pouch")
            category.entries.single { it.id == "command" }.source shouldBe RewardCatalogSource.Treasure("common", "command")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects unknown keys and multiple reward providers" {
        val root = Files.createTempDirectory("arc-reward-catalog-invalid")
        try {
            writeConfig(root, """
                enabled: true
                title: 'Награды'
                categories:
                  common:
                    name: 'Обычные'
                    description: []
                    icon: CHEST
                    entries:
                      bad:
                        name: 'Плохая награда'
                        description: []
                        requires: []
                        treasure: {pool: common, id: bad}
                        preset: bad
                        extra: true
            """.trimIndent())

            runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects non-canonical custom model map keys" {
        val root = Files.createTempDirectory("arc-reward-catalog-icon-invalid")
        try {
            writeConfig(root, """
                enabled: true
                title: 'Награды'
                categories:
                  common:
                    name: 'Обычные'
                    description: []
                    icon: {material: CHEST, customModelData: 1}
                    entries: {}
            """.trimIndent())

            runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects more than 300 entries in a category" {
        val root = Files.createTempDirectory("arc-reward-catalog-bound")
        try {
            val entries = buildString {
                repeat(301) { index ->
                    appendLine("      e$index:")
                    appendLine("        name: 'Награда $index'")
                    appendLine("        description: []")
                    appendLine("        requires: []")
                    appendLine("        preset: reward_$index")
                }
            }
            writeConfig(root, """
                enabled: true
                title: 'Награды'
                categories:
                  common:
                    name: 'Обычные'
                    description: []
                    icon: CHEST
                    entries:
            """.trimIndent() + "\n" + entries)

            runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun writeConfig(root: java.nio.file.Path, contents: String) {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(modules.resolve("reward-catalog.yml"), contents)
        }
    }
}
