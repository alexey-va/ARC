package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class HelpCenterGuideCatalogTest : StringSpec({
    "every current command and guide link belongs to an authored player topic" {
        val topics = HelpCenterGuideCatalog.topics
        topics.map { it.id }.toSet().size shouldBe topics.size
        topics.map { it.category }.toSet() shouldBe HelpCenterCategory.entries.toSet()
        topics.flatMap { it.coveredCommands }.toSet() shouldBe HelpCenterController.catalogCommandIds
        val root = Files.createTempDirectory("help-guide-catalog")
        try {
            ConfigManager.clear()
            val settings = HelpCenterConfig.load(root).snapshot()
            topics.forEach { topic ->
                // Bundled text and portable defaults describe the same public feature.
                settings.guide(topic.id) shouldBe topic.defaults
                topic.actions.forEach { action ->
                    when (action) {
                        is HelpCenterGuideAction.Command -> (action.id in HelpCenterController.catalogCommandIds) shouldBe true
                        is HelpCenterGuideAction.Topic -> (action.id in HelpCenterGuideCatalog.byId) shouldBe true
                        is HelpCenterGuideAction.Page -> settings.text(action.labelKey).isNotBlank() shouldBe true
                        HelpCenterGuideAction.SlimefunMenu -> Unit
                    }
                }
            }
            val entries = HelpCenterGuideCatalog.searchEntries(settings)
            entries.map { it.action }.toSet() shouldBe topics.map { HelpCenterSearchAction.OpenGuide(it.id) }.toSet()
            entries.all { it.command == null } shouldBe true
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "feature explanations can be overridden without changing their action authority" {
        val root = Files.createTempDirectory("help-guide-override")
        try {
            ConfigManager.clear()
            Files.writeString(root.resolve("help-center.yml"), """
                guides:
                  bank:
                    title: 'Личный счёт'
                    description: 'Проверить банковский баланс'
                    body: 'Откройте банк и выберите доступную операцию.'
                    keywords: 'счёт накопления банк'
            """.trimIndent())
            val settings = HelpCenterConfig.load(root).snapshot()
            val entry = HelpCenterGuideCatalog.searchEntries(settings).single { it.id == "guide-bank" }
            entry.label shouldBe "Личный счёт"
            entry.action shouldBe HelpCenterSearchAction.OpenGuide("bank")
            HelpCenterGuideCatalog.byId.getValue("bank").actions.first() shouldBe HelpCenterGuideAction.Command("bank")
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
