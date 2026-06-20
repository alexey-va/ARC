package ru.arc.hooks.elitemobs

import com.destroystokyo.paper.ParticleBuilder
import com.magmaguy.elitemobs.treasurechest.TreasureChest
import com.magmaguy.elitemobs.wormhole.Wormhole
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.ParticleManager
import ru.arc.xserver.playerlist.PlayerManager

class EMWormholes {

    companion object {
        private var wormholeTask: BukkitTask? = null
        val config = ConfigManager.of(ARC.instance.dataPath, "elitemobs.yml")
    }

    fun init() {
        cancel()
        info("Starting wormhole task")
        wormholeTask = ARC.instance.server.scheduler.runTaskTimerAsynchronously(
            ARC.instance,
            Runnable {
                try {
                    runWormholes()
                    runChests()
                } catch (e: Exception) {
                    error("Error running wormholes", e)
                }
            },
            20L,
            config.integer("wormholes.period-ticks", 2).toLong(),
        )
    }

    fun cancel() {
        wormholeTask?.takeUnless { it.isCancelled }?.cancel()
    }

    private fun runChests() {
        val players = PlayerManager.getOnlinePlayersThreadSafe()
        val chests = TreasureChest.getTreasureChestHashMap() ?: return
        if (chests.isEmpty()) return

        val entries = snapshotEntries(chests)
        try {
            for ((location, chest) in entries) {
                if (location == null) continue
                val worldName = chest.worldName ?: continue
                val world = Bukkit.getWorld(worldName) ?: continue
                location.world = world

                for (p in players) {
                    val playerLocation = p.location
                    if (playerLocation.world != world) continue
                    val dist = config.real("chests.distance", 40.0)
                    if (location.distanceSquared(playerLocation) > dist * dist) continue

                    val restockTimers = chest.customTreasureChestConfigFields.restockTimers ?: continue
                    val found = restockTimers.any { s ->
                        val split = s.split(":")
                        split.isNotEmpty() && split[0].equals(p.uniqueId.toString(), ignoreCase = true)
                    }
                    if (found) continue

                    ParticleManager.queue(
                        ParticleBuilder(config.particle("chests.particle", Particle.END_ROD))
                            .count(config.integer("chests.particle-count", 10))
                            .location(location.toCenterLocation())
                            .offset(
                                config.real("chests.particle-offset", 0.5),
                                config.real("chests.particle-offset", 0.5),
                                config.real("chests.particle-offset", 0.5),
                            )
                            .extra(config.real("chests.particle-extra", 0.05))
                            .receivers(players)
                            .spawn()
                    )
                    try {
                        val sound = config.string("chests.sound", "block.beacon.ambient")
                        val sound1 = Registry.SOUNDS.get(Key.key(sound)) ?: continue
                        p.playSound(p.location, sound1, 1.0f, 1.0f)
                    } catch (e: Exception) {
                        error("Error playing sound", e)
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

        for (wormhole in snapshot(wormholes)) {
            val e1 = wormhole.wormholeEntry1 ?: continue
            val e2 = wormhole.wormholeEntry2 ?: continue
            val l1 = e1.location ?: continue
            val l2 = e2.location ?: continue

            val particle = config.particle("wormholes.particle", Particle.DUST)
            val offset = config.real("wormholes.particle-offset", 1.0).toFloat()
            val extra = config.real("wormholes.particle-extra", 0.05)
            val count = config.integer("wormholes.particle-count", 30)
            val modifier = wormhole.wormholeConfigFields.sizeMultiplier

            listOf(l1, l2).filter { it.world != null }.forEach { l ->
                ParticleManager.queue(
                    ParticleBuilder(particle)
                        .count(count)
                        .location(l)
                        .extra(extra)
                        .offset(offset * modifier, offset * modifier, offset * modifier)
                        .receivers(players)
                        .color(wormhole.particleColor)
                        .spawn()
                )
            }
        }
    }

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
