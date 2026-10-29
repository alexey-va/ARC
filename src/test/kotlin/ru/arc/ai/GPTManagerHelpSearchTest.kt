package ru.arc.ai

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.chat.ChatCompletionService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import ru.arc.ai.config.NpcChatConfig
import ru.arc.ai.config.TestLlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.npc.NpcChatRpcClient
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GPTManagerHelpSearchTest : StringSpec({
    afterTest { GPTManager.shutdown() }

    "search has bounded separate workers and retires delayed or queued requests on shutdown" {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val message = mockk<ChatCompletionMessage>()
        every { message.content() } returns Optional.of("[]")
        val choice = mockk<ChatCompletion.Choice>()
        every { choice.message() } returns message
        val completion = mockk<ChatCompletion>()
        every { completion.choices() } returns listOf(choice)
        val completions = mockk<ChatCompletionService>()
        every { completions.create(any()) } answers {
            calls.incrementAndGet()
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Synthetic completion was not released" }
            completion
        }
        val chat = mockk<ChatService>()
        every { chat.completions() } returns completions
        val client = mockk<OpenAIClient>()
        every { client.chat() } returns chat
        val llm = mockk<OpenRouterLlmClient>()
        every { llm.enabled } returns true
        every { llm.client } returns client
        val config = TestLlmModuleConfig()
        val npcConfig = mockk<NpcChatConfig>(relaxed = true)
        val rpc = NpcChatRpcClient(InMemoryRedis(), config).also { it.start() }

        try {
            Tasks.withScheduler(TestTaskScheduler()) {
                GPTManager.init(config, npcConfig, llm, rpc)
                GPTManager.hasActiveHelpSearchExecutor() shouldBe true
                val active = List(2) { GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog") }
                started.await(5, TimeUnit.SECONDS) shouldBe true
                val queued = List(2) { GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog") }
                GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog").isCompletedExceptionally shouldBe true
                active.forEach { it.isDone shouldBe false }
                calls.get() shouldBe 2

                GPTManager.shutdown()
                GPTManager.hasActiveHelpSearchExecutor() shouldBe false
                GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog").join() shouldBe null
                release.countDown()
                (active + queued).forEach { it.get(5, TimeUnit.SECONDS) shouldBe null }
                calls.get() shouldBe 2

                GPTManager.init(config, npcConfig, llm, rpc)
                GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog").get(5, TimeUnit.SECONDS) shouldBe "[]"
                calls.get() shouldBe 3
            }
        } finally {
            release.countDown()
            GPTManager.shutdown()
            rpc.close()
        }
    }

    "disabled LLM does not allocate search workers or perform a completion" {
        val config = TestLlmModuleConfig(apiKey = "none")
        val rpc = NpcChatRpcClient(InMemoryRedis(), config).also { it.start() }
        try {
            Tasks.withScheduler(TestTaskScheduler()) {
                GPTManager.init(config, mockk<NpcChatConfig>(relaxed = true), OpenRouterLlmClient.create(config), rpc)
                GPTManager.hasActiveHelpSearchExecutor() shouldBe false
                GPTManager.completeHelpSearch("google/gemini-2.5-flash-lite", 128, "rank", "public catalog").join() shouldBe null
            }
        } finally {
            GPTManager.shutdown()
            rpc.close()
        }
    }
})
