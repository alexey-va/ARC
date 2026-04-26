package ru.arc.treasure.core

import java.util.concurrent.ThreadLocalRandom

/**
 * A pool of treasures with weighted random selection.
 * Immutable by design - all modifications create new instances.
 */
data class TreasurePool(
    val id: String,
    val treasures: List<Treasure> = emptyList(),
    val messages: List<TreasureMessage> = emptyList(),
    val isDirty: Boolean = true,
) {
    /** Total weight of all treasures in this pool */
    val totalWeight: Int
        get() = treasures.sumOf { it.weight }

    /** Number of treasures in this pool */
    val size: Int
        get() = treasures.size

    /** Whether this pool has no treasures */
    fun isEmpty(): Boolean = treasures.isEmpty()

    // ==================== Modification Methods ====================

    /**
     * Adds a treasure to the pool.
     */
    fun add(treasure: Treasure): TreasurePool = copy(treasures = treasures + treasure, isDirty = true)

    /**
     * Removes a treasure by ID.
     */
    fun remove(treasureId: String): TreasurePool = copy(treasures = treasures.filter { it.id != treasureId }, isDirty = true)

    /**
     * Removes a treasure by reference (using its ID).
     */
    fun remove(treasure: Treasure): TreasurePool = remove(treasure.id)

    /**
     * Updates a treasure in the pool.
     */
    fun update(updated: Treasure): TreasurePool =
        copy(
            treasures = treasures.map { if (it.id == updated.id) updated else it },
            isDirty = true,
        )

    /**
     * Creates a copy with new pool messages.
     * These messages are sent when ANY treasure from this pool is given,
     * unless the treasure has its own messages.
     */
    fun withMessages(messages: List<TreasureMessage>): TreasurePool = copy(messages = messages, isDirty = true)

    /**
     * Adds a pool message.
     */
    fun addMessage(message: TreasureMessage): TreasurePool = copy(messages = messages + message, isDirty = true)

    /**
     * Clears all pool messages.
     */
    fun clearMessages(): TreasurePool = copy(messages = emptyList(), isDirty = true)

    /**
     * Marks this pool as clean (not modified since last save).
     */
    fun markClean(): TreasurePool = copy(isDirty = false)

    // ==================== Query Methods ====================

    /**
     * Finds a treasure by ID.
     */
    fun findById(treasureId: String): Treasure? = treasures.find { it.id == treasureId }

    /**
     * Checks if the pool contains a treasure with the given ID.
     */
    fun contains(treasureId: String): Boolean = treasures.any { it.id == treasureId }

    /**
     * Selects a random treasure based on weights.
     * Returns null if pool is empty or all treasures have zero weight.
     */
    fun random(): Treasure? {
        val effectiveTreasures = treasures.filter { it.weight > 0 }
        if (effectiveTreasures.isEmpty()) return null

        val totalWeight = effectiveTreasures.sumOf { it.weight }
        if (totalWeight <= 0) return null

        val roll = ThreadLocalRandom.current().nextInt(totalWeight)
        var cumulative = 0

        for (treasure in effectiveTreasures) {
            cumulative += treasure.weight
            if (roll < cumulative) return treasure
        }

        return effectiveTreasures.lastOrNull()
    }

    // ==================== Serialization ====================

    /**
     * Serializes this pool to a map for YAML storage.
     */
    fun toMap(): Map<String, Any?> =
        buildMap {
            put("id", id)
            if (messages.isNotEmpty()) {
                put("messages", messages.map { it.toMap() })
            }
            put("treasures", treasures.map { it.toMap() })
        }

    companion object {
        /**
         * Deserializes a pool from a map.
         */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any?>): TreasurePool? {
            val id = map["id"] as? String ?: return null

            // Parse new message format
            val messages =
                (map["messages"] as? List<*>)
                    ?.mapNotNull { (it as? Map<String, Any?>)?.let { m -> TreasureMessage.fromMap(m) } }
                    ?: emptyList()

            // Legacy migration
            val legacyMessages =
                if (messages.isEmpty()) {
                    val commonMessage = map["commonMessage"] as? String
                    val commonAnnounceMessage = map["commonAnnounceMessage"] as? String
                    val commonAnnounce = map["commonAnnounce"] as? Boolean ?: false
                    TreasureMessage.fromLegacy(commonMessage, commonAnnounceMessage, commonAnnounce)
                } else {
                    messages
                }

            val treasuresList =
                (map["treasures"] as? List<*>)?.mapNotNull { entry ->
                    (entry as? Map<String, Any?>)?.let { Treasure.fromMap(it) }
                } ?: emptyList()

            return TreasurePool(
                id = id,
                treasures = treasuresList,
                messages = legacyMessages,
                isDirty = false, // Loaded pools start clean
            )
        }
    }
}
