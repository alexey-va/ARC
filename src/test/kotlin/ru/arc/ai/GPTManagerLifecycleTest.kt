package ru.arc.ai

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.ai.config.NpcChatConfig
import ru.arc.ai.config.TestLlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.npc.NpcChatRpcClient
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis

class GPTManagerLifecycleTest {
    @AfterEach
    fun tearDown() {
        GPTManager.shutdown()
    }

    @Test
    fun `shutdown closes owned executor and disables requests`() {
        val scheduler = TestTaskScheduler()
        Tasks.withScheduler(scheduler) {
            val config = TestLlmModuleConfig(apiKey = "none")
            val rpc = NpcChatRpcClient(InMemoryRedis(), config).also { it.start() }
            GPTManager.init(
                llmConfig = config,
                npcChatConfig = mockk<NpcChatConfig>(relaxed = true),
                llmClient = OpenRouterLlmClient.create(config),
                npcChatRpcClient = rpc,
            )

            assertTrue(GPTManager.isRunning())
            assertTrue(GPTManager.hasActiveExecutor())

            GPTManager.shutdown()

            assertFalse(GPTManager.isRunning())
            assertFalse(GPTManager.hasActiveExecutor())
            assertNull(GPTManager.moderationResponse("text").join())
            rpc.close()
        }
    }

    @Test
    fun `reinitialization replaces rather than leaks executor`() {
        val scheduler = TestTaskScheduler()
        Tasks.withScheduler(scheduler) {
            val config = TestLlmModuleConfig(apiKey = "none")
            val npcConfig = mockk<NpcChatConfig>(relaxed = true)
            val client = OpenRouterLlmClient.create(config)
            val rpc = NpcChatRpcClient(InMemoryRedis(), config).also { it.start() }

            GPTManager.init(config, npcConfig, client, rpc)
            GPTManager.init(config, npcConfig, client, rpc)

            assertTrue(GPTManager.isRunning())
            assertTrue(GPTManager.hasActiveExecutor())
            rpc.close()
        }
    }
}
