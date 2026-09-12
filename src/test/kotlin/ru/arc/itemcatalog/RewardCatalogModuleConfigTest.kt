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
            settings.title shouldBe "<gold><bold>Сокровищница наград"
            settings.categories.size shouldBe settings.categories.distinctBy { it.id }.size
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "missing presentation keys use defaults without disabling the catalogue" {
        val root = Files.createTempDirectory("arc-reward-catalog-defaults")
        try {
            writeConfig(root, """
                enabled: true
                categories:
                  common:
                    entries:
                      reward:
                        preset: reward_preset
            """.trimIndent())

            val settings = RewardCatalogModuleConfig.load(root).snapshot()
            settings.enabled shouldBe true
            settings.title shouldBe "<gold><bold>Сокровищница наград"
            settings.categories.single().apply {
                name shouldBe "common"
                description shouldBe emptyList()
                icon shouldBe CatalogIconStyle("CHEST")
                entries.single().description shouldBe emptyList()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "parses reward sources with optional presentation fields and tolerant defaults" {
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
                        preview-itemsadder: iageneric:coin
                        future-presentation-field: ignored
                      pouch:
                        name: '<white>Мешочек'
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
            category.entries.single { it.id == "preset" }.previewItemsAdder shouldBe "iageneric:coin"
            category.entries.single { it.id == "pouch" }.source shouldBe RewardCatalogSource.Pouch("reward_pouch")
            category.entries.single { it.id == "pouch" }.description shouldBe emptyList()
            category.entries.single { it.id == "pouch" }.previewItemsAdder shouldBe null
            category.entries.single { it.id == "command" }.source shouldBe RewardCatalogSource.Treasure("common", "command")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "parses a namespaced ItemsAdder preview without making it a reward source" {
        val root = Files.createTempDirectory("arc-reward-catalog-preview")
        try {
            writeConfig(root, """
                enabled: true
                title: 'Награды'
                categories:
                  common:
                    name: 'Обычные'
                    description: []
                    icon: PAPER
                    entries:
                      coin:
                        description: []
                        treasure: {pool: common, id: coin}
                        preview-itemsadder: iageneric:coin
            """.trimIndent())

            val entry = RewardCatalogModuleConfig.load(root).snapshot().categories.single().entries.single()
            entry.source shouldBe RewardCatalogSource.Treasure("common", "coin")
            entry.previewItemsAdder shouldBe "iageneric:coin"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "ignores an invalid optional ItemsAdder preview id" {
        val root = Files.createTempDirectory("arc-reward-catalog-preview-invalid")
        try {
            writeConfig(root, """
                enabled: true
                title: 'Награды'
                categories:
                  common:
                    name: 'Обычные'
                    description: []
                    icon: PAPER
                    entries:
                      coin:
                        description: []
                        treasure: {pool: common, id: coin}
                        preview-itemsadder: coin
            """.trimIndent())

            RewardCatalogModuleConfig.load(root).snapshot().categories.single().entries.single().previewItemsAdder shouldBe null
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "ignores extension fields but rejects multiple reward providers" {
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
                        future-presentation-field: ignored
            """.trimIndent())

            runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "uses the default model when an unknown presentation key is present" {
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

            RewardCatalogModuleConfig.load(root).snapshot().categories.single().icon.customModelData shouldBe 0
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects more than the bounded category entry limit" {
        val root = Files.createTempDirectory("arc-reward-catalog-bound")
        try {
            val entries = buildString {
                repeat(RewardCatalogModuleConfig.MAX_ENTRIES_PER_CATEGORY + 1) { index ->
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

    "nested case weights describe one outcome and seals reference an equipment collection" {
        val root = Files.createTempDirectory("arc-reward-cases")
        try {
            writeConfig(root, caseConfig())
            val settings = RewardCatalogModuleConfig.load(root).snapshot()
            settings.children(null).map { it.id } shouldBe listOf("cases", "set_sun")
            val case = settings.children("cases").single()
            case.rolls shouldBe 1
            case.entries.map(case::chance) shouldBe listOf("87,5%", "12,5%")
            case.entries.last().source shouldBe RewardCatalogSource.Seal("set_sun")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "full furniture packages are bounded and every grantable reward needs a case" {
        val root = Files.createTempDirectory("arc-reward-packages")
        try {
            val valid = """
                enabled: true
                title: 'Награды'
                require-case-coverage: true
                packages:
                  forge:
                    name: '<gold>Кузница'
                    items: ['forge:anvil', 'forge:table']
                categories:
                  furniture:
                    name: 'Мебель'
                    description: []
                    icon: CHEST
                    entries:
                      forge:
                        description: []
                        package: forge
                  case_furniture:
                    name: 'Кейс мебели'
                    description: []
                    icon: CHEST
                    rolls: 1
                    entries:
                      forge:
                        description: []
                        package: forge
                        weight: 10000
            """.trimIndent()
            writeConfig(root, valid)
            val loaded = RewardCatalogModuleConfig.load(root).snapshot()
            loaded.packages.getValue("forge").items shouldBe listOf("forge:anvil", "forge:table")
            loaded.uncoveredRewards() shouldBe emptyList()
            for (invalid in listOf(
                valid.replace("package: forge", "package: absent"),
                valid.replace("'forge:table'", "'forge:anvil'"),
                valid.substringBefore("  case_furniture:"),
            )) {
                writeConfig(root, invalid)
                runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "rejects broken case hierarchy and ambiguous odds before replacing the active catalogue" {
        val root = Files.createTempDirectory("arc-reward-case-invalid")
        try {
            val valid = caseConfig()
            listOf(
                valid.replace("parent: cases", "parent: missing"),
                valid.replace("parent: cases", "parent: summer"),
                valid.replace("rolls: 1", "rolls: 2"),
                valid.replace("weight: 7", "weight: 0"),
                valid.replace("weight: 7", "unused: 7"),
                valid.replace("rolls: 1", ""),
                valid.replace("seal: set_sun", "seal: missing"),
                valid.replace("treasure: {pool: sun, id: sword}", "preset: sword"),
                valid.replace("name: 'Кейсы'", "name: 'Кейсы'\n    rolls: 1"),
            ).forEach { invalid ->
                writeConfig(root, invalid)
                runCatching { RewardCatalogModuleConfig.load(root).snapshot() }.isFailure shouldBe true
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun caseConfig() = """
            enabled: true
            title: 'Награды'
            categories:
              cases:
                name: 'Кейсы'
                description: []
                icon: CHEST
                entries: {}
              summer:
                parent: cases
                rolls: 1
                name: 'Летний кейс'
                description: ['Одна награда из списка.']
                icon: CHEST
                entries:
                  supply:
                    description: []
                    preset: summer_supply
                    weight: 7
                  seal:
                    description: []
                    seal: set_sun
                    weight: 1
              set_sun:
                name: 'Солнечный сет'
                description: []
                icon: DIAMOND_SWORD
                entries:
                  sword:
                    description: []
                    treasure: {pool: sun, id: sword}
        """.trimIndent()

        private fun writeConfig(root: java.nio.file.Path, contents: String) {
            val modules = Files.createDirectories(root.resolve("modules"))
            Files.writeString(modules.resolve("reward-catalog.yml"), contents)
            ru.arc.config.ConfigManager.ofModule(root, "reward-catalog.yml").load()
        }
    }
}
