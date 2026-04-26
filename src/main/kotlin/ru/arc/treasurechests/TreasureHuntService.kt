package ru.arc.treasurechests

import com.destroystokyo.paper.ParticleBuilder
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import ru.arc.common.chests.CustomChest
import ru.arc.common.locationpools.LocationPool
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.treasure.core.Treasures
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.ParticleManager
import ru.arc.util.RandomUtils
import ru.arc.util.SoundUtils
import ru.arc.util.TextUtil.mm
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interface for announcing messages.
 */
interface MessageAnnouncer {
    fun sendToWorld(
        world: World,
        message: String,
    )

    fun sendGlobally(
        uuid: UUID,
        message: String,
    )

    fun getPlayerUuids(): Set<UUID>
}

/**
 * Interface for spawning custom chests.
 * Note: Named ChestSpawner to avoid conflict with ChestFactory object in common.chests
 */
interface ChestSpawner {
    fun createChest(
        block: Block,
        variant: ChestVariant,
        namespaceId: String?,
    ): CustomChest?

    fun clearChest(
        block: Block,
        customBlockDataKey: NamespacedKey,
    )
}

/**
 * Core service for treasure hunt logic.
 *
 * Fully testable through dependency injection.
 */
class TreasureHuntService(
    private var config: TreasureHuntModuleConfig,
    private val scheduler: TaskScheduler,
    private val announcer: MessageAnnouncer,
    private val chestSpawner: ChestSpawner,
) {
    private val activeHunts = ConcurrentLinkedDeque<ActiveHunt>()
    private val blockToHunt = ConcurrentHashMap<Location, ActiveHunt>()

    // === Lifecycle ===

    /**
     * Reload configuration.
     */
    fun reloadConfig(newConfig: TreasureHuntModuleConfig) {
        this.config = newConfig
        config.invalidateCache()
        info("Treasure hunt config reloaded with ${config.huntTypes.size} hunt types")
    }

    /**
     * Stop all hunts and cleanup.
     */
    fun shutdown() {
        stopAll()
    }

    // === Hunt Management ===

    /**
     * Start a hunt by type ID.
     *
     * @return the started hunt or null if failed
     */
    fun startHunt(
        typeId: String,
        chestCount: Int,
    ): ActiveHunt? {
        val huntConfig = config.huntTypes[typeId]
        if (huntConfig == null) {
            warn("Hunt type not found: $typeId")
            return null
        }

        val locationPool = huntConfig.getLocationPool()
        if (locationPool == null) {
            warn("Location pool not found: ${huntConfig.locationPoolId}")
            return null
        }

        val count = if (chestCount > 0) chestCount else locationPool.size
        return startHuntInternal(huntConfig, count)
    }

    /**
     * Start a hunt with custom parameters.
     */
    fun startHunt(
        locationPool: LocationPool,
        chestCount: Int,
        chestType: ChestType,
    ): ActiveHunt? {
        val huntConfig =
            TreasureHuntConfig.simple(
                id = "dynamic-${System.currentTimeMillis()}",
                locationPoolId = locationPool.id,
                chestType = chestType,
            )

        return startHuntInternal(huntConfig, chestCount)
    }

    private fun startHuntInternal(
        huntConfig: TreasureHuntConfig,
        chestCount: Int,
    ): ActiveHunt? {
        val locationPool = huntConfig.getLocationPool()
        if (locationPool == null) {
            warn("Location pool not found: ${huntConfig.locationPoolId}")
            return null
        }

        info("Starting treasure hunt for pool: ${locationPool.id}")

        // Stop existing hunt for this pool
        getByLocationPool(locationPool)?.let { existing ->
            info("Stopping existing hunt for pool: ${locationPool.id}")
            stopHunt(existing)
        }

        // Get random locations
        val locations =
            locationPool
                .getRandomLocations(chestCount)
                .filter { it.isSameServer() }
                .mapNotNull { it.toLocation() }

        if (locations.isEmpty()) {
            warn("No valid locations in pool: ${locationPool.id}")
            return null
        }

        // Determine world
        val world = locations.firstOrNull()?.world
        if (world == null) {
            warn("Could not determine world for hunt")
            return null
        }

        // Clear blocks and place chests
        val placedChests = mutableMapOf<Location, PlacedChest>()
        val aliases = config.aliases

        for (location in locations) {
            val block = location.block
            if (block.type != Material.AIR) {
                block.type = Material.AIR
            }

            val chestType = huntConfig.getRandomChestType()
            val namespaceId = chestType.namespaceId?.let { aliases[it] ?: it }

            val chest = chestSpawner.createChest(block, chestType.type, namespaceId)
            if (chest != null) {
                val created = chest.create()
                if (created && block.type != Material.AIR) {
                    placedChests[block.location.toCenterLocation()] = PlacedChest(chest, chestType)
                } else if (!created) {
                    warn("Failed to create chest at $location")
                }
            }
        }

        if (placedChests.isEmpty()) {
            warn("No chests were placed")
            return null
        }

        // Create hunt
        val hunt =
            ActiveHunt(
                config = huntConfig,
                world = world,
                chests = ConcurrentHashMap(placedChests),
                totalChests = placedChests.size,
                startTime = System.currentTimeMillis(),
            )

        // Start display task
        startDisplayTask(hunt)

        // Announce start
        announceStart(hunt)

        // Register
        activeHunts.add(hunt)
        placedChests.keys.forEach { blockToHunt[it] = hunt }

        info("Started hunt with ${placedChests.size} chests in ${world.name}")
        return hunt
    }

    /**
     * Stop a specific hunt.
     */
    fun stopHunt(hunt: ActiveHunt) {
        hunt.displayTask?.cancel()
        hunt.displayTask = null

        // Hide boss bar
        hunt.bossBar?.let { bar ->
            hunt.bossBarAudience.forEach { it.hideBossBar(bar) }
        }
        hunt.bossBarAudience.clear()

        // Destroy remaining chests
        hunt.chests.values.forEach { it.chest.destroy() }
        hunt.chests.clear()

        // Announce stop
        announceStop(hunt)

        // Unregister
        blockToHunt.entries.removeIf { it.value === hunt }
        activeHunts.remove(hunt)

        info("Stopped hunt in ${hunt.world.name}")
    }

    /**
     * Stop all active hunts.
     */
    fun stopAll() {
        activeHunts.toList().forEach { stopHunt(it) }
    }

    // === Chest Claiming ===

    /** Lock for atomic chest claiming operations */
    private val claimLock = Any()

    /**
     * Claim a chest.
     * Thread-safe: uses synchronized block to prevent double claiming.
     *
     * @return true if claimed, false if not part of a hunt
     */
    fun claimChest(
        block: Block,
        player: Player,
    ): Boolean {
        val centerLoc = block.location.toCenterLocation()

        // Atomic removal from both maps
        val (hunt, placedChest) =
            synchronized(claimLock) {
                val hunt = blockToHunt.remove(centerLoc) ?: return false
                val placedChest = hunt.chests.remove(centerLoc)
                if (placedChest == null) {
                    // Restore blockToHunt if chest not found (shouldn't happen)
                    blockToHunt[centerLoc] = hunt
                    return false
                }
                hunt to placedChest
            }

        // Destroy chest
        placedChest.chest.destroy()

        // Give reward
        placedChest.chestType.getTreasurePool()?.random()?.let { treasure ->
            Treasures.service.give(treasure, player)
        }

        // Play effects
        playClaimEffects(block, placedChest.chestType, hunt.config.effects.launchFireworks)

        // Check if hunt completed
        if (hunt.chests.isEmpty()) {
            stopHunt(hunt)
        }

        debug("Player ${player.name} claimed chest at $centerLoc")
        return true
    }

    // === Query ===

    fun getByBlock(block: Block): ActiveHunt? = blockToHunt[block.location.toCenterLocation()]

    fun getByLocationPool(pool: LocationPool): ActiveHunt? = activeHunts.find { it.config.locationPoolId == pool.id }

    fun getActiveHunts(): List<ActiveHunt> = activeHunts.toList()

    fun getHuntTypeIds(): List<String> = config.huntTypes.keys.toList()

    fun getHuntConfig(id: String): TreasureHuntConfig? = config.huntTypes[id]

    fun getAliases(): Map<String, String> = config.aliases

    fun getMessages(): TreasureHuntMessages = config.messages

    // === Display ===

    private fun startDisplayTask(hunt: ActiveHunt) {
        val tickInterval = config.particles.idleTicks
        val soundCounter = AtomicInteger(0)

        hunt.displayTask =
            scheduler.runTimer(tickInterval, tickInterval) {
                updateDisplay(hunt, soundCounter)
            }
    }

    private fun updateDisplay(
        hunt: ActiveHunt,
        soundCounter: AtomicInteger,
    ) {
        if (hunt.chests.isEmpty()) return

        // Check timeout
        val elapsed = (System.currentTimeMillis() - hunt.startTime) / 1000
        if (hunt.config.timeoutSeconds > 0 && elapsed >= hunt.config.timeoutSeconds) {
            stopHunt(hunt)
            return
        }

        val players = hunt.world.players
        val playerSoundEach = config.particles.playerSoundEach
        val soundCount = soundCounter.getAndIncrement()
        if (soundCount >= playerSoundEach) soundCounter.set(0)

        val closestChests = mutableMapOf<Player, PlacedChest>()

        // Display particles
        for ((_, placedChest) in hunt.chests) {
            val particleConfig = config.particles.getIdleConfig(placedChest.chestType.particlePath)
            val chestLocation = placedChest.chest.blockLocation

            val receivers = mutableListOf<Player>()
            for (player in players) {
                val distance = player.location.distance(chestLocation)

                closestChests.compute(player) { _, current ->
                    if (current == null) {
                        placedChest
                    } else if (distance < player.location.distance(current.chest.blockLocation)) {
                        placedChest
                    } else {
                        current
                    }
                }

                if (distance <= particleConfig.radius) {
                    receivers.add(player)
                }
            }

            if (receivers.isNotEmpty()) {
                ParticleManager.queue(
                    ParticleBuilder(particleConfig.particle)
                        .location(chestLocation.toCenterLocation())
                        .count(particleConfig.count)
                        .extra(particleConfig.extra)
                        .offset(particleConfig.offset, particleConfig.offset, particleConfig.offset)
                        .receivers(receivers),
                )
            }
        }

        // Play proximity sounds
        if (soundCount == 0) {
            for ((player, placedChest) in closestChests) {
                val particleConfig = config.particles.getIdleConfig(placedChest.chestType.particlePath)
                val chestLocation = placedChest.chest.blockLocation

                if (player.location.distance(chestLocation) > particleConfig.soundRadius) continue

                SoundUtils.playSoundAt(player, chestLocation.toCenterLocation(), particleConfig.sound)
            }
        }

        // Update boss bar
        updateBossBar(hunt)
    }

    private fun updateBossBar(hunt: ActiveHunt) {
        if (!hunt.config.bossBar.visible) return

        val message =
            hunt.config.bossBar.message
                .replace("%left%", hunt.chests.size.toString())
        val progress = hunt.chests.size.toFloat() / hunt.totalChests
        val players = hunt.world.players

        if (hunt.bossBar == null) {
            hunt.bossBar =
                BossBar.bossBar(
                    mm(message),
                    progress,
                    hunt.config.bossBar.color,
                    hunt.config.bossBar.overlay,
                )
        } else {
            hunt.bossBar?.name(mm(message))
            hunt.bossBar?.progress(progress.coerceIn(0f, 1f))
        }

        // Update audience
        hunt.bossBarAudience.removeAll { player ->
            if (player.world != hunt.world) {
                hunt.bossBar?.let { player.hideBossBar(it) }
                true
            } else {
                false
            }
        }

        players.forEach { player ->
            if (player !in hunt.bossBarAudience) {
                hunt.bossBar?.let { player.showBossBar(it) }
                hunt.bossBarAudience.add(player)
            }
        }
    }

    // === Effects ===

    private val fireworkColors =
        arrayOf(
            Color.WHITE,
            Color.AQUA,
            Color.PURPLE,
            Color.YELLOW,
            Color.MAROON,
            Color.GREEN,
            Color.TEAL,
            Color.OLIVE,
            Color.FUCHSIA,
            Color.LIME,
            Color.RED,
            Color.ORANGE,
        )

    private fun playClaimEffects(
        block: Block,
        chestType: ChestType,
        launchFirework: Boolean,
    ) {
        val particleConfig = config.particles.getClaimedConfig(chestType.particlePath)

        // Particles
        ParticleManager.queue(
            ParticleBuilder(particleConfig.particle)
                .location(block.location.toCenterLocation())
                .count(particleConfig.count)
                .extra(particleConfig.extra)
                .offset(particleConfig.offset, particleConfig.offset, particleConfig.offset)
                .receivers(block.world.players),
        )

        // Sound
        SoundUtils.playSound(block.location, particleConfig.sound)

        // Firework
        if (launchFirework) {
            launchFirework(block)
        }
    }

    private fun launchFirework(block: Block) {
        val random = ThreadLocalRandom.current()

        block.world.spawnEntity(
            block.location.toCenterLocation().add(0.0, 1.0, 0.0),
            EntityType.FIREWORK_ROCKET,
            CreatureSpawnEvent.SpawnReason.CUSTOM,
        ) { entity ->
            val firework = entity as Firework

            val effect =
                FireworkEffect
                    .builder()
                    .flicker(random.nextBoolean())
                    .trail(random.nextBoolean())
                    .with(RandomUtils.random(FireworkEffect.Type.entries.toTypedArray()))
                    .withColor(RandomUtils.random(fireworkColors, 3).toList())
                    .withFade(RandomUtils.random(fireworkColors, 3).toList())
                    .build()

            val meta = firework.fireworkMeta
            meta.power = random.nextInt(1, 3)
            meta.addEffect(effect)
            firework.fireworkMeta = meta
        }
    }

    // === Announcements ===

    private fun announceStart(hunt: ActiveHunt) {
        if (!hunt.config.announcements.announceStart) return

        val message =
            hunt.config.announcements.startMessage
                ?: config.messages.defaultStartMessage

        if (message.isBlank()) return

        if (hunt.config.announcements.announceStartGlobally) {
            announcer.getPlayerUuids().forEach { uuid ->
                announcer.sendGlobally(uuid, message)
            }
        } else {
            announcer.sendToWorld(hunt.world, message)
        }
    }

    private fun announceStop(hunt: ActiveHunt) {
        if (!hunt.config.announcements.announceStop) return

        val message =
            hunt.config.announcements.stopMessage
                ?: config.messages.defaultStopMessage

        if (message.isBlank()) return

        announcer.sendToWorld(hunt.world, message)
    }

    // === Player Events ===

    fun onPlayerQuit(player: Player) {
        activeHunts.forEach { hunt ->
            hunt.bossBarAudience.remove(player)
        }
    }
}

/**
 * Размещённый сундук с информацией о типе.
 */
data class PlacedChest(
    val chest: CustomChest,
    val chestType: ChestType,
)

/**
 * Active hunt state.
 * Thread-safe: uses ConcurrentHashMap for chests and thread-safe set for audience.
 */
class ActiveHunt(
    val config: TreasureHuntConfig,
    val world: World,
    val chests: ConcurrentHashMap<Location, PlacedChest>,
    val totalChests: Int,
    val startTime: Long,
) {
    @Volatile
    var displayTask: ScheduledTask? = null

    @Volatile
    var bossBar: BossBar? = null

    /** Thread-safe set for boss bar audience */
    val bossBarAudience: MutableSet<Player> = ConcurrentHashMap.newKeySet()

    val remainingChests: Int get() = chests.size
    val progress: Float get() = if (totalChests > 0) remainingChests.toFloat() / totalChests else 0f
}
