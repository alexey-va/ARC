package ru.arc.helpcenter

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HelpCenterSearchServiceTest : StringSpec({
    val protection = HelpCenterSearchEntry(
        "privat", "Приват", "Управление защитой территории", "земля поселение",
        HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT),
    )
    val home = HelpCenterSearchEntry(
        "home-create", "Сохранить дом", "Сохранить точку возвращения", "дом home",
        HelpCenterSearchAction.CreateHome,
    )
    val entries = listOf(home, protection)

    "AI is disabled by default and local search never calls the provider" {
        HelpCenterSearchOptions().enabled shouldBe false
        HelpCenterSearchService(complete = { _, _, _, _ -> error("Default search must not call AI") }).use { service ->
            val result = service.search(entries, "пириват", 2).join()
            result.mode shouldBe HelpCenterSearchMode.LOCAL
            result.entries shouldContainExactly listOf(protection)
        }
    }

    "AI requests use only compact public catalog text and enforce an input budget" {
        val wordy = home.copy(label = "д".repeat(200), description = "я".repeat(1000), keywords = "private-marker", command = "secret-marker")
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, tokens, prompt, request ->
            tokens shouldBe 128
            (prompt.toByteArray(Charsets.UTF_8).size + request.toByteArray(Charsets.UTF_8).size <= 16000) shouldBe true
            val row = JsonParser.parseString(request).asJsonObject["catalog"].asJsonArray.single().asJsonArray
            row.size() shouldBe 3
            row[1].asString.length shouldBe 64
            row[2].asString.length shouldBe 96
            request.contains("private-marker") shouldBe false
            request.contains("secret-marker") shouldBe false
            CompletableFuture.completedFuture("[]")
        }).use { service -> service.search(listOf(wordy), "обычная фраза", 8).join().mode shouldBe HelpCenterSearchMode.SEMANTIC }

        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> error("Oversized catalog must stay local") }).use { service ->
            val largeCatalog = List(150) { wordy.copy(id = "entry-$it") }
            service.search(largeCatalog, "дом", 8).join().mode shouldBe HelpCenterSearchMode.LEXICAL
        }
    }

    "repeated exact requests reuse only validated IDs against the current entry objects" {
        var calls = 0
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ ->
            calls++
            CompletableFuture.completedFuture("[\"privat\"]")
        }).use { service ->
            service.search(entries, "защита", 2).join().mode shouldBe HelpCenterSearchMode.SEMANTIC
            val fresh = protection.copy(action = HelpCenterSearchAction.OpenPage(HelpCenterPage.ROOT))
            service.search(listOf(home, fresh), "защита", 2).join().entries.single().action shouldBe fresh.action
            calls shouldBe 1
            service.search(entries, "другая фраза", 2).join()
            calls shouldBe 2
        }
    }

    "model and output budget are validated independently from moderation settings" {
        runCatching { HelpCenterSearchOptions(model = " ") }.isFailure shouldBe true
        runCatching { HelpCenterSearchOptions(model = "model\nother") }.isFailure shouldBe true
        runCatching { HelpCenterSearchOptions(maxOutputTokens = 1024) }.isFailure shouldBe true
        HelpCenterSearchOptions(maxOutputTokens = 32).maxOutputTokens shouldBe 32
    }

    "ranks the entire authorized catalog by model-selected meaning without a lexical prefilter" {
        val query = "не пускай чужаков рушить мою постройку"
        HelpCenterPlanner.search(entries, query, 2) shouldBe emptyList()
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { model, tokens, _, request ->
            model shouldBe "google/gemini-2.5-flash-lite"
            tokens shouldBe 128
            val payload = JsonParser.parseString(request).asJsonObject
            payload["query"].asString shouldBe query
            payload["catalog"].asJsonArray.map { it.asJsonArray[0].asString }
                .shouldContainExactly("home-create", "privat")
            payload["catalog"].asJsonArray.forEach { it.asJsonArray.size() shouldBe 3 }
            CompletableFuture.completedFuture("[\"privat\"]")
        }).use { service ->
            val result = service.search(entries, query, 2).get(1, TimeUnit.SECONDS)
            result.entries shouldContainExactly listOf(protection)
            result.mode shouldBe HelpCenterSearchMode.SEMANTIC
            (result.entries.single().action === protection.action) shouldBe true
        }
    }

    "accepts a semantic no-match instead of appending contradictory lexical suggestions" {
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> CompletableFuture.completedFuture("[]") }).use { service ->
            val result = service.search(entries, "дом", 2).join()
            result.mode shouldBe HelpCenterSearchMode.SEMANTIC
            result.entries shouldBe emptyList()
        }
    }

    "accepts only a whole JSON or unlabelled code fence while retaining ID order" {
        listOf(
            "```json\n[\"privat\",\"home-create\"]\n```",
            "```\n[\"privat\",\"home-create\"]\n```",
            " \r\n```json\r\n[\"privat\",\"home-create\"]\r\n```\r\n ",
        ).forEach { response ->
            HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> CompletableFuture.completedFuture(response) }).use { service ->
                val result = service.search(entries, "дом", 2).join()
                result.mode shouldBe HelpCenterSearchMode.SEMANTIC
                result.entries shouldContainExactly listOf(protection, home)
            }
        }
        val ids = listOf("spawn", "rtp", "back", "stuck", "recovery", "loot")
        val catalog = ids.map { protection.copy(id = it) }
        val observedResponse = "```json\n[\"spawn\",\"rtp\",\"back\",\"stuck\",\"recovery\",\"loot\"]\n```"
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> CompletableFuture.completedFuture(observedResponse) }).use { service ->
            val result = service.search(catalog, "помоги вернуться", 8).join()
            result.mode shouldBe HelpCenterSearchMode.SEMANTIC
            result.entries.map { it.id } shouldContainExactly ids
        }
    }

    "fences do not relax JSON ID prose or original response length validation" {
        listOf(
            "```json\n[\"operator-only\"]\n```",
            "```json\n[\"privat\",\"privat\"]\n```",
            "```json\n['privat']\n```",
            "```json\nВот ответ: [\"privat\"]\n```",
            "Вот ответ:\n```json\n[\"privat\"]\n```",
            "```json\n[\"privat\"]\n```\nПояснение",
            "```javascript\n[\"privat\"]\n```",
            "```json\n[\"privat\"]\n```\n```json\n[]\n```",
            "```json\n[\"privat\"]\n[]\n```",
            "```json\n[\"privat\"]",
            " ".repeat(2048) + "```json\n[\"privat\"]\n```",
        ).forEach { response ->
            HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> CompletableFuture.completedFuture(response) }).use { service ->
                val result = service.search(entries, "дом", 2).join()
                result.mode shouldBe HelpCenterSearchMode.LEXICAL
                result.entries shouldContainExactly listOf(home)
            }
        }
    }

    "rejects invented hidden malformed duplicate and excessive IDs as a whole" {
        listOf(
            "[\"privat\",\"operator-only\"]",
            "[\"home-create\",\"home-create\"]",
            "[\"home-create\",\"privat\"]",
            "{\"id\":\"privat\",\"command\":\"op Player\"}",
            "[\"privat\"] trailing",
            "['privat']",
            "[1]",
            "null",
            "[\"" + "x".repeat(4096) + "\"]",
        ).forEach { response ->
            HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> CompletableFuture.completedFuture(response) }).use { service ->
                val result = service.search(entries, "дом", 1).join()
                result.mode shouldBe HelpCenterSearchMode.LEXICAL
                result.entries shouldContainExactly listOf(home)
            }
        }
    }

    "unavailable and failed completions retain spelling-tolerant local results" {
        val completions = listOf(
            CompletableFuture.completedFuture<String?>(null),
            CompletableFuture.failedFuture<String?>(IllegalStateException("synthetic provider failure")),
        )
        completions.forEach { completion ->
            HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> completion }).use { service ->
                val result = service.search(entries, "пириват", 2).join()
                result.mode shouldBe HelpCenterSearchMode.LEXICAL
                result.entries shouldContainExactly listOf(protection)
            }
        }
    }

    "deadline returns local results and a late semantic reply cannot replace them" {
        val completion = CompletableFuture<String?>()
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> completion }, timeoutMillis = 10).use { service ->
            val result = service.search(entries, "дом", 2)
            result.get(5, TimeUnit.SECONDS).mode shouldBe HelpCenterSearchMode.LEXICAL
            completion.isDone shouldBe false
            completion.complete("[\"privat\"]")
            result.join().entries shouldContainExactly listOf(home)
        }
    }

    "closing cancels pending observers and prevents requests in a retired lifecycle" {
        val completion = CompletableFuture<String?>()
        var calls = 0
        val service = HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> calls++; completion })
        val pending = service.search(entries, "дом", 2)
        pending.isDone shouldBe false
        service.close()
        pending.isCancelled shouldBe true
        completion.complete("[\"privat\"]")
        service.search(entries, "дом", 2).isCompletedExceptionally shouldBe true
        calls shouldBe 1
    }

    "empty queries show the bounded catalog without asking the model" {
        HelpCenterSearchService(options = HelpCenterSearchOptions(enabled = true), complete = { _, _, _, _ -> error("Blank query must not call the model") }).use { service ->
            val result = service.search(entries, "   ", 1).join()
            result.mode shouldBe HelpCenterSearchMode.CATALOG
            result.entries shouldContainExactly listOf(home)
        }
    }
})
