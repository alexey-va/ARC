package ru.arc.audit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.ContextAwareRepository
import ru.arc.core.InMemoryRepository
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

/**
 * Repository interface for audit data.
 */
interface AuditRepository : ContextAwareRepository<AuditData, String> {

    /**
     * Save all data to persistent storage.
     */
    fun saveAll()

    /**
     * Shutdown the repository.
     */
    fun shutdown()
}

/**
 * Production implementation using CachedRepository.
 */
class RedisAuditRepository private constructor(
    private val repo: CachedRepository<AuditData>,
    private val scope: CoroutineScope,
) : AuditRepository {
    private val contextIds = mutableSetOf<String>()

    override fun get(id: String): CompletableFuture<AuditData?> {
        val future = CompletableFuture<AuditData?>()
        scope.launch {
            try {
                val result = repo.get(id.lowercase())
                future.complete(result.getOrNull())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> {
        val future = CompletableFuture<AuditData>()
        scope.launch {
            try {
                val result = repo.getOrCreate(id.lowercase()) { factory() }
                future.complete(result.getOrThrow())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return future
    }

    override fun save(entity: AuditData) {
        entity.isDirty = true
        scope.launch {
            repo.save(entity)
        }
    }

    override fun delete(id: String) {
        scope.launch {
            val data = repo.get(id.lowercase()).getOrNull()
            if (data != null) {
                data.transactions.clear()
                data.isDirty = true
                repo.save(data)
            }
        }
    }

    override fun all(): Collection<AuditData> =
        runBlocking {
            repo.all().getOrNull() ?: emptyList()
        }

    override fun exists(id: String): Boolean =
        runBlocking {
            repo.get(id.lowercase()).getOrNull() != null
        }

    override fun count(): Int =
        runBlocking {
            repo.all().getOrNull()?.size ?: 0
        }

    override fun clear() {
        scope.launch {
            repo.all().getOrNull()?.forEach {
                it.transactions.clear()
                it.isDirty = true
                repo.save(it)
            }
        }
    }

    override fun addContext(id: String) {
        val lowerId = id.lowercase()
        contextIds.add(lowerId)
        repo.addContext(lowerId)
    }

    override fun removeContext(id: String) {
        val lowerId = id.lowercase()
        contextIds.remove(lowerId)
        repo.removeContext(lowerId)
    }

    override fun getContext(): Set<String> = contextIds.toSet()

    override fun saveAll() {
        runBlocking {
            repo.saveDirty()
        }
    }

    override fun shutdown() {
        runBlocking {
            repo.shutdown()
        }
    }

    companion object {
        /**
         * Create repository with default configuration.
         */
        fun create(): RedisAuditRepository {
            val config = ConfigManager.of(ARC.instance.dataPath, "audit.yml")
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            val saveIntervalTicks = config.integer("save-interval", 20).toLong()
            val saveIntervalMs = saveIntervalTicks * 50 // Convert ticks to ms

            val repo =
                redisRepo<AuditData>(
                    id = "audit",
                    storageKey = "arc.audits",
                    updateChannel = "arc.audit-update",
                    scope = scope,
                ) {
                    loadAllOnStart(false)
                    saveInterval((saveIntervalMs / 1000).seconds)
                }

            return RedisAuditRepository(repo, scope)
        }
    }
}

/**
 * In-memory implementation for testing.
 */
class InMemoryAuditRepository : InMemoryRepository<AuditData, String>({ it.id() }), AuditRepository {

    override fun get(id: String): CompletableFuture<AuditData?> {
        return super.get(id.lowercase())
    }

    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> {
        return super.getOrCreate(id.lowercase(), factory)
    }

    override fun saveAll() {
        // No-op for in-memory
    }

    override fun shutdown() {
        // No-op for in-memory
    }
}
