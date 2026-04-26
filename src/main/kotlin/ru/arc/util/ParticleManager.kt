package ru.arc.util

import com.destroystokyo.paper.ParticleBuilder
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Manages particle spawning with async and sync queues.
 * Uses Task DSL for scheduling.
 */
object ParticleManager {

    private val buildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()
    private val syncBuildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()

    private var asyncTask: ScheduledTask? = null
    private var syncTask: ScheduledTask? = null

    @JvmStatic
    fun setupParticleManager() {
        // Cancel existing tasks
        asyncTask?.cancel()
        syncTask?.cancel()

        // Async particle processing (every tick)
        asyncTask =
            repeatingAsync(period = 1.ticks, delay = 0.ticks) {
                while (buildersQueue.isNotEmpty()) {
                    buildersQueue.poll()?.spawn()
                }
            }

        // Sync particle processing with rate limiting
        syncTask =
            repeating(period = 1.ticks, delay = 0.ticks) {
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
    }

    @JvmStatic
    fun stopTasks() {
        asyncTask?.cancel()
        syncTask?.cancel()
    }

    @JvmStatic
    fun queue(builder: ParticleBuilder) {
        if (!buildersQueue.offer(builder)) {
            warn("Failed to queue particle builder: {}", builder)
        }
    }

    @JvmStatic
    fun queueSync(builder: ParticleBuilder) {
        if (!syncBuildersQueue.offer(builder)) {
            warn("Failed to queue sync particle builder: {}", builder)
        }
    }
}

