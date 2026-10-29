package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class HelpCenterLocalSearchTest : StringSpec({
    "bundled player vocabulary stays available in LOCAL search without an AI call" {
        val directory = Files.createTempDirectory("help-local-search")
        try {
            ConfigManager.clear()
            val settings = HelpCenterConfig.load(directory).snapshot()
            val catalog = HelpCenterConfig.COMMANDS.keys.map { id ->
                val text = settings.command(id)
                HelpCenterSearchEntry(id, text.label, text.description, text.keywords, HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT))
            } + HelpCenterConfig.INTENTS.keys.map { id ->
                val text = settings.intent(id)
                HelpCenterSearchEntry(id, text.label, text.description, text.keywords, HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT))
            }
            settings.searchAi.enabled shouldBe false
            HelpCenterSearchService(settings.searchAi, complete = { _, _, _, _ -> error("LOCAL search must not call AI") }).use { service ->
                fun search(query: String): List<String> {
                    val result = service.search(catalog, query, 32).join()
                    result.mode shouldBe HelpCenterSearchMode.LOCAL
                    return result.entries.map { it.id }
                }

                search("банк").first() shouldBe "bank"
                search("быстрая продажа").first() shouldBe "sell"
                search("заработок") shouldContainAll listOf("jobs")
                search("заработать") shouldContainAll listOf("jobs", "goals")
                search("деньги") shouldContainAll listOf("bank", "jobs", "my", "sell")
                search("подработки").contains("dungeons") shouldBe false
                search("/op") shouldBe emptyList()
            }

            val withoutBank = catalog.filterNot { it.id == "bank" }
            HelpCenterPlanner.search(withoutBank, "банк", 8).map { it.id }.contains("bank") shouldBe false
        } finally {
            ConfigManager.clear()
            directory.toFile().deleteRecursively()
        }
    }

    "joined and split configured names outrank a partial matching word" {
        val generic = HelpCenterSearchEntry(
            "slime", "Слайм Slime", "Обычный моб", "моб слизь", HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT),
        )
        val technology = HelpCenterSearchEntry(
            "slimefun", "Книга технологий", "Открыть справочник", "slimefun слаймфан", HelpCenterSearchAction.OpenPage(HelpCenterPage.TECHNOLOGY),
        )
        val catalog = listOf(generic, technology)
        listOf("слайм фан", "слаймфан", "slime fun", "slimefun", "хочу слайм фан").forEach { query ->
            HelpCenterPlanner.search(catalog, query, 8).first().id shouldBe "slimefun"
        }
        val split = technology.copy(keywords = "slime fun слайм фан")
        HelpCenterPlanner.search(listOf(generic, split), "слаймфан", 8).first().id shouldBe "slimefun"
    }

})
