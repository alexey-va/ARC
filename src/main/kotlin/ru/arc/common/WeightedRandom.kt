package ru.arc.common

import ru.arc.util.Logging.info
import java.util.TreeMap
import java.util.concurrent.ThreadLocalRandom

class WeightedRandom<T> {
    data class Pair<T>(val value: T, val weight: Double)

    private val map = TreeMap<Double, Pair<T>>()
    private var totalWeight = 0.0

    fun values(): Collection<T> {
        return map.values.map { it.value }
    }

    fun add(value: T, weight: Double) {
        if (weight <= 0 || !weight.isFinite()) {
            // Reject zero, negative, NaN, and Infinity weights
            return
        }
        totalWeight += weight
        // Check for overflow/underflow
        if (!totalWeight.isFinite()) {
            // Rollback if totalWeight becomes invalid
            totalWeight -= weight
            info("Weight addition would cause overflow, rejecting weight: {}", weight)
            return
        }
        map[totalWeight] = Pair(value, weight)
    }

    fun size(): Int {
        return map.size
    }

    fun random(): T? {
        if (map.isEmpty() || totalWeight <= 0) {
            info("Random called on empty WeightedRandom")
            return null
        }
        val value = ThreadLocalRandom.current().nextDouble(0.0, totalWeight)
        val entry = map.ceilingEntry(value)
        if (entry == null) {
            // Edge case: should not happen, but handle gracefully
            info("No entry found for random value: {}", value)
            return null
        }
        return entry.value.value
    }

    fun getNRandom(n: Int): Set<T> {
        if (map.isEmpty() || totalWeight <= 0) {
            info("NRandom called on empty WeightedRandom")
            return emptySet()
        }
        if (n <= 0) {
            return emptySet()
        }

        // If requesting all or more items than available, return all unique items
        val availableCount = map.size
        if (n >= availableCount) {
            return values().toSet()
        }

        val result = mutableSetOf<T>()
        // Try multiple times to get unique items
        for (attempt in 0..<100) {
            val values = ThreadLocalRandom.current()
                .doubles(0.0, totalWeight)
                .limit(n.toLong())
                .sorted()
                .boxed()
                .toList()
            for (value in values) {
                val entry = map.ceilingEntry(value)
                if (entry != null) {
                    result.add(entry.value.value)
                    if (result.size == n) return result
                }
            }
            // If we got enough items, return early
            if (result.size == n) return result
        }

        // If we still don't have enough after many attempts, return what we have
        if (result.size < n) {
            info(
                "Not enough unique values in WeightedRandom after 100 attempts. Got: {}, Requested: {}",
                result.size,
                n
            )
        }
        return result
    }

    fun remove(value: T): Boolean {
        val remaining = mutableListOf<Pair<T>>()
        var found = false
        for (pair in map.values) {
            if (pair.value == value) {
                found = true
            } else {
                remaining.add(pair)
            }
        }
        if (!found) return false
        map.clear()
        totalWeight = 0.0
        for (pair in remaining) {
            add(pair.value, pair.weight)
        }
        return true
    }
}

