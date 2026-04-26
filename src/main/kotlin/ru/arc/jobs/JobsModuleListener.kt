package ru.arc.jobs

import com.gamingmesh.jobs.api.JobsExpGainEvent
import com.gamingmesh.jobs.api.JobsPrePaymentEvent
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.audit.AuditManager
import ru.arc.audit.Type
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Listener for Jobs plugin events.
 *
 * Applies custom boost multipliers on top of Jobs' built-in boosts.
 * Uses event fingerprinting to prevent duplicate processing which could
 * cause exponential reward growth.
 */
object JobsModuleListener : Listener {
    /**
     * Fingerprint of a processed event.
     * Events with the same fingerprint within the cache TTL are considered duplicates.
     */
    private data class EventFingerprint(
        val playerUuid: UUID,
        val jobName: String,
        val type: RewardType,
        val originalValue: Long, // Store as long bits to avoid floating point comparison issues
    )

    private enum class RewardType { EXP, MONEY, POINTS }

    /**
     * Cache of recently processed events.
     * If an event fingerprint exists in cache, we skip processing to avoid duplicates.
     *
     * Short TTL (500ms) is enough because duplicate events happen within the same tick.
     */
    private val processedEvents: Cache<EventFingerprint, Boolean> =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(500, TimeUnit.MILLISECONDS)
            .maximumSize(1000)
            .build()

    /**
     * Check if event was already processed and mark it as processed.
     * @return true if this is a duplicate (should skip), false if it's new (should process)
     */
    private fun isDuplicate(fingerprint: EventFingerprint): Boolean {
        val existing = processedEvents.getIfPresent(fingerprint)
        if (existing != null) {
            return true
        }
        processedEvents.put(fingerprint, true)
        return false
    }

    /**
     * Create fingerprint for a reward value.
     * Uses Double.toBits() for exact comparison without floating point issues.
     */
    private fun fingerprint(
        playerUuid: UUID,
        jobName: String,
        type: RewardType,
        value: Double,
    ) = EventFingerprint(playerUuid, jobName, type, value.toBits())

    @EventHandler
    fun onJobsExp(event: JobsExpGainEvent) {
        val player = event.player as? Player ?: return
        val originalExp = event.exp

        // Check for duplicate event
        val fingerprint = fingerprint(player.uniqueId, event.job.name, RewardType.EXP, originalExp)
        if (isDuplicate(fingerprint)) return

        // Get custom boost from our system
        val data = JobsModule.getBoostData(player.uniqueId) ?: return
        val customBoost = data.getBoost(event.job, BoostType.EXP)

        // No custom boost applied (boost = 1.0 means no change)
        if (abs(customBoost - 1.0) < 0.000001) return

        // Get Jobs' built-in boost for this player
        val baseBoost = JobsModule.getBoost(player, event.job.name, BoostType.EXP)

        // Calculate the modified exp that will result in our desired total boost
        // Jobs will apply baseBoost to whatever we set, so we need to compensate
        val totalBoost = baseBoost + customBoost - 1.0
        val targetExp = originalExp * (totalBoost + 1.0)
        val adjustedExp = targetExp / (1.0 + baseBoost)

        event.exp = adjustedExp
    }

    @EventHandler
    fun onJobsPay(event: JobsPrePaymentEvent) {
        val player = event.player as? Player ?: return
        val originalMoney = event.amount
        val originalPoints = event.points

        // Get custom boosts from our system
        val data = JobsModule.getBoostData(player.uniqueId)
        val customMoneyBoost = data?.getBoost(event.job, BoostType.MONEY) ?: 1.0
        val customPointsBoost = data?.getBoost(event.job, BoostType.POINTS) ?: 1.0

        val hasCustomMoneyBoost = abs(customMoneyBoost - 1.0) > 0.000001
        val hasCustomPointsBoost = abs(customPointsBoost - 1.0) > 0.000001

        // Get Jobs' built-in boosts
        val baseMoneyBoost = JobsModule.getBoost(player, event.job.name, BoostType.MONEY)
        val basePointsBoost = JobsModule.getBoost(player, event.job.name, BoostType.POINTS)

        // Process money boost
        if (hasCustomMoneyBoost) {
            val moneyFingerprint = fingerprint(player.uniqueId, event.job.name, RewardType.MONEY, originalMoney)
            if (!isDuplicate(moneyFingerprint)) {
                val totalBoost = baseMoneyBoost + customMoneyBoost - 1.0
                val targetMoney = originalMoney * (totalBoost + 1.0)
                val adjustedMoney = targetMoney / (1.0 + baseMoneyBoost)

                event.amount = adjustedMoney
                AuditManager.operation(player.name, targetMoney, Type.JOB, event.job.name)
            }
        } else {
            // No custom boost, just audit the base amount
            AuditManager.operation(player.name, originalMoney * (baseMoneyBoost + 1.0), Type.JOB, event.job.name)
        }

        // Process points boost
        if (hasCustomPointsBoost) {
            val pointsFingerprint = fingerprint(player.uniqueId, event.job.name, RewardType.POINTS, originalPoints)
            if (!isDuplicate(pointsFingerprint)) {
                val totalBoost = basePointsBoost + customPointsBoost - 1.0
                val targetPoints = originalPoints * (totalBoost + 1.0)
                val adjustedPoints = targetPoints / (1.0 + basePointsBoost)

                event.points = adjustedPoints
            }
        }
    }
}
