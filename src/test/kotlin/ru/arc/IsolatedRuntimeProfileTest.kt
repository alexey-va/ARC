package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.ArcCommand
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.modules.RedisModule
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.util.Logging
import ru.arc.redis.RedisManager
import java.io.File
import java.util.concurrent.CompletableFuture

class IsolatedRuntimeProfileTest : FreeSpec({
    beforeEach { ModuleRegistry.resetForTests() }

    "isolated glyph protection adds only its Redis connection and guard" {
        Logging.disableLokiAppender = true
        Logging.quietMode = true
        Logging.bootstrapForTests()
        ConfigManager.clear()
        val redis = mockk<RedisManager>(relaxed = true) {
            every { loadMap(any()) } returns CompletableFuture.completedFuture(emptyMap())
        }
        mockkObject(RedisModule)
        every { RedisModule.init() } answers { ARC.redisManager = redis }
        every { RedisModule.reload() } answers { ARC.redisManager = redis }
        try {
            MockBukkitTestRuntime.open().use { runtime ->
                val plugin = runtime.loadPlugin(IsolatedGlyphProfilePlugin::class.java)
                plugin.runtimeProfile shouldBe ArcRuntimeProfile.ISOLATED
                ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                    setOf("Redis", "ChatGlyphProtection", "Config", "OpsHttp", "Restart", "ItemInfo")
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
                ARC.networkRegistry shouldBe null
                ARC.hookRegistry shouldBe null
                runtime.server.servicesManager.getRegistration(ArcSidebarService::class.java) shouldBe null
                ArcCommand.INSTANCE.availableSubcommands(runtime.server.consoleSender)
                    .map { it.configKey }.toSet() shouldBe setOf("help", "reload", "restart")
                plugin.reload()
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            }
        } finally {
            unmockkObject(RedisModule)
            ARC.plugin = null
            ARC.redisManager = null
            ConfigManager.clear()
        }
    }

    "isolated startup and reload register only local operations and preserve unrelated commands" {
        Logging.disableLokiAppender = true
        Logging.quietMode = true
        Logging.bootstrapForTests()
        ConfigManager.clear()
        MockBukkitTestRuntime.open().use { runtime ->
            val otherBuy = object : Command("buy") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            runtime.server.commandMap.register("other", otherBuy)
            val plugin = runtime.loadPlugin(IsolatedProfilePlugin::class.java)
            plugin.runtimeProfile shouldBe ArcRuntimeProfile.ISOLATED
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe setOf("Config", "OpsHttp", "Restart", "ItemInfo")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ARC.redisManager shouldBe null
            ARC.networkRegistry shouldBe null
            ARC.hookRegistry shouldBe null
            runtime.server.servicesManager.getRegistration(ArcSidebarService::class.java) shouldBe null
            runtime.server.commandMap.getCommand("buy") shouldBe otherBuy
            runtime.server.commandMap.getCommand("arc:buy") shouldBe null
            runtime.server.commandMap.getCommand("arc:menu") shouldBe null
            ArcCommand.INSTANCE.availableSubcommands(runtime.server.consoleSender)
                .map { it.configKey }.toSet() shouldBe setOf("help", "reload", "restart")
            plugin.reload()
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe setOf("Config", "OpsHttp", "Restart", "ItemInfo")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ARC.redisManager shouldBe null
            ARC.hookRegistry shouldBe null
        }
        ARC.plugin = null
        ConfigManager.clear()
    }
})

open class IsolatedProfilePlugin : ARC() {
    override fun onLoad() {
        File(dataFolder, "modules/runtime.yml").apply {
            parentFile.mkdirs()
            writeText("profile: isolated\n")
        }
        super.onLoad()
    }
}

open class IsolatedGlyphProfilePlugin : IsolatedProfilePlugin() {
    override fun onLoad() {
        File(dataFolder, "modules/chat-mode.yml").apply {
            parentFile.mkdirs()
            writeText("glyph-protection:\n  isolated-enabled: true\n")
        }
        File(dataFolder, "modules/redis.yml").writeText("enabled: true\nserver-name: slimefun\nmain-server: false\n")
        super.onLoad()
    }
}
