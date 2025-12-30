package ru.arc.util

import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CooldownManager {

    private var countdownTask: BukkitTask? = null
    private val cooldownMap = ConcurrentHashMap<UUID, MutableMap<String, Cooldown>>()

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

    @JvmStatic
    fun addCooldown(uuid: UUID, id: String, ticks: Long) {
        cooldownMap.compute(uuid) { _, v ->
            val map = v ?: ConcurrentHashMap()
            map[id] = Cooldown(true, ticks, id)
            map
        }
    }

    @JvmStatic
    fun onCooldown(uuid: UUID, id: String, ticks: Long, action: Runnable): Boolean {
        if (cooldown(uuid, id) > 0) return false
        action.run()
        addCooldown(uuid, id, ticks)
        return true
    }

    @JvmStatic
    fun setupTask(period: Long) {
        if (countdownTask != null && !countdownTask!!.isCancelled()) {
            countdownTask!!.cancel()
        }
        countdownTask = object : BukkitRunnable() {
            override fun run() {
                countdown(period)
            }
        }.runTaskTimer(ARC.plugin!!, period, period)
    }

    data class Cooldown(
        var resetOnExit: Boolean,
        var ticksLeft: Long,
        var cooldownId: String
    )
}

