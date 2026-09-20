package ru.arc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.ArcCommand
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.ConfigManager
import ru.arc.core.ModuleRegistry
import ru.arc.paper.api.ArcSidebarService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.util.Logging
import java.io.File

class IsolatedRuntimeProfileTest : FreeSpec({
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
