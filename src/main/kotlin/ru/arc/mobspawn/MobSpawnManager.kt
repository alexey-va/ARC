package ru.arc.mobspawn

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.hooks.HookRegistry

/**
 * Production implementation of ClaimChecker using Lands hook.
 */
class LandsClaimChecker : ClaimChecker {
    override fun isClaimed(location: Location): Boolean {
        return HookRegistry.landsHook?.isClaimed(location) ?: false
    }
}

/**
 * Production implementation of EntitySpawner.
 */
class BukkitEntitySpawner : EntitySpawner {
    override fun spawn(location: Location, entityType: EntityType) {
        location.world?.spawnEntity(location, entityType)
    }

    override fun spawnViaCmi(player: Player, entityType: EntityType, amount: Int, spread: Int) {
        val command = "cmi spawnmob ${entityType.name} ${player.name} q:$amount sp:$spread -s"
        ARC.trySeverCommand(command)
    }
}

/**
 * Production implementation of WorldProvider.
 */
class BukkitWorldProvider : WorldProvider {
    override fun getWorlds(): List<World> = Bukkit.getWorlds()
}

/**
 * Static facade for MobSpawnService.
 *
 * Provides Java-compatible static methods for plugin initialization.
 */
object MobSpawnManager {
    private var service: MobSpawnService? = null

    /**
     * Initialize and start the mob spawn service.
     */
    @JvmStatic
    fun init() {
        cancel()

        val config = MobSpawnConfig.load(ARC.plugin.dataPath)

        service = MobSpawnService(
            config = config,
            scheduler = BukkitTaskScheduler(ARC.plugin),
            worldProvider = BukkitWorldProvider(),
            claimChecker = LandsClaimChecker(),
            entitySpawner = BukkitEntitySpawner()
        )

        service?.start()
    }

    /**
     * Initialize with custom service (for testing).
     */
    fun init(customService: MobSpawnService) {
        cancel()
        service = customService
        service?.start()
    }

    /**
     * Cancel the mob spawn service.
     */
    @JvmStatic
    fun cancel() {
        service?.stop()
        service = null
    }

    /**
     * Get current service instance.
     */
    fun getService(): MobSpawnService? = service

    /**
     * Check if service is running.
     */
    @JvmStatic
    fun isRunning(): Boolean = service?.isRunning() ?: false
}


