package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.event.HandlerList
import org.bukkit.plugin.PluginDescriptionFile
import org.mockbukkit.mockbukkit.MockBukkit
import ru.arc.commands.arc.ArcCommand
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.core.modules.RedisModule
import ru.arc.gui.ArcMenus
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.hooks.HookRegistry
import ru.arc.listeners.ChatListener
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.RedisManager
import ru.arc.redis.ChannelListener
import ru.arc.xserver.playerlist.PlayerManager
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

    "slimefun profile loads utility modules and personal interface settings, exposes its menu routes and closes cleanly" {
        Logging.disableLokiAppender = true
        Logging.quietMode = true
        Logging.bootstrapForTests()
        ConfigManager.clear()

        MockBukkitTestRuntime.open().use { runtime ->
            val otherBuy = object : Command("buy") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            val justTeamsGuild = object : Command("g") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            val justTeamsGuildAlias = object : Command("guild") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            val otherSpawn = object : Command("spawn") {
                override fun execute(sender: CommandSender, label: String, args: Array<String>) = true
            }
            runtime.server.commandMap.register("justteams", justTeamsGuild)
            runtime.server.commandMap.register("justteams", justTeamsGuildAlias)
            runtime.server.commandMap.register("other", otherBuy)
            runtime.server.commandMap.register("other", otherSpawn)
            val plugin = loadSlimefunProfilePlugin(SlimefunProfilePlugin::class.java)

            plugin.runtimeProfile shouldBe ArcRuntimeProfile.SLIMEFUN
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                setOf("Config", "OpsHttp", "Restart", "ItemInfo", "Redis", "ChatMode", "SlimefunNetworkChat", "SlimefunMenu", "Hooks", "HelpCenter")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ARC.redisManager shouldBe null
            ARC.networkRegistry shouldBe null
            ARC.hookRegistry shouldNotBe null
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet().intersect(setOf("Network", "Economy", "Sync")) shouldBe emptySet()
            HandlerList.getRegisteredListeners(plugin).any { it.listener is ChatListener } shouldBe true
            runtime.server.servicesManager.getRegistration(ArcSidebarService::class.java) shouldNotBe null
            ArcMenus.hasDialogRuntimeForTests() shouldBe true
            HelpCenterModule.isAvailable() shouldBe true
            plugin.tablist shouldNotBe null
            HookRegistry.landsHook shouldBe null
            HookRegistry.emHook shouldBe null
            HookRegistry.jobsEnabled shouldBe false
            val interfaceHooks = checkNotNull(ARC.hookRegistry)
            interfaceHooks.chatListener shouldBe null
            interfaceHooks.blockListener shouldBe null

            runtime.server.commandMap.getCommand("buy") shouldBe otherBuy
            runtime.server.commandMap.getCommand("arc:buy") shouldBe null
            runtime.server.commandMap.getCommand("arc:x") shouldBe null
            runtime.server.commandMap.getCommand("arc:menu") shouldBe runtime.server.commandMap.getCommand("menu")
            runtime.server.commandMap.getCommand("skyblock") shouldBe runtime.server.commandMap.getCommand("sb")
            runtime.server.commandMap.getCommand("skyblock") shouldBe runtime.server.commandMap.getCommand("islandmenu")
            runtime.server.commandMap.getCommand("spawn") shouldBe runtime.server.commandMap.getCommand("arc:spawn")
            runtime.server.commandMap.getCommand("mm") shouldBe runtime.server.commandMap.getCommand("arc:mm")
            (runtime.server.commandMap.getCommand("mm") is SlimefunMenuAliasCommand) shouldBe true
            val globalChatCommand = checkNotNull(plugin.getCommand("g"))
            val localChatCommand = checkNotNull(plugin.getCommand("l"))
            runtime.server.commandMap.getCommand("g") shouldBe globalChatCommand
            runtime.server.commandMap.getCommand("l") shouldBe localChatCommand
            runtime.server.commandMap.getCommand("${plugin.name.lowercase()}:g") shouldBe globalChatCommand
            runtime.server.commandMap.getCommand("${plugin.name.lowercase()}:l") shouldBe localChatCommand
            runtime.server.commandMap.getCommand("justteams:g") shouldBe justTeamsGuild
            runtime.server.commandMap.getCommand("guild") shouldBe justTeamsGuildAlias
            ArcCommand.INSTANCE.availableSubcommands(runtime.server.consoleSender)
                .map { it.configKey }.toSet() shouldBe setOf("help", "reload", "restart")

            SlimefunMenuModule.ROUTES.associate { it.id to it.invocation } shouldBe mapOf(
                "guide" to "slimefun:slimefun guide",
                "rtp" to "rtp:rtp",
                "homes" to "huskhomes:homes",
                "team" to "justteams:team",
                "shop" to "economyshopgui-premium:shop slimefun_resources",
            )
            SlimefunMenuModule.islandActionIds(hasIsland = false) shouldBe listOf("island_create", "island_top", "visit")
            SlimefunMenuModule.islandActionIds(hasIsland = true) shouldBe listOf(
                "island_home", "island_manage", "island_team", "island_visitors", "island_settings", "island_biome", "island_top", "visit",
            )
            SlimefunMenuModule.curatedStarterIds(
                configured = listOf("normal", " bogus text", "../../op", "normal", "desert"),
                available = setOf("normal", "desert", "other"),
            ) shouldBe listOf("normal", "desert")
            runtime.server.messenger.getOutgoingChannels(plugin).toSet() shouldBe setOf("bungeecord:main")
            runtime.server.messenger.getIncomingChannels(plugin).toSet() shouldBe emptySet()

            plugin.reload()
            ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                setOf("Config", "OpsHttp", "Restart", "ItemInfo", "Redis", "ChatMode", "SlimefunNetworkChat", "SlimefunMenu", "Hooks", "HelpCenter")
            ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
            ArcMenus.hasDialogRuntimeForTests() shouldBe true

            val oldAlias = runtime.server.commandMap.getCommand("mm")
            (runtime.server.commandMap.knownCommands.values.any { it === oldAlias }) shouldBe true
            runtime.server.pluginManager.disablePlugin(plugin)
            runtime.server.commandMap.getCommand("mm") shouldBe null
            runtime.server.commandMap.getCommand("spawn") shouldBe otherSpawn
            runtime.server.commandMap.getCommand("arc:mm") shouldBe null
            runtime.server.commandMap.knownCommands.values.any { it === oldAlias } shouldBe false
            ArcMenus.hasDialogRuntimeForTests() shouldBe false
            HelpCenterModule.isAvailable() shouldBe false
            interfaceHooks.isClosed shouldBe true
            ARC.hookRegistry shouldBe null
            plugin.tablist shouldBe null

            runtime.server.pluginManager.enablePlugin(plugin)
            val reenabledAlias = runtime.server.commandMap.getCommand("mm")
            (reenabledAlias is SlimefunMenuAliasCommand) shouldBe true
            (reenabledAlias === oldAlias) shouldBe false
            runtime.server.commandMap.getCommand("arc:mm") shouldBe reenabledAlias
            runtime.server.commandMap.getCommand("spawn") shouldBe runtime.server.commandMap.getCommand("arc:spawn")
            ArcMenus.hasDialogRuntimeForTests() shouldBe true
            runtime.server.pluginManager.disablePlugin(plugin)
            runtime.server.commandMap.getCommand("mm") shouldBe null
            runtime.server.commandMap.getCommand("spawn") shouldBe otherSpawn
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
                val plugin = loadSlimefunProfilePlugin(SlimefunGlyphProfilePlugin::class.java)
                plugin.runtimeProfile shouldBe ArcRuntimeProfile.SLIMEFUN
                ModuleRegistry.getRuntimeStatuses().map { it.name }.toSet() shouldBe
                    setOf("Redis", "ChatGlyphProtection", "Config", "OpsHttp", "Restart", "ItemInfo", "ChatMode", "SlimefunNetworkChat", "SlimefunMenu", "Hooks", "HelpCenter")
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
                verify(atLeast = 1) { redis.registerChannelUnique("arc.proxy_player_list", any()) }
                val listeners = mutableListOf<ChannelListener>()
                verify(atLeast = 1) { redis.registerChannelUnique("arc.proxy_player_list", capture(listeners)) }
                listeners.last().consume(
                    "arc.proxy_player_list",
                    """[{"username":"SkyblockQA","server":"slimefun","uuid":"00000000-0000-0000-0000-000000000001","joinTime":1},{"username":"SpawnQA","server":"spawn","uuid":"00000000-0000-0000-0000-000000000002","joinTime":1}]""",
                    "proxy",
                )
                PlayerManager.getPlayerNames() shouldBe setOf("SkyblockQA", "SpawnQA")
                HandlerList.getRegisteredListeners(plugin).any { it.listener is ChatListener } shouldBe true
                plugin.reload()
                ModuleRegistry.getRuntimeStatuses().all { it.ready && it.failures == 0L } shouldBe true
                verify(atLeast = 2) { redis.registerChannelUnique("arc.proxy_player_list", any()) }
                ARC.networkRegistry shouldBe null
                ARC.hookRegistry shouldNotBe null
                runtime.server.pluginManager.disablePlugin(plugin)
                verify(atLeast = 2) { redis.unregisterChannel("arc.proxy_player_list", any()) }
            }
        } finally {
            unmockkObject(RedisModule)
            ARC.plugin = null
            ARC.redisManager = null
            PlayerManager.readMessage("[]")
            ConfigManager.clear()
        }
    }
})

private fun <T : ARC> loadSlimefunProfilePlugin(type: Class<T>): T {
    val descriptor = ARC::class.java.classLoader.getResourceAsStream("plugin.yml")
        ?.use { PluginDescriptionFile(it) }
        ?: error("ARC plugin.yml must be available to the Slimefun profile test")
    return MockBukkit.loadWith(type, descriptor)
}

open class SlimefunProfilePlugin : ARC() {
    override fun onLoad() {
        File(dataFolder, "modules/runtime.yml").apply {
            parentFile.mkdirs()
            writeText("profile: slimefun\n")
        }
        File(dataFolder, "modules/redis.yml").apply {
            parentFile.mkdirs()
            writeText("enabled: false\nserver-name: slimefun\nmain-server: false\n")
        }
        super.onLoad()
    }
}

open class SlimefunGlyphProfilePlugin : SlimefunProfilePlugin() {
    override fun onLoad() {
        super.onLoad()
        File(dataFolder, "modules/chat-mode.yml").apply {
            parentFile.mkdirs()
            writeText("glyph-protection:\n  isolated-enabled: true\n")
        }
        File(dataFolder, "modules/redis.yml").writeText("enabled: true\nserver-name: slimefun\nmain-server: false\n")
    }
}
