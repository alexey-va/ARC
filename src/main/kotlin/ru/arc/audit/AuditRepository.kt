package ru.arc.audit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Minimal persistence boundary required by [AuditService].
 */
interface AuditRepository {
    fun get(id: String): CompletableFuture<AuditData?>

    fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData>

    fun save(entity: AuditData)

    fun all(): Collection<AuditData>

    fun addContext(id: String)

    fun removeContext(id: String)

    fun getContext(): Set<String>

    fun shutdown()
}

/**
 * Production implementation using CachedRepository.
 */
class RedisAuditRepository private constructor(
    private val repo: CachedRepository<AuditData>,
    private val scope: CoroutineScope,
) : AuditRepository {
    private val contextIds = ConcurrentHashMap.newKeySet<String>()

    override fun get(id: String): CompletableFuture<AuditData?> =
        scope.future {
            repo.get(id.lowercase()).getOrThrow()
        }

    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> =
        scope.future {
            repo.getOrCreate(id.lowercase()) { factory() }.getOrThrow()
        }

    override fun save(entity: AuditData) {
        repo.markDirty(entity)
    }

    override fun all(): Collection<AuditData> = repo.allNow()

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
                    // Weight, pruning and clear-all operate on the complete audit set.
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval((saveIntervalMs / 1000).seconds)
                }

            return RedisAuditRepository(repo, scope)
        }
    }
}

/**
 * In-memory implementation for testing.
 */
class InMemoryAuditRepository : AuditRepository {
    private val data = ConcurrentHashMap<String, AuditData>()
    private val contexts = ConcurrentHashMap.newKeySet<String>()

    override fun get(id: String): CompletableFuture<AuditData?> =
        CompletableFuture.completedFuture(data[id.lowercase()])

    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> =
        CompletableFuture.completedFuture(
            data.computeIfAbsent(id.lowercase()) {
                factory().also { created ->
                    require(created.id() == id.lowercase()) {
                        "Created audit id '${created.id()}' does not match requested id '${id.lowercase()}'"
                    }
                }
            },
        )

    override fun save(entity: AuditData) {
        data[entity.id()] = entity
    }

    override fun all(): Collection<AuditData> = data.values.toList()

    override fun addContext(id: String) {
        contexts.add(id.lowercase())
    }

    override fun removeContext(id: String) {
        contexts.remove(id.lowercase())
    }

    override fun getContext(): Set<String> = contexts.toSet()

    override fun shutdown() = Unit
}
