package ru.arc.hooks.elitemobs

import com.destroystokyo.paper.ParticleBuilder
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import com.magmaguy.elitemobs.wormhole.Wormhole
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import ru.arc.xserver.playerlist.PlayerManager
import ru.arc.config.material
import ru.arc.config.materialSet
import ru.arc.config.particle
import ru.arc.config.sound
import java.util.UUID

class EMWormholes internal constructor(
    private val config: Config,
    private val scheduleWormholes: (periodTicks: Long, task: () -> Unit) -> ScheduledTask,
) : AutoCloseable {
    constructor() : this(
        config = ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml"),
        scheduleWormholes = { periodTicks, task ->
            repeating(periodTicks.ticks, delay = 20.ticks) {
                task()
            }
        },
    )

    private var wormholeTask: ScheduledTask? = null
    private val lastChestSoundTick = mutableMapOf<UUID, Long>()
    private var closed = false

    @Synchronized
    fun init() {
        check(!closed) { "EMWormholes is closed" }
        cancelTask()
        info("Starting wormhole task")
        val periodTicks = config.integer("wormholes.period-ticks", 2).toLong()
        require(periodTicks > 0) { "wormholes.period-ticks must be positive, got $periodTicks" }
        wormholeTask = scheduleWormholes(periodTicks) {
            try {
                runWormholes()
                runChests()
            } catch (e: Exception) {
                error("Error running wormholes", e)
            }
        }
    }

    @Deprecated("Use close()", ReplaceWith("close()"))
    fun cancel() {
        close()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelTask()
        lastChestSoundTick.clear()
    }

    private fun cancelTask() {
        val task = wormholeTask
        wormholeTask = null
        task?.takeUnless { it.isCancelled }?.cancel()
    }

    private fun runChests() {
        val players = PlayerManager.getOnlinePlayersThreadSafe()
        if (players.isEmpty()) return
        val chests = TreasureChest.getTreasureChestHashMap() ?: return
        if (chests.isEmpty()) return

        val distance = config.real("chests.distance", 40.0)
        val distanceSquared = distance * distance
        val particle = config.particle("chests.particle", Particle.END_ROD)
        val particleCount = config.integer("chests.particle-count", 10)
        val particleOffset = config.real("chests.particle-offset", 0.5)
        val particleExtra = config.real("chests.particle-extra", 0.05)
        val soundPeriodTicks = config.integer("chests.sound-period-ticks", 100).toLong().coerceAtLeast(1L)
        val currentTick = Bukkit.getCurrentTick().toLong()
        val sound = runCatching {
            Registry.SOUNDS.get(Key.key(config.string("chests.sound", "block.beacon.ambient")))
        }.onFailure { error("Error resolving chest sound", it) }.getOrNull()
        val entries = snapshotEntries(chests)
        try {
            for ((location, chest) in entries) {
                if (location == null) continue
                val worldName = chest.worldName ?: continue
                val world = Bukkit.getWorld(worldName) ?: continue
                location.world = world

                val receivers = ArrayList<Player>()
                for (p in players) {
                    val playerLocation = p.location
                    if (playerLocation.world != world) continue
                    if (location.distanceSquared(playerLocation) > distanceSquared) continue

                    val restockTimers = chest.customTreasureChestConfigFields.restockTimers ?: continue
                    val playerId = p.uniqueId.toString()
                    val found = restockTimers.any { timer -> belongsToPlayer(timer, playerId) }
                    if (found) continue
                    receivers.add(p)
                }

                if (receivers.isNotEmpty()) {
                    ParticleManager.queue(
                        ParticleBuilder(particle)
                            .count(particleCount)
                            .location(location.toCenterLocation())
                            .offset(particleOffset, particleOffset, particleOffset)
                            .extra(particleExtra)
                            .receivers(receivers)
                    )
                    sound?.let { resolved ->
                        receivers.forEach { player ->
                            val lastTick = lastChestSoundTick[player.uniqueId]
                            if (lastTick == null || currentTick - lastTick >= soundPeriodTicks) {
                                player.playSound(player.location, resolved, 1.0f, 1.0f)
                                lastChestSoundTick[player.uniqueId] = currentTick
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error("Error running chests", e)
        }
    }

    private fun runWormholes() {
        val wormholes = Wormhole.getWormholes() ?: return
        if (wormholes.isEmpty()) return
        val players = PlayerManager.getOnlinePlayersThreadSafe()
        if (players.isEmpty()) return
        val particle = config.particle("wormholes.particle", Particle.DUST)
        val offset = config.real("wormholes.particle-offset", 1.0).toFloat()
        val extra = config.real("wormholes.particle-extra", 0.05)
        val count = config.integer("wormholes.particle-count", 30)

        for (wormhole in snapshot(wormholes)) {
            val e1 = wormhole.wormholeEntry1 ?: continue
            val e2 = wormhole.wormholeEntry2 ?: continue
            val l1 = e1.location ?: continue
            val l2 = e2.location ?: continue

            val modifier = wormhole.wormholeConfigFields.sizeMultiplier

            fun queue(location: Location) {
                if (location.world == null) return
                ParticleManager.queue(
                    ParticleBuilder(particle)
                        .count(count)
                        .location(location)
                        .extra(extra)
                        .offset(offset * modifier, offset * modifier, offset * modifier)
                        .receivers(players)
                        .color(wormhole.particleColor)
                )
            }
            queue(l1)
            queue(l2)
        }
    }

    private fun belongsToPlayer(timer: String, playerId: String): Boolean =
        timer.length > playerId.length &&
            timer[playerId.length] == ':' &&
            timer.regionMatches(0, playerId, 0, playerId.length, ignoreCase = true)

    private fun <T> snapshot(source: Iterable<T>): List<T> {
        val copy = ArrayList<T>()
        try {
            for (item in source) copy.add(item)
        } catch (ignored: ConcurrentModificationException) {
        }
        return copy
    }

    private fun <K, V> snapshotEntries(map: Map<K, V>): List<Map.Entry<K, V>> {
        return try {
            ArrayList(map.entries)
        } catch (ignored: ConcurrentModificationException) {
            emptyList()
        }
    }
}
