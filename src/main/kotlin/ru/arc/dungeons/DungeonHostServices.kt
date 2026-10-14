package ru.arc.dungeons

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.Portal
import ru.arc.PortalData
import ru.arc.audit.AuditConfig
import ru.arc.common.ServerLocation
import ru.arc.integrations.contracts.contractsService
import ru.arc.dungeons.api.*
import ru.arc.enchanting.admitCreativeBook
import ru.arc.enchanting.applyBookInInventory
import ru.arc.enchanting.readAdvancedMagicDust
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.helpcenter.HelpCenterModule
import ru.arc.hooks.HookRegistry
import ru.arc.ops.ItemPresets
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import ru.arc.util.CooldownManager
import ru.arc.util.ParticleManager
import java.util.UUID

/** Only shared contracts cross the plugin boundary; ARC retains these existing owners. */
object DungeonHostServices {
    fun register(plugin: Plugin) {
        val services = plugin.server.servicesManager
        fun <T : Any> register(type: Class<T>, service: T) =
            services.register(type, service, plugin, ServicePriority.Normal)
        register(ArcDungeonConfiguration::class.java, object : ArcDungeonConfiguration {
            override fun legacyDataPath() = ARC.instance.dataPath
            override fun serverName() = requireNotNull(ARC.serverName)
            override fun sharedRedisAvailable() = ARC.redisManager != null
            override fun sharedRedisGeneration() = ru.arc.core.modules.RedisModule.bindingGeneration
            override fun lostLootSqlSettings(): ArcSqlSettings? = AuditConfig.load().mysql?.let {
                ArcSqlSettings(it.host, it.port, it.database, it.username, it.password,
                    ArcSqlSslMode.valueOf(it.sslMode.name), it.minimumIdle, it.maximumPoolSize,
                    it.connectionTimeoutMs, it.socketTimeoutMs, it.validationTimeoutMs, it.maxLifetimeMs, false)
            }
        })
        register(ArcPlayerSaveService::class.java, ArcPlayerSaveService(ru.arc.sync.SyncManager::registerPeriodicSave))
        register(ArcStaffCooldowns::class.java, object : ArcStaffCooldowns {
            override fun remainingTicks(player: UUID) = CooldownManager.cooldown(player, "staff-spells")
            override fun start(player: UUID, ticks: Long) = CooldownManager.addCooldown(player, "staff-spells", ticks)
            override fun clear(player: UUID) = CooldownManager.clearCooldown(player, "staff-spells")
        })
        register(ArcParticleQueue::class.java, ArcParticleQueue(ParticleManager::queue))
        register(ArcDungeonNavigation::class.java, object : ArcDungeonNavigation {
            override fun openMain(player: Player) = HelpCenterModule.open(player)
            override fun openGuide(player: Player, returnTo: Runnable) = HelpCenterModule.openDungeonsGuide(player, returnTo::run)
            override fun openPortal(player: Player, action: Runnable) {
                Portal(player.uniqueId, PortalData(ownerAction = { action.run() }))
            }
            override fun teleport(player: Player, server: String, world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) =
                HookRegistry.huskHomesHook?.teleport(player, ServerLocation(server, world, x, y, z, yaw, pitch)) == true
            override fun escapeGoesBack(player: UUID) =
                HookRegistry.luckPermsHook?.getCachedMeta(player, MenuEscapeBehavior.META_KEY) != "close"
        })
        register(ArcDungeonItems::class.java, object : ArcDungeonItems {
            override fun resolvePreset(preset: String, amount: Int): List<ItemStack> = ItemPresets.resolveStacks(preset, amount).getOrThrow()
            override fun readMagicDust(item: ItemStack) = readAdvancedMagicDust(item)?.let {
                ArcDungeonItems.MagicDust(it.successPercent, it.lowerDestroy)
            }
            override fun presentAdvancedBook(item: ItemStack, viewer: Player) =
                ru.arc.enchanting.presentAdvancedBook(item, viewer)
            override fun admitCreativeBook(player: Player, book: ItemStack) = ru.arc.enchanting.admitCreativeBook(player, book)
            override fun applyBook(player: Player, targetSlot: Int, expectedTarget: ItemStack, expectedBook: ItemStack,
                result: ItemStack?, bookInventorySlot: Int?) =
                applyBookInInventory(player, targetSlot, expectedTarget, expectedBook, result, bookInventorySlot)
        })
        register(ArcDungeonContracts::class.java, object : ArcDungeonContracts {
            override fun authorizeInstance(blueprintWorld: String, instanceWorld: String) =
                ArcDungeonContracts.Admission.valueOf(contractsService()?.authorizeInstance(blueprintWorld, instanceWorld)?.name ?: "DENIED")
            override fun cancelInstance(instanceWorld: String) { contractsService()?.cancelInstance(instanceWorld) }
            override fun playerAuthorized(instanceWorld: String, player: UUID) = contractsService()?.playerAuthorized(instanceWorld, player)
            override fun runId(instanceWorld: String) = contractsService()?.runId(instanceWorld)
            override fun started(runId: String, world: String, participants: Set<String>, instanceWorld: String) {
                contractsService()?.started(runId, world, participants, instanceWorld)
            }
            override fun completed(runId: String, world: String, participants: Set<String>, instanceWorld: String) {
                contractsService()?.completed(runId, world, participants, instanceWorld)
            }
            override fun resumeRewards(player: UUID) { contractsService()?.resumeRewards(player) }
        })
    }
}

fun dungeonService(): ArcDungeonsService? = Bukkit.getServicesManager().load(ArcDungeonsService::class.java)
