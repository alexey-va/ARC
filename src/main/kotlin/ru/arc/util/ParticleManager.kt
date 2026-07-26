package ru.arc.util

import com.destroystokyo.paper.ParticleBuilder
import ru.arc.core.ScheduledTask
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Batches particle spawning on the server thread.
 *
 * Particle builders eventually call the Bukkit world API, so both legacy queue
 * entry points are drained by the same synchronous task.
 */
object ParticleManager {

    private val buildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()
    private val syncBuildersQueue = ConcurrentLinkedDeque<ParticleBuilder>()

    private var task: ScheduledTask? = null

    @JvmStatic
    fun setupParticleManager() {
        stopTasks()
        task =
            repeating(period = 1.ticks, delay = 0.ticks) {
                var count = 0
                while (count < MAX_PARTICLES_PER_TICK) {
                    val builder = syncBuildersQueue.poll() ?: buildersQueue.poll() ?: break
                    builder.spawn()
                    count++
                }
                val remaining = syncBuildersQueue.size + buildersQueue.size
                if (remaining > 0) {
                    warn("Particle queue rate-limited; {} builders remain", remaining)
                }
            }
    }

    @JvmStatic
    fun stopTasks() {
        task?.cancel()
        task = null
        buildersQueue.clear()
        syncBuildersQueue.clear()
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

    private const val MAX_PARTICLES_PER_TICK = 200
}
