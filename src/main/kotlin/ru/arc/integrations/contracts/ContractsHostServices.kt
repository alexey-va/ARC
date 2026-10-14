package ru.arc.integrations.contracts

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.contracts.api.ArcContractsDungeonLaunch
import ru.arc.dungeons.dungeonService
import ru.arc.hooks.HookRegistry
import java.util.UUID
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.contracts.api.ArcContractsHostConfiguration
import ru.arc.contracts.api.ArcContractsService
import ru.arc.core.modules.RedisModule

/** Shared runtime configuration stays with ARC; contract state belongs to ArcContracts. */
object ContractsHostServices {
    fun register(plugin: Plugin) {
        plugin.server.servicesManager.register(ArcContractsHostConfiguration::class.java,
            object : ArcContractsHostConfiguration {
                override fun legacyDataPath() = ARC.instance.dataPath
                override fun serverName() = requireNotNull(ARC.serverName)
                override fun commandText(key: String, fallback: String) = ru.arc.commands.arc.CommandConfig.get(key, fallback)
                override fun sharedRedisAvailable() = ARC.redisManager != null
                override fun sharedRedisGeneration() = RedisModule.bindingGeneration
            }, plugin, ServicePriority.Normal)
        plugin.server.servicesManager.register(ArcContractsDungeonLaunch::class.java,
            object : ArcContractsDungeonLaunch {
                override fun canLaunch(player: Player, blueprintWorld: String) =
                    dungeonService()?.canLaunchSeasonDungeon(player, blueprintWorld) == true
                override fun launch(player: Player, blueprintWorld: String) {
                    checkNotNull(dungeonService()) { "ArcDungeons is unavailable" }.launchSeasonDungeon(player, blueprintWorld)
                }
                override fun localParticipants(leader: UUID): Set<UUID>? {
                    val parties = HookRegistry.partiesHook ?: return setOf(leader)
                    return parties.localLaunchParticipants(leader)
                }
            }, plugin, ServicePriority.Normal)
    }
}

fun contractsService(): ArcContractsService? = Bukkit.getServicesManager().load(ArcContractsService::class.java)

internal fun requireContractsService(): ArcContractsService =
    checkNotNull(contractsService()) { "ArcContracts is unavailable" }
