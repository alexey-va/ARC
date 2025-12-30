package ru.arc.util

import com.destroystokyo.paper.ParticleBuilder
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentLinkedDeque

object ParticleManager {

    private val buildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()
    private val syncBuildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()

    private var task: BukkitTask? = null
    private var syncTask: BukkitTask? = null

    @JvmStatic
    fun setupParticleManager() {
        if (task != null && !task!!.isCancelled()) {
            task!!.cancel()
        }
        if (syncTask != null && !syncTask!!.isCancelled()) {
            syncTask!!.cancel()
        }

        task = object : BukkitRunnable() {
            override fun run() {
                while (buildersQueue.isNotEmpty()) {
                    buildersQueue.poll()?.spawn()
                }
            }
        }.runTaskTimerAsynchronously(ARC.plugin!!, 0L, 1L)

        syncTask = object : BukkitRunnable() {
            override fun run() {
                var count = 0
                while (syncBuildersQueue.isNotEmpty()) {
                    syncBuildersQueue.poll()?.spawn()
                    count++
                    if (count > 200) {
                        warn("Too many particles to show in one tick. Size: {}", syncBuildersQueue.size)
                        break
                    }
                }
            }
        }.runTaskTimer(ARC.plugin!!, 0L, 1L)
    }

    @JvmStatic
    fun queue(builder: ParticleBuilder) {
        val res = buildersQueue.offer(builder)
        if (!res) {
            warn("Failed to queue particle builder: {}", builder)
        }
    }
}

