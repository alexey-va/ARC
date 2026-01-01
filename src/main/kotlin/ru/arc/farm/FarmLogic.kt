package ru.arc.farm

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily block limit tracker for players.
 *
 * Tracks how many blocks each player has broken and enforces daily limits.
 * Fully testable - no Bukkit dependencies.
 */
class BlockLimitTracker(
    private val maxBlocks: Int,
    private val progressInterval: Int = 64
) {
    private val blocksBroken = ConcurrentHashMap<UUID, Int>()

    /**
     * Check if player has reached their daily limit.
     */
    fun hasReachedLimit(playerId: UUID): Boolean {
        return blocksBroken.getOrDefault(playerId, 0) >= maxBlocks
    }

    /**
     * Increment block count for player.
     * @return Current count after increment
     */
    fun incrementBlocks(playerId: UUID): Int {
        return blocksBroken.merge(playerId, 1, Int::plus) ?: 1
    }

    /**
     * Get current block count for player.
     */
    fun getBlockCount(playerId: UUID): Int {
        return blocksBroken.getOrDefault(playerId, 0)
    }

    /**
     * Check if progress message should be shown.
     */
    fun shouldShowProgress(playerId: UUID): Boolean {
        val count = blocksBroken.getOrDefault(playerId, 0)
        return count > 0 && count % progressInterval == 0 && count != maxBlocks
    }

    /**
     * Reset all player limits (daily reset).
     */
    fun resetAll() {
        blocksBroken.clear()
    }

    /**
     * Reset limit for specific player.
     */
    fun reset(playerId: UUID) {
        blocksBroken.remove(playerId)
    }

    /**
     * Get all players with their block counts.
     */
    fun getAllCounts(): Map<UUID, Int> = blocksBroken.toMap()
}

/**
 * Temporary block tracker with expiration.
 *
 * Tracks blocks that have been broken and should regenerate after a timeout.
 */
class TemporaryBlockTracker<T : Any>(
    private val expireTimeMs: Long,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class TrackedBlock<T>(
        val block: T,
        val timestamp: Long
    )

    private val blocks = mutableListOf<TrackedBlock<T>>()

    /**
     * Add a block to tracking.
     */
    fun add(block: T) {
        blocks.add(TrackedBlock(block, timeProvider()))
    }

    /**
     * Get and remove all expired blocks.
     */
    fun getExpired(): List<T> {
        val currentTime = timeProvider()
        val expired = blocks.filter { currentTime - it.timestamp > expireTimeMs }
        blocks.removeAll(expired)
        return expired.map { it.block }
    }

    /**
     * Check if a block is currently tracked (not yet expired).
     */
    fun isTracked(block: T): Boolean {
        return blocks.any { it.block == block }
    }

    /**
     * Get count of tracked blocks.
     */
    fun count(): Int = blocks.size

    /**
     * Clear all tracked blocks.
     */
    fun clear() {
        blocks.clear()
    }
}

/**
 * Region bounds checker.
 *
 * Simple AABB check for coordinates.
 */
data class RegionBounds(
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    /**
     * Check if point is within bounds.
     */
    fun contains(x: Int, y: Int, z: Int): Boolean {
        return x in minX..maxX && y in minY..maxY && z in minZ..maxZ
    }

    /**
     * Get volume of region.
     */
    fun volume(): Long {
        return (maxX - minX + 1L) * (maxY - minY + 1L) * (maxZ - minZ + 1L)
    }
}

