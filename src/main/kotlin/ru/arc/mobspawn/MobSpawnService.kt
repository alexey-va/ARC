package ru.arc.mobspawn

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import ru.arc.common.WeightedRandom
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.util.Logging.debug

/**
 * Interface for checking if a location is claimed.
 */
interface ClaimChecker {
    fun isClaimed(location: Location): Boolean
}

/**
 * Interface for spawning entities.
 */
interface EntitySpawner {
    fun spawn(location: Location, entityType: EntityType)
    fun spawnViaCmi(player: Player, entityType: EntityType, amount: Int, spread: Int)
}

/**
 * Interface for getting worlds and players.
 */
interface WorldProvider {
    fun getWorlds(): List<World>
}

/**
 * Result of a spawn attempt.
 */
data class SpawnResult(
    val spawned: Int,
    val skipped: Boolean = false,
    val reason: String? = null
)

/**
 * Core service for mob spawning logic.
 *
 * Fully testable through dependency injection.
 */
class MobSpawnService(
    private val config: MobSpawnConfig,
    private val scheduler: TaskScheduler,
    private val worldProvider: WorldProvider,
    private val claimChecker: ClaimChecker,
    private val entitySpawner: EntitySpawner,
    private val random: () -> Double = { Math.random() }
) {
    private var task: ScheduledTask? = null
    private val mobPicker: WeightedRandom<EntityType> = config.createMobPicker()
    private val trackedMobTypes: Set<EntityType> = config.mobWeights.keys

    /**
     * Start the mob spawn task.
     */
    fun start() {
        stop()

        if (!config.enabled) {
            debug("MobSpawn disabled in config")
            return
        }

        if (mobPicker.size() == 0) {
            debug("No mobs configured for spawning")
            return
        }

        task = scheduler.runTimer(0L, config.intervalTicks) {
            runSpawnCycle()
        }

        debug("MobSpawn service started with {} mob types", trackedMobTypes.size)
    }

    /**
     * Stop the mob spawn task.
     */
    fun stop() {
        task?.cancel()
        task = null
    }

    /**
     * Run a single spawn cycle for all worlds.
     */
    fun runSpawnCycle() {
        for (world in worldProvider.getWorlds()) {
            if (!config.worlds.contains(world.name)) continue
            if (!config.isSpawnTime(world.time)) continue

            processWorld(world)
        }
    }

    /**
     * Process spawning for a single world.
     */
    fun processWorld(world: World) {
        debug("Processing world {} for mob spawns", world.name)

        var totalSpawned = 0
        for (player in world.players) {
            val result = trySpawnNear(player)
            totalSpawned += result.spawned
        }

        if (totalSpawned > 0) {
            debug("Spawned {} mobs in world {}", totalSpawned, world.name)
        }
    }

    /**
     * Try to spawn mobs near a player.
     *
     * @return SpawnResult with count of spawned mobs
     */
    fun trySpawnNear(player: Player): SpawnResult {
        // Check biome
        if (player.location.block.biome == Biome.MUSHROOM_FIELDS) {
            return SpawnResult(0, skipped = true, reason = "mushroom_fields")
        }

        // Check claim
        if (claimChecker.isClaimed(player.location)) {
            return SpawnResult(0, skipped = true, reason = "claimed")
        }

        // Check player state
        if (player.isFlying || player.gameMode != GameMode.SURVIVAL) {
            return SpawnResult(0, skipped = true, reason = "player_state")
        }

        // Count nearby mobs
        val nearbyMobs = countNearbyMobs(player)
        debug("Found {} nearby mobs for {}", nearbyMobs, player.name)

        if (nearbyMobs >= config.threshold) {
            return SpawnResult(0, skipped = true, reason = "threshold_reached")
        }

        // Calculate spawn amount
        val amount = minOf(config.threshold - nearbyMobs, config.amount)
        debug("Will spawn up to {} mobs near {}", amount, player.name)

        return if (config.useCmiCommand) {
            spawnViaCmi(player, amount)
        } else {
            spawnNaturally(player, amount)
        }
    }

    /**
     * Count nearby mobs of tracked types.
     */
    fun countNearbyMobs(player: Player): Int {
        val radius = config.radius
        return player.getNearbyEntities(radius, radius, radius)
            .count { it.type in trackedMobTypes }
    }

    /**
     * Spawn mobs using CMI command.
     */
    private fun spawnViaCmi(player: Player, amount: Int): SpawnResult {
        // Check light level
        if (player.location.block.lightLevel > config.maxLightLevel) {
            debug("Light level too high for mob spawn near {}", player.name)
            return SpawnResult(0, skipped = true, reason = "light_level")
        }

        // Group mobs by type
        val mobCounts = mutableMapOf<EntityType, Int>()
        repeat(amount) {
            val mob = mobPicker.random() ?: return@repeat
            mobCounts[mob] = mobCounts.getOrDefault(mob, 0) + 1
        }

        // Spawn each type
        for ((entityType, count) in mobCounts) {
            entitySpawner.spawnViaCmi(player, entityType, count, config.cmiSpread)
        }

        return SpawnResult(amount)
    }

    /**
     * Spawn mobs naturally by finding valid locations.
     */
    private fun spawnNaturally(player: Player, amount: Int): SpawnResult {
        val locations = findSpawnLocations(player, amount)
        debug("Found {} valid spawn locations near {}", locations.size, player.name)

        for (location in locations) {
            val entityType = mobPicker.random() ?: continue
            entitySpawner.spawn(location, entityType)
        }

        return SpawnResult(locations.size)
    }

    /**
     * Find valid spawn locations near a player.
     */
    fun findSpawnLocations(player: Player, amount: Int): List<Location> {
        val locations = mutableListOf<Location>()
        val center = player.location
        val radius = config.radius.toInt()
        val maxAttempts = amount * config.tryMultiplier

        repeat(maxAttempts) {
            if (locations.size >= amount) return@repeat

            val location = tryFindLocation(center, radius, player)
            if (location != null) {
                locations.add(location)
            }
        }

        return locations
    }

    /**
     * Try to find a single valid spawn location.
     */
    private fun tryFindLocation(center: Location, radius: Int, player: Player): Location? {
        val x = center.x + (random() * radius * 2 - radius)
        val y = center.y + (random() * radius * 2 - radius)
        val z = center.z + (random() * radius * 2 - radius)

        val loc = Location(center.world, x, y, z)

        // Must be on solid ground
        if (!loc.block.type.isSolid) return null

        // Must have 2 blocks of air above
        if (loc.block.getRelative(0, 1, 0).type.isSolid) return null
        if (loc.block.getRelative(0, 2, 0).type.isSolid) return null

        // Must be within radius
        if (loc.distance(center) > radius) return null

        // Check light level
        if (loc.block.getRelative(0, 1, 0).lightLevel > config.maxLightLevel) return null

        // Must not be in line of sight
        if (player.hasLineOfSight(loc)) return null

        // Final passable check
        if (!loc.block.isPassable) return null

        return loc.add(0.0, 1.0, 0.0)
    }

    /**
     * Get tracked mob types.
     */
    fun getTrackedMobTypes(): Set<EntityType> = trackedMobTypes

    /**
     * Check if service is running.
     */
    fun isRunning(): Boolean = task != null
}


