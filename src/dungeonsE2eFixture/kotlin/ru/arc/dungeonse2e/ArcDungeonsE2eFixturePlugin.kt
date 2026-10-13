package ru.arc.dungeonse2e

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.dungeons.api.ArcDungeonsService

/** CI-only consumer that verifies the real Paper service and classloader boundary. */
class ArcDungeonsE2eFixturePlugin : JavaPlugin(), CommandExecutor {
    private lateinit var registeredService: ArcDungeonsService

    override fun onEnable() {
        verifyRuntimeState()
        requireNotNull(getCommand("arc-dungeons-e2e-check")).setExecutor(this)
    }

    private fun verifyRuntimeState() {
        val arc = requireEnabledPlugin("ARC")
        val dungeons = requireEnabledPlugin("ArcDungeons") as? JavaPlugin
            ?: error("ArcDungeons must be a JavaPlugin")
        check(server.pluginManager.getPlugin("EliteMobs") == null) {
            "This E2E profile must exercise ArcDungeons without EliteMobs"
        }
        check(server.pluginManager.isPluginEnabled("Parkour")) {
            "This E2E profile must keep the Parkour dependency installed"
        }

        check(ArcDungeonsService::class.java.classLoader === arc.javaClass.classLoader) {
            "ArcDungeonsService must be owned by the ARC API classloader"
        }
        check(ArcDungeonConfiguration::class.java.classLoader === arc.javaClass.classLoader) {
            "ArcDungeonConfiguration must be owned by the ARC API classloader"
        }
        val hostConfiguration = requireNotNull(server.servicesManager.load(ArcDungeonConfiguration::class.java)) {
            "ARC full-profile host configuration service was not registered"
        }
        check(!hostConfiguration.sharedRedisAvailable()) {
            "This profile must verify ArcDungeons when ARC shared Redis is disabled"
        }
        val service = requireNotNull(server.servicesManager.load(ArcDungeonsService::class.java)) {
            "ArcDungeons did not register ArcDungeonsService"
        }
        if (::registeredService.isInitialized) {
            check(service === registeredService) { "ARC reload replaced ArcDungeonsService unexpectedly" }
        } else {
            registeredService = service
        }
        check(ArcDungeonsService::class.java.isInstance(service)) {
            "The registered provider does not implement the shared ArcDungeonsService contract"
        }
        check(service.javaClass.classLoader === dungeons.javaClass.classLoader) {
            "The ArcDungeons service implementation must be owned by the child plugin classloader"
        }
        check(!service.nativeReady()) {
            "Native EliteMobs integration must stay unavailable when EliteMobs is absent"
        }

        for (name in DUNGEON_COMMANDS) {
            val command = requireNotNull(dungeons.getCommand(name)) {
                "ArcDungeons plugin.yml is missing /$name"
            }
            check(command.plugin === dungeons) { "/$name must be owned by ArcDungeons" }
            check(command.executor != null && command.executor !== dungeons) {
                "/$name must have an active ArcDungeons command handler"
            }
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        verifyRuntimeState()
        sender.sendMessage(READY_MESSAGE)
        return true
    }

    private fun requireEnabledPlugin(name: String): Plugin {
        val plugin = requireNotNull(server.pluginManager.getPlugin(name)) { "$name must be installed" }
        check(plugin.isEnabled) { "$name must be enabled before the ArcDungeons E2E fixture" }
        return plugin
    }

    private companion object {
        val DUNGEON_COMMANDS = listOf("dungeon", "dungeonstart", "dungeonsave", "dungeonsaves", "loot")
        const val READY_MESSAGE = "ARC_DUNGEONS_E2E_READY"
    }
}
