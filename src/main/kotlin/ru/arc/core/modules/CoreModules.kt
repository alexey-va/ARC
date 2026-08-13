package ru.arc.core.modules

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.RegisteredServiceProvider
import ru.arc.ARC
import ru.arc.TitleInput
import ru.arc.audit.AuditManager
import ru.arc.board.BoardManager
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.config.AuctionConfig
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.LocationPoolConfig
import ru.arc.config.StockConfig
import ru.arc.core.Tasks
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.chat.ChatModeService
import ru.arc.eliteloot.EliteLootManager
import ru.arc.farm.FarmManager
import ru.arc.hooks.HookRegistry
import ru.arc.leafdecay.LeafDecayManager
import ru.arc.misc.JoinMessagesManager
import ru.arc.mobspawn.MobSpawnManager
import ru.arc.network.NetworkRegistry
import ru.arc.config.ArcRedisConfig
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.stock.HistoryManager
import ru.arc.stock.Stock
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayer
import ru.arc.stock.StockPlayerManager
import ru.arc.sync.CMISync
import ru.arc.sync.duels.DuelStatsBridge
import ru.arc.sync.duels.DuelsSync
import ru.arc.sync.EmSync
import ru.arc.sync.SkillsSync
import ru.arc.sync.SlimefunSync
import ru.arc.sync.SyncManager
import ru.arc.treasure.core.Treasures
import ru.arc.treasurechests.HuntFurnitureJanitor
import ru.arc.treasurechests.HuntFurnitureRegistry
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.treasurechests.TreasureHuntRegistry
import ru.arc.util.CooldownManager
import ru.arc.util.HeadTextureCache
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import ru.arc.xserver.XActionManager
import ru.arc.xserver.announcements.AnnounceManager
import ru.arc.bschests.PersonalLootModule as PersonalLoot

// ==================== Priority 10: Core Infrastructure ====================

/**
 * Redis connection module.
 */
object RedisModule : PluginModule {
    override val name = "Redis"
    override val priority = 10

    override fun init() {
        RedisConfigBootstrap.ensure(ARC.instance.dataPath)
        val redis = RedisModuleConfig.load(ARC.instance.dataPath)

        if (!redis.enabled) {
            ARC.redisManager?.close()
            ARC.redisManager = null
            info("Redis disabled — skipping connection (redis.enabled=false)")
            return
        }

        val connection = redis.connection()

        if (ARC.redisManager != null) {
            ARC.redisManager!!.connect(connection)
            info("Reconnected to Redis")
        } else {
            ARC.redisManager =
                RedisManager(
                    connection,
                    ServerIdentity { ARC.serverName ?: redis.serverName },
                )
            info("Connected to Redis")
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        ARC.redisManager?.close()
        ARC.redisManager = null
    }
}

/**
 * Network registry for cross-server communication.
 */
object NetworkModule : PluginModule {
    override val name = "Network"
    override val priority = 15

    override fun init() {
        val redis = ARC.redisManager ?: return
        val registry = NetworkRegistry(redis)
        registry.init()
        ARC.networkRegistry = registry
    }

    override fun shutdown() {
        ARC.networkRegistry?.shutdown()
        ARC.networkRegistry = null
    }
}

/**
 * Hook registry for external plugin integrations.
 */
object HooksModule : PluginModule {
    override val name = "Hooks"
    override val priority = 20

