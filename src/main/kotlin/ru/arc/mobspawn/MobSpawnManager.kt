package ru.arc.mobspawn

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.Tasks
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
    @Volatile
    private var service: MobSpawnService? = null

    /**
     * Initialize and start the mob spawn service.
     */
    @JvmStatic
    @Synchronized
    fun init() {
        cancel()

        val config = MobSpawnConfig.load(ARC.instance.dataPath)

        val newService =
            MobSpawnService(
                config = config,
                scheduler = Tasks.scheduler,
                worldProvider = BukkitWorldProvider(),
                claimChecker = LandsClaimChecker(),
                entitySpawner = BukkitEntitySpawner(),
            )
        newService.start()
        service = newService
    }

    /**
     * Initialize with custom service (for testing).
     */
    @Synchronized
    internal fun init(customService: MobSpawnService) {
        cancel()
        customService.start()
        service = customService
    }

    /**
     * Cancel the mob spawn service.
     */
    @JvmStatic
    @Synchronized
    fun cancel() {
        val current = service
        service = null
        current?.stop()
    }

    /**
     * Get current service instance.
     */
    internal fun getService(): MobSpawnService? = service

    /**
     * Check if service is running.
     */
    @JvmStatic
    fun isRunning(): Boolean = service?.isRunning() ?: false
}
