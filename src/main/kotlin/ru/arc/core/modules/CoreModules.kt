package ru.arc.core.modules

import net.milkbowl.vault.economy.Economy
import org.bukkit.plugin.RegisteredServiceProvider
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.board.Board
import ru.arc.bschests.PersonalLootManager
import ru.arc.common.locationpools.LocationPoolManager
import ru.arc.common.treasure.TreasurePool
import ru.arc.configs.AuctionConfig
import ru.arc.configs.BoardConfig
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.configs.LocationPoolConfig
import ru.arc.configs.StockConfig
import ru.arc.core.PluginModule
import ru.arc.eliteloot.EliteLootManager
import ru.arc.farm.FarmManager
import ru.arc.hooks.HookRegistry
import ru.arc.leafdecay.LeafDecayManager
import ru.arc.misc.JoinMessages
import ru.arc.mobspawn.MobSpawnManager
import ru.arc.network.NetworkRegistry
import ru.arc.network.RedisManager
import ru.arc.network.repos.RedisRepo
import ru.arc.stock.StockClient
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayerManager
import ru.arc.store.StoreManager
import ru.arc.sync.CMISync
import ru.arc.sync.EmSync
import ru.arc.sync.SkillsSync
import ru.arc.sync.SlimefunSync
import ru.arc.sync.SyncManager
import ru.arc.treasurechests.TreasureHuntManager
import ru.arc.util.CooldownManager
import ru.arc.util.HeadTextureCache
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import ru.arc.xserver.XActionManager
import ru.arc.xserver.announcements.AnnounceManager

// ==================== Priority 10: Core Infrastructure ====================

/**
 * Redis connection module.
 */
object RedisModule : PluginModule {
    override val name = "Redis"
    override val priority = 10

    override fun init() {
        val config = ConfigManager.of(ARC.plugin.dataPath, "misc.yml")
        val ip = config.string("redis.ip", "localhost")
        val port = config.integer("redis.port", 3306)
        val username = config.string("redis.username", "default")
        val password = config.string("redis.password", "")

        if (ARC.redisManager != null) {
            ARC.redisManager.connect(ip, port, username, password)
            info("Reconnected to Redis")
        } else {
            ARC.redisManager = RedisManager(ip, port, username, password)
            info("Connected to Redis")
        }
    }

    override fun reload() = init()

    override fun shutdown() {
        RedisRepo.saveAll()
    }
}

/**
 * Network registry for cross-server communication.
 */
object NetworkModule : PluginModule {
    override val name = "Network"
    override val priority = 15

    override fun init() {
        if (ARC.redisManager == null) return
        ARC.networkRegistry = NetworkRegistry(ARC.redisManager)
        ARC.networkRegistry.init()
    }

    override fun shutdown() {}
}

/**
 * Hook registry for external plugin integrations.
 */
object HooksModule : PluginModule {
    override val name = "Hooks"
    override val priority = 20

    override fun init() {
        ARC.hookRegistry = HookRegistry()
        ARC.hookRegistry.setupHooks()
    }

    override fun shutdown() {
        ARC.hookRegistry.cancelTasks()
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
        ARC.plugin.server.pluginManager.getPlugin("Vault") ?: return
        val rsp: RegisteredServiceProvider<Economy>? =
            ARC.plugin.server.servicesManager.getRegistration(Economy::class.java)
        economy = rsp?.provider
    }

    override fun shutdown() {}
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
        ARC.serverName = ConfigManager
            .of(ARC.plugin.dataPath, "misc.yml")
            .string("redis.server-name", "default")
    }

    override fun reload() = init()
    override fun shutdown() {}
}

/**
 * Location pools configuration.
 */
object LocationPoolModule : PluginModule {
    override val name = "LocationPools"
    override val priority = 35

    override fun init() {
        ARC.plugin.locationPoolConfig = LocationPoolConfig()
        LocationPoolManager.init()
    }

    override fun shutdown() {
        ARC.plugin.locationPoolConfig?.saveLocationPools(true)
        ARC.plugin.locationPoolConfig?.cancelTasks()
    }
}

/**
 * Board (scoreboard) configuration.
 */
object BoardModule : PluginModule {
    override val name = "Board"
    override val priority = 40

    override fun init() {
        ARC.plugin.boardConfig = BoardConfig()
        Board.init()
    }

    override fun shutdown() {}
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

    override fun shutdown() {}
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

    override fun shutdown() {}
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

    override fun shutdown() {}
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

    override fun shutdown() {}
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

    override fun shutdown() {}
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

    override fun shutdown() {}
}

/**
 * Stock market system.
 */
object StockModule : PluginModule {
    override val name = "Stock"
    override val priority = 75

    override fun init() {
        StockConfig.load()
        AuctionConfig.load()
        StockPlayerManager.init()
        StockMarket.init()
    }

    override fun shutdown() {
        StockMarket.cancelTasks()
        StockMarket.saveHistory()
        StockClient.stopClient()
    }
}

/**
 * Store/shop system.
 */
object StoreModule : PluginModule {
    override val name = "Store"
    override val priority = 76

    override fun init() {
        StoreManager.init()
    }

    override fun shutdown() {
        StoreManager.saveAll()
    }
}

/**
 * Treasure pool system.
 */
object TreasureModule : PluginModule {
    override val name = "Treasures"
    override val priority = 77

    override fun init() {
        TreasurePool.loadAllTreasures()
        TreasurePool.startSaveTask()
        TreasureHuntManager.loadTreasureHuntTypes()
    }

    override fun shutdown() {
        TreasureHuntManager.stopAll()
        TreasurePool.cancelSaveTask()
        TreasurePool.saveAllTreasurePools()
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

    override fun shutdown() {}
}

/**
 * Leaf decay acceleration.
 */
object LeafDecayModule : PluginModule {
    override val name = "LeafDecay"
    override val priority = 79

    override fun init() {
        LeafDecayManager.reload()
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
        PersonalLootManager.reload()
        PersonalLootManager.init()
    }

    override fun reload() {
        PersonalLootManager.reload()
    }

    override fun shutdown() {
        PersonalLootManager.shutdown()
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

    override fun shutdown() {}
}

/**
 * Join message customization.
 */
object JoinMessagesModule : PluginModule {
    override val name = "JoinMessages"
    override val priority = 82

    override fun init() {
        JoinMessages.init()
    }

    override fun shutdown() {}
}

// ==================== Priority 90: Building System ====================

/**
 * Auto-build system for structures.
 */
object BuildingModule : PluginModule {
    override val name = "Building"
    override val priority = 90

    override fun init() {
        ru.arc.autobuild.BuildingManager.init()
    }

    override fun shutdown() {
        ru.arc.autobuild.BuildingManager.stopAll()
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
        val config: Config = ConfigManager.of(ARC.plugin.dataPath, "misc.yml")

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

        SyncManager.startSaveAllTasks()
    }

    override fun shutdown() {
        SyncManager.saveAll()
    }
}

