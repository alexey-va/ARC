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
import ru.arc.gui.ArcMenus
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisManager
import ru.arc.slimefunmenu.SlimefunMenuAliasCommand
import ru.arc.slimefunmenu.SlimefunMenuModule
import ru.arc.util.Logging
import java.io.File
import java.util.concurrent.CompletableFuture

class SlimefunRuntimeProfileTest : FreeSpec({
    beforeEach {
        ModuleRegistry.resetForTests()
        ArcMenus.resetForTests()
    }

    "slimefun profile loads only utility modules, exposes its menu routes and closes cleanly" {
        Logging.disableLokiAppender = true
        Logging.quietMode = true
        Logging.bootstrapForTests()
        ConfigManager.clear()

        MockBukkitTestRuntime.open().use { runtime ->
            val otherBuy = object : Command("buy") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            runtime.server.commandMap.register("other", otherBuy)
            val plugin = runtime.loadPlugin(SlimefunProfilePlugin::class.java)

            plugin.runtimeProfile shouldBe ArcRuntimeProfile.SLIMEFUN
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                setOf("Config", "OpsHttp", "Restart", "ItemInfo", "SlimefunMenu")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ARC.redisManager shouldBe null
            ARC.networkRegistry shouldBe null
            ARC.hookRegistry shouldBe null
            runtime.server.servicesManager.getRegistration(ArcSidebarService::class.java) shouldBe null
            ArcMenus.hasDialogRuntimeForTests() shouldBe true

            runtime.server.commandMap.getCommand("buy") shouldBe otherBuy
            runtime.server.commandMap.getCommand("arc:buy") shouldBe null
            runtime.server.commandMap.getCommand("arc:x") shouldBe null
            runtime.server.commandMap.getCommand("arc:menu") shouldBe runtime.server.commandMap.getCommand("menu")
            runtime.server.commandMap.getCommand("mm") shouldBe runtime.server.commandMap.getCommand("arc:mm")
            (runtime.server.commandMap.getCommand("mm") is SlimefunMenuAliasCommand) shouldBe true
            ArcCommand.INSTANCE.availableSubcommands(runtime.server.consoleSender)
                .map { it.configKey }.toSet() shouldBe setOf("help", "reload", "restart")

            SlimefunMenuModule.ROUTES.associate { it.id to it.invocation } shouldBe mapOf(
                "guide" to "slimefun:slimefun guide",
                "rtp" to "rtp:rtp",
                "homes" to "huskhomes:homes",
                "lands" to "lands:lands",
                "team" to "justteams:team",
                "shop" to "economyshopgui-premium:shop slimefun_resources",
            )

            plugin.reload()
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                setOf("Config", "OpsHttp", "Restart", "ItemInfo", "SlimefunMenu")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ArcMenus.hasDialogRuntimeForTests() shouldBe true

            val oldAlias = runtime.server.commandMap.getCommand("mm")
            (runtime.server.commandMap.knownCommands.values.any { it === oldAlias }) shouldBe true
            runtime.server.pluginManager.disablePlugin(plugin)
            runtime.server.commandMap.getCommand("mm") shouldBe null
            runtime.server.commandMap.getCommand("arc:mm") shouldBe null
            runtime.server.commandMap.knownCommands.values.any { it === oldAlias } shouldBe false
            ArcMenus.hasDialogRuntimeForTests() shouldBe false

            runtime.server.pluginManager.enablePlugin(plugin)
            val reenabledAlias = runtime.server.commandMap.getCommand("mm")
            (reenabledAlias is SlimefunMenuAliasCommand) shouldBe true
            (reenabledAlias === oldAlias) shouldBe false
            runtime.server.commandMap.getCommand("arc:mm") shouldBe reenabledAlias
            ArcMenus.hasDialogRuntimeForTests() shouldBe true
            runtime.server.pluginManager.disablePlugin(plugin)
            runtime.server.commandMap.getCommand("mm") shouldBe null
            runtime.server.commandMap.getCommand("arc:mm") shouldBe null
            runtime.server.commandMap.knownCommands.values.any { it === reenabledAlias } shouldBe false
        }

        ARC.plugin = null
        ConfigManager.clear()
    }

    "slimefun profile retains the existing opt-in Redis glyph guard" {
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
                val plugin = runtime.loadPlugin(SlimefunGlyphProfilePlugin::class.java)
                plugin.runtimeProfile shouldBe ArcRuntimeProfile.SLIMEFUN
                ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                    setOf("Redis", "ChatGlyphProtection", "Config", "OpsHttp", "Restart", "ItemInfo", "SlimefunMenu")
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
                plugin.reload()
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
                ARC.networkRegistry shouldBe null
                ARC.hookRegistry shouldBe null
            }
        } finally {
            unmockkObject(RedisModule)
            ARC.plugin = null
            ARC.redisManager = null
            ConfigManager.clear()
        }
    }
})

open class SlimefunProfilePlugin : ARC() {
    override fun onLoad() {
        File(dataFolder, "modules/runtime.yml").apply {
            parentFile.mkdirs()
            writeText("profile: slimefun\n")
        }
        super.onLoad()
    }
}

open class SlimefunGlyphProfilePlugin : SlimefunProfilePlugin() {
    override fun onLoad() {
        File(dataFolder, "modules/chat-mode.yml").apply {
            parentFile.mkdirs()
            writeText("glyph-protection:\n  isolated-enabled: true\n")
        }
        File(dataFolder, "modules/redis.yml").writeText("enabled: true\nserver-name: slimefun\nmain-server: false\n")
        super.onLoad()
    }
}