    override fun init() {
        val registry = HookRegistry()
        try {
            registry.setupHooks()
            ARC.hookRegistry = registry
        } catch (failure: Throwable) {
            runCatching { registry.close() }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun reload() {
        ARC.hookRegistry?.setupHooks()
    }

    override fun shutdown() {
        val registry = ARC.hookRegistry
        ARC.hookRegistry = null
        try {
            TitleInput.shutdown()
        } finally {
            registry?.close()
        }
    }
}

/**
 * Vault economy integration.
 */
object EconomyModule : PluginModule {
    override val name = "Economy"
    override val priority = 25

    private var economy: Economy? = null

    @JvmStatic
    fun getEconomy(): Economy? = economy

    override fun init() {
        ARC.instance.server.pluginManager
            .getPlugin("Vault") ?: return
        val rsp: RegisteredServiceProvider<Economy>? =
            ARC.instance.server.servicesManager
                .getRegistration(Economy::class.java)
        economy = rsp?.provider
    }

    override fun shutdown() {
        economy = null
    }
}

// ==================== Priority 30-50: Configuration ====================

/**
 * Server name and base configuration.
 */
object ConfigModule : PluginModule {
    override val name = "Config"
    override val priority = 30

    override fun init() {
        ConfigManager.reloadAll()
        ARC.serverName = ArcRedisConfig.get().serverName
    }

    override fun reload() {
        ARC.serverName = ArcRedisConfig.get().serverName
    }

    override fun shutdown() {}
}

/**
 * Location pools configuration.
 */
object LocationPoolModule : PluginModule {
    override val name = "LocationPools"
    override val priority = 35

    override fun init() {
        ARC.instance.locationPoolConfig = LocationPoolConfig()
        LocationPoolManager.init()
    }

    override fun shutdown() {
        ARC.instance.locationPoolConfig?.saveLocationPools(true)
        ARC.instance.locationPoolConfig?.cancelTasks()
        ARC.instance.locationPoolConfig = null
    }
}

/**
 * Board (scoreboard) configuration.
 */
object BoardModule : PluginModule {
    override val name = "Board"
    override val priority = 40

    override fun init() {
        BoardManager.init()
    }

    override fun shutdown() {
        BoardManager.shutdown()
    }
}

// ==================== Priority 50-70: Core Features ====================

/**
 * Particle effect manager.
 */
object ParticleModule : PluginModule {
    override val name = "Particles"
    override val priority = 50

    override fun init() {
        ParticleManager.setupParticleManager()
    }

    override fun shutdown() {
        ParticleManager.stopTasks()
    }
}

/**
 * Cooldown tracking system.
 */
object CooldownModule : PluginModule {
    override val name = "Cooldowns"
    override val priority = 51

    override fun init() {
        CooldownManager.setupTask(5)
    }

    override fun shutdown() {
        CooldownManager.stop()
    }
}

/**
 * Head texture cache for player heads.
 */
object HeadCacheModule : PluginModule {
    override val name = "HeadCache"
    override val priority = 52

    override fun init() {
        ARC.headTextureCache = HeadTextureCache()
    }

    override fun shutdown() {
        ARC.headTextureCache?.save()
    }
}

/**
 * Audit/logging manager.
 */
object AuditModule : PluginModule {
    override val name = "Audit"
    override val priority = 53

    override fun init() {
        AuditManager.init()
    }

    override fun shutdown() {
        AuditManager.shutdown()
    }
}

// ==================== Priority 70-90: Game Features ====================

/**
 * Farm management system.
 */
object FarmModule : PluginModule {
    override val name = "Farms"
    override val priority = 70

    override fun init() {
        FarmManager.init()
    }

    override fun shutdown() {
        FarmManager.cancelTasks()
    }
}

/**
 * Announcement system.
 */
object AnnounceModule : PluginModule {
    override val name = "Announcements"
    override val priority = 71

    override fun init() {
        AnnounceManager.init()
    }

    override fun reload() {
        AnnounceManager.reload()
    }

    override fun shutdown() {
        AnnounceManager.cancel()
    }
}

/**
 * Cross-server action manager.
 */
object XActionModule : PluginModule {
    override val name = "XActions"
    override val priority = 72

    override fun init() {
        XActionManager.init()
    }

    override fun shutdown() {
        XActionManager.shutdown()
    }
}

/**
 * Stock market system.
 */
object StockModule : PluginModule {
    override val name = "Stock"
    override val priority = 75

    private var scope: CoroutineScope? = null
    private var initialized = false
    private val config by lazy { ConfigManager.of(ARC.instance.dataPath, "stocks/stock.yml") }
    private var updateTask: ScheduledTask? = null
    private var dividendTask: ScheduledTask? = null

    @JvmStatic
    fun isAvailable(): Boolean = initialized

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope?.launch(block = block)
    }

    override fun init() {
        if (ARC.redisManager == null) return
        if (!config.bool("enabled", false)) {
            ru.arc.util.Logging
                .info("Stocks are disabled")
            return
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val activeScope = scope ?: return
        var stockRepository: CachedRepository<Stock>? = null
        var playerRepository: CachedRepository<StockPlayer>? = null

        try {
            StockConfig.load(config)
            AuctionConfig.load()

            stockRepository =
                redisRepo<Stock>(
                id = "stocks",
                storageKey = "arc.stocks",
                updateChannel = "arc.stocks_update",
                scope = activeScope,
            ) {
                loadAllOnStart(true)
                // The market catalog is a small complete mirror used by bulk jobs.
                enableCleanup(false)
                saveInterval(kotlin.time.Duration.parse("1s"))
            }

            playerRepository =
                redisRepo<StockPlayer>(
                id = "stock_players",
                storageKey = "arc.stock_players",
                updateChannel = "arc.stock_players_update",
                scope = activeScope,
            ) {
                loadAllOnStart(true)
                // Margin calls and dividends must include offline positions too.
                enableCleanup(false)
                saveInterval(kotlin.time.Duration.parse("250ms"))
            }

            StockMarket.stockRepo = stockRepository
            StockPlayerManager.playerRepo = playerRepository

            HistoryManager.init()

            updateTask =
                repeating(200.ticks, delay = 20.ticks) {
                    activeScope.launch { StockMarket.updateStocks() }
                }
            dividendTask =
                repeating(20.ticks, delay = 100.ticks) {
                    StockMarket.payDividends()
                }

            initialized = true
        } catch (e: Exception) {
            updateTask?.cancel()
            dividendTask?.cancel()
            updateTask = null
            dividendTask = null
            HistoryManager.cancelTasks()
            runBlocking {
                playerRepository?.shutdown()
                stockRepository?.shutdown()
            }
            activeScope.cancel()
            scope = null
            StockMarket.closeClient()
            throw e
        }
    }

    override fun shutdown() {
        if (!initialized) return
        initialized = false

        updateTask?.let { if (!it.isCancelled) it.cancel() }
        dividendTask?.let { if (!it.isCancelled) it.cancel() }
        updateTask = null
        dividendTask = null
        HistoryManager.cancelTasks()

        StockMarket.saveHistory()
        runBlocking { StockMarket.stockRepo.shutdown() }
        runBlocking { StockPlayerManager.playerRepo.shutdown() }
        StockMarket.closeClient()
        scope?.cancel()
        scope = null
    }
}

/**
 * Store/shop system.
 */
object StoreModule : PluginModule {
    override val name = "Store"
    override val priority = 76

    override fun init() {
        ru.arc.store.StoreManager
            .init()
    }

    override fun shutdown() {
        ru.arc.store.StoreManager
            .shutdown()
    }
}

/**
 * Treasure pool system.
 */
object TreasureModule : PluginModule {
    override val name = "Treasures"
    override val priority = 77

    override fun init() {
        Treasures.init()
        TreasureHuntRegistry.init()
        HuntFurnitureRegistry.init()
        HuntFurnitureJanitor.init(Tasks.scheduler)
    }

    override fun reload() {
        Treasures.reload()
        TreasureHuntManager.loadTreasureHuntTypes()
    }

    override fun shutdown() {
        HuntFurnitureJanitor.shutdown()
        TreasureHuntManager.stopAll()
        Treasures.shutdown()
    }
}

/**
 * Elite loot drops.
 */
object EliteLootModule : PluginModule {
    override val name = "EliteLoot"
    override val priority = 78

    override fun init() {
        EliteLootManager.init()
    }

    override fun shutdown() {
        EliteLootManager.shutdown()
    }
}

/**
 * Leaf decay acceleration.
 */
object LeafDecayModule : PluginModule {
    override val name = "LeafDecay"
    override val priority = 79

    override fun init() {
        LeafDecayManager.init()
    }

    override fun reload() {
        LeafDecayManager.reload()
    }

    override fun shutdown() {
        LeafDecayManager.cancel()
    }
}

/**
 * Personal loot/chest system.
 */
object PersonalLootModule : PluginModule {
    override val name = "PersonalLoot"
    override val priority = 80

    override fun init() {
        PersonalLoot.init()
    }

    override fun reload() {
        PersonalLoot.reload()
    }

    override fun shutdown() {
        PersonalLoot.shutdown()
    }
}

/**
 * Mob spawn customization.
 */
object MobSpawnModule : PluginModule {
    override val name = "MobSpawn"
    override val priority = 81

    override fun init() {
        MobSpawnManager.init()
    }

    override fun shutdown() {
        MobSpawnManager.cancel()
    }
}

/**
 * Join message customization.
 */
object JoinMessagesModule : PluginModule {
    override val name = "JoinMessages"
    override val priority = 82

    override fun init() {
        JoinMessagesManager.init()
    }

    override fun shutdown() {
        JoinMessagesManager.shutdown()
    }
}

object ChatModeModule : PluginModule {
    override val name = "ChatMode"
    override val priority = 66

    override fun init() {
        ChatModeService.init()
    }

    override fun shutdown() {
        ChatModeService.shutdown()
    }

    override fun reload() {}
}

// ==================== Priority 90: Building System ====================

/**
 * Auto-build system for structures.
 */
object BuildingModule : PluginModule {
    override val name = "Building"
    override val priority = 90

    override fun init() {
        ru.arc.autobuild.BuildingManager
            .init()
    }

    override fun shutdown() {
        ru.arc.autobuild.BuildingManager
            .stopAll()
    }
}

// ==================== Priority 100: Sync Systems ====================

/**
 * Data synchronization with other plugins.
 */
object SyncModule : PluginModule {
    override val name = "Sync"
    override val priority = 100

    override fun init() {
        val config: Config = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

        if (HookRegistry.sfHook != null && config.bool("sync.slimefun", true)) {
            info("Starting slimefun sync")
            SyncManager.registerSync(SlimefunSync::class.java, SlimefunSync())
        }

        if (HookRegistry.emHook != null && config.bool("sync.em", true)) {
            info("Starting EM sync")
            SyncManager.registerSync(EmSync::class.java, EmSync())
        }

        if (HookRegistry.cmiHook != null && config.bool("sync.cmi", false)) {
            info("Starting CMI sync")
            SyncManager.registerSync(CMISync::class.java, CMISync())
        }

        if (HookRegistry.auraSkillsHook != null && config.bool("sync.aura-skills", true)) {
            info("Starting AuraSkills sync")
            SyncManager.registerSync(SkillsSync::class.java, SkillsSync())
        }

        if (config.bool("sync.duels", true)) {
            DuelsSync.createOrNull()?.let { sync ->
                info("Starting RusCrafting Duels stats sync")
                SyncManager.registerSync(DuelsSync::class.java, sync)
                DuelStatsBridge.install(sync)
            }
        }

        SyncManager.startSaveAllTasks()
    }

    override fun shutdown() {
        DuelStatsBridge.uninstall()
        SyncManager.shutdown()
    }
}
