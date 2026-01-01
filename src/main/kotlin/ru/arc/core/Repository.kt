package ru.arc.core

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic repository interface for data persistence.
 *
 * Abstracts storage mechanism (Redis, file, memory) for testability.
 *
 * @param T The entity type
 * @param ID The ID type (usually String)
 */
interface Repository<T, ID> {
    /**
     * Get entity by ID, or null if not found.
     */
    fun get(id: ID): CompletableFuture<T?>

    /**
     * Get entity by ID, creating with factory if not exists.
     */
    fun getOrCreate(id: ID, factory: () -> T): CompletableFuture<T>

    /**
     * Save entity.
     */
    fun save(entity: T)

    /**
     * Delete entity by ID.
     */
    fun delete(id: ID)

    /**
     * Get all entities.
     */
    fun all(): Collection<T>

    /**
     * Check if entity exists.
     */
    fun exists(id: ID): Boolean

    /**
     * Count total entities.
     */
    fun count(): Int

    /**
     * Clear all entities.
     */
    fun clear()
}

/**
 * Repository with context tracking (e.g., online players).
 */
interface ContextAwareRepository<T, ID> : Repository<T, ID> {
    /**
     * Add ID to active context (e.g., player joined).
     */
    fun addContext(id: ID)

    /**
     * Remove ID from active context (e.g., player left).
     */
    fun removeContext(id: ID)

    /**
     * Get all IDs in active context.
     */
    fun getContext(): Set<ID>
}

/**
 * In-memory repository implementation for testing.
 */
open class InMemoryRepository<T : Any, ID : Any>(
    private val idExtractor: (T) -> ID
) : ContextAwareRepository<T, ID> {

    protected val data = ConcurrentHashMap<ID, T>()
    protected val context = ConcurrentHashMap.newKeySet<ID>()

    override fun get(id: ID): CompletableFuture<T?> {
        return CompletableFuture.completedFuture(data[id])
    }

    override fun getOrCreate(id: ID, factory: () -> T): CompletableFuture<T> {
        return CompletableFuture.completedFuture(
            data.computeIfAbsent(id) { factory() }
        )
    }

    override fun save(entity: T) {
        data[idExtractor(entity)] = entity
    }

    override fun delete(id: ID) {
        data.remove(id)
    }

    override fun all(): Collection<T> = data.values.toList()

    override fun exists(id: ID): Boolean = data.containsKey(id)

    override fun count(): Int = data.size

    override fun clear() {
        data.clear()
        context.clear()
    }

    override fun addContext(id: ID) {
        context.add(id)
    }

    override fun removeContext(id: ID) {
        context.remove(id)
    }

    override fun getContext(): Set<ID> = context.toSet()
}

