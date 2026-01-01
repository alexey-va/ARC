package ru.arc.audit

import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.core.ContextAwareRepository
import ru.arc.core.InMemoryRepository
import ru.arc.network.repos.RedisRepo
import java.util.concurrent.CompletableFuture

/**
 * Repository interface for audit data.
 */
interface AuditRepository : ContextAwareRepository<AuditData, String> {

    /**
     * Save all data to persistent storage.
     */
    fun saveAll()
}

/**
 * Production implementation using RedisRepo.
 */
class RedisAuditRepository private constructor(
    private val repo: RedisRepo<AuditData>
) : AuditRepository {

    override fun get(id: String): CompletableFuture<AuditData?> {
        return repo.getOrNull(id.lowercase())
    }

    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> {
        return repo.getOrCreate(id.lowercase(), factory)
    }

    override fun save(entity: AuditData) {
        entity.isDirty = true
    }

    override fun delete(id: String) {
        repo.getOrNull(id.lowercase()).thenAccept { data ->
            data?.transactions?.clear()
            data?.isDirty = true
        }
    }

    override fun all(): Collection<AuditData> = repo.all()

    override fun exists(id: String): Boolean = repo.all().any { it.id() == id.lowercase() }

    override fun count(): Int = repo.all().size

    override fun clear() {
        repo.all().forEach {
            it.transactions.clear()
            it.isDirty = true
        }
    }

    override fun addContext(id: String) {
        repo.addContext(id.lowercase())
    }

    override fun removeContext(id: String) {
        repo.removeContext(id.lowercase())
    }

    override fun getContext(): Set<String> {
        return emptySet() // RedisRepo doesn't expose context
    }

    override fun saveAll() {
        repo.forceSave()
    }

    companion object {
        /**
         * Create repository with default configuration.
         */
        fun create(): RedisAuditRepository {
            val config = ConfigManager.of(ARC.plugin.dataPath, "audit.yml")

            val repo = RedisRepo.builder(AuditData::class.java)
                .saveBackups(false)
                .id("audit")
                .redisManager(ARC.redisManager)
                .storageKey("arc.audits")
                .saveInterval(config.integer("save-interval", 20).toLong())
                .updateChannel("arc.audit-update")
                .loadAll(false)
                .build()

            return RedisAuditRepository(repo)
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
}

