package ru.arc.core.modules

import ru.arc.ARC
import ru.arc.ai.GPTManager
import ru.arc.ai.config.LlmConfigBootstrap
import ru.arc.ai.config.LlmModuleConfig
import ru.arc.ai.llm.OpenRouterLlmClient
import ru.arc.ai.tools.PaperAiToolExecutors
import ru.arc.ai.tools.ToolRpcServer
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info

object AiModule : PluginModule {
    override val name = "AI"
    override val priority = 25

    private var toolRpcServer: ToolRpcServer? = null

    override fun init() {
        val dataPath = ARC.instance.dataPath
        LlmConfigBootstrap.mergeLegacyPaper(dataPath)
        val llmConfig = LlmModuleConfig.load(dataPath)
        val llmClient = OpenRouterLlmClient.create(llmConfig)
        GPTManager.init(llmConfig, llmClient)

        val redis = ARC.redisManager ?: return
        val serverName = ARC.serverName ?: ru.arc.redis.RedisModuleConfig.load(dataPath).serverName
        toolRpcServer =
            ToolRpcServer(
                localServerName = serverName,
                redis = redis,
                config = llmConfig,
                executors = PaperAiToolExecutors.all(),
            )
        toolRpcServer!!.start()
        info("AI module ready (LLM enabled={})", llmClient.enabled)
    }

    override fun shutdown() {
        GPTManager.shutdown()
        toolRpcServer = null
    }
}
