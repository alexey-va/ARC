package ru.arc.util

import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.ScheduledTask
import ru.arc.core.SystemTimeProvider
import ru.arc.core.TaskScheduler
import ru.arc.core.TimeProvider
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for cooldown management (for testing/DI).
 */
interface CooldownProvider {
    fun cooldown(uuid: UUID, id: String): Long
    fun addCooldown(uuid: UUID, id: String, ticks: Long)
    fun onCooldown(uuid: UUID, id: String, ticks: Long, action: Runnable): Boolean
    fun isOnCooldown(uuid: UUID, id: String): Boolean = cooldown(uuid, id) > 0
    fun clearCooldown(uuid: UUID, id: String)
    fun clearAllCooldowns(uuid: UUID)
}

/**
 * Production cooldown manager using Bukkit scheduler.
 *
 * Provides both static methods (for Java) and CooldownProvider implementation (for Kotlin/DI).
 */
object CooldownManager {

    private var countdownTask: ScheduledTask? = null
    private val cooldownMap = ConcurrentHashMap<UUID, MutableMap<String, Cooldown>>()
    private var scheduler: TaskScheduler? = null

    private fun countdown(step: Long) {
        val uuidToRemove = mutableListOf<UUID>()
        for ((uuid, cooldowns) in cooldownMap) {
            val toRemove = mutableListOf<String>()
            for ((id, cooldown) in cooldowns) {
                if (cooldown.ticksLeft <= step) {
                    toRemove.add(id)
                } else {
                    cooldown.ticksLeft -= step
                }
            }
            toRemove.forEach { cooldowns.remove(it) }
            if (cooldowns.isEmpty()) {
                uuidToRemove.add(uuid)
            }
        }
        uuidToRemove.forEach { cooldownMap.remove(it) }
    }

    /**
     * Get remaining cooldown in ticks.
     */
    @JvmStatic
    fun cooldown(uuid: UUID, id: String): Long {
        val stringCooldownMap = cooldownMap[uuid] ?: return 0
        val cooldown = stringCooldownMap[id] ?: return 0
        if (cooldown.ticksLeft <= 0) {
            stringCooldownMap.remove(id)
            return 0
        }
        return cooldown.ticksLeft
    }

    /**
     * Add a cooldown.
     */
    @JvmStatic
    fun addCooldown(uuid: UUID, id: String, ticks: Long) {
        cooldownMap.compute(uuid) { _, v ->
            val map = v ?: ConcurrentHashMap()
            map[id] = Cooldown(true, ticks, id)
            map
        }
    }

    /**
     * Execute action if not on cooldown, then add cooldown.
     * @return true if action was executed
     */
    @JvmStatic
    fun onCooldown(uuid: UUID, id: String, ticks: Long, action: Runnable): Boolean {
        if (cooldown(uuid, id) > 0) return false
        action.run()
        addCooldown(uuid, id, ticks)
        return true
    }

    /**
     * Check if on cooldown.
     */
    @JvmStatic
    fun isOnCooldown(uuid: UUID, id: String): Boolean = cooldown(uuid, id) > 0

    /**
     * Clear cooldown for player.
     */
    @JvmStatic
    fun clearCooldown(uuid: UUID, id: String) {
        cooldownMap[uuid]?.remove(id)
    }

    /**
     * Clear all cooldowns for player.
     */
    @JvmStatic
    fun clearAllCooldowns(uuid: UUID) {
        cooldownMap.remove(uuid)
    }

    @JvmStatic
    fun setupTask(period: Long) {
        countdownTask?.cancel()
        scheduler = BukkitTaskScheduler(ARC.plugin!!)
        countdownTask = scheduler!!.runTimer(period, period) {
            countdown(period)
        }
    }

    /**
     * Setup with custom scheduler (for testing).
     */
    fun setupTask(period: Long, customScheduler: TaskScheduler) {
        countdownTask?.cancel()
        scheduler = customScheduler
        countdownTask = scheduler!!.runTimer(period, period) {
            countdown(period)
        }
    }

    /**
     * Stop the countdown task.
     */
    fun stop() {
        countdownTask?.cancel()
        countdownTask = null
    }

    /**
     * Clear all cooldowns (for testing).
     */
    fun clearAll() {
        cooldownMap.clear()
    }

    /**
     * Get as CooldownProvider interface (for DI).
     */
    fun asProvider(): CooldownProvider = CooldownManagerProvider

    data class Cooldown(
        var resetOnExit: Boolean,
        var ticksLeft: Long,
        var cooldownId: String
    )

    /**
     * CooldownProvider wrapper for the singleton.
     */
    private object CooldownManagerProvider : CooldownProvider {
        override fun cooldown(uuid: UUID, id: String) = CooldownManager.cooldown(uuid, id)
        override fun addCooldown(uuid: UUID, id: String, ticks: Long) = CooldownManager.addCooldown(uuid, id, ticks)
        override fun onCooldown(uuid: UUID, id: String, ticks: Long, action: Runnable) =
            CooldownManager.onCooldown(uuid, id, ticks, action)

        override fun clearCooldown(uuid: UUID, id: String) = CooldownManager.clearCooldown(uuid, id)
        override fun clearAllCooldowns(uuid: UUID) = CooldownManager.clearAllCooldowns(uuid)
    }
}

/**
 * In-memory cooldown provider for testing.
 * Time-based, not tick-based.
 */
class TestCooldownProvider(
    private val timeProvider: TimeProvider = SystemTimeProvider
) : CooldownProvider {

    private val cooldowns = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    override fun cooldown(uuid: UUID, id: String): Long {
        val expireTime = cooldowns[uuid]?.get(id) ?: return 0
        val remaining = expireTime - timeProvider.currentTimeMillis()
        if (remaining <= 0) {
            cooldowns[uuid]?.remove(id)
            return 0
        }
        return remaining / 50 // Convert ms to ticks
    }

    override fun addCooldown(uuid: UUID, id: String, ticks: Long) {
        val expireTime = timeProvider.currentTimeMillis() + (ticks * 50)
        cooldowns.computeIfAbsent(uuid) { ConcurrentHashMap() }[id] = expireTime
    }

    override fun onCooldown(uuid: UUID, id: String, ticks: Long, action: Runnable): Boolean {
        if (isOnCooldown(uuid, id)) return false
        action.run()
        addCooldown(uuid, id, ticks)
        return true
    }

    override fun clearCooldown(uuid: UUID, id: String) {
        cooldowns[uuid]?.remove(id)
    }

    override fun clearAllCooldowns(uuid: UUID) {
        cooldowns.remove(uuid)
    }

    fun clearAll() {
        cooldowns.clear()
    }
}
