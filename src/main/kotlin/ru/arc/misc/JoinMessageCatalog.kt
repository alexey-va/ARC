package ru.arc.misc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.bukkit.Material
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.hours

data class JoinMessageCatalogEntry(
    @JvmField var id: String = "",
    @JvmField var message: String = "",
    @JvmField var displayName: String = "",
    @JvmField var material: String = "PAPER",
    @JvmField var customModelData: Int = 0,
    @JvmField var permission: String? = null,
    @JvmField var rank: String = "<italic:false><green>Для всех",
)

class JoinMessageCatalog(
    @JvmField var catalogId: String = CATALOG_ID,
    @JvmField var schemaVersion: Int = SCHEMA_VERSION,
    @JvmField var revision: String = "",
    @JvmField var updatedAt: Long = 0,
    @JvmField var join: List<JoinMessageCatalogEntry> = emptyList(),
    @JvmField var leave: List<JoinMessageCatalogEntry> = emptyList(),
) : Entity,
    Mergeable<JoinMessageCatalog> {
    override fun id(): String = catalogId

    @Synchronized
    override fun merge(other: JoinMessageCatalog) {
        schemaVersion = other.schemaVersion
        revision = other.revision
        updatedAt = other.updatedAt
        join = other.join.map(JoinMessageCatalogEntry::copy)
        leave = other.leave.map(JoinMessageCatalogEntry::copy)
    }

    fun entries(isJoin: Boolean): List<JoinMessageCatalogEntry> = if (isJoin) join else leave

    fun validate() {
        require(catalogId == CATALOG_ID) { "Unexpected join message catalog id: $catalogId" }
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported join message catalog schema: $schemaVersion" }
        require(revision.isNotBlank() && revision.length <= 128) { "Invalid join message catalog revision" }
        require(join.isNotEmpty() && leave.isNotEmpty()) { "Join message catalog must contain both phrase groups" }
        require(join.size <= MAX_ENTRIES_PER_KIND && leave.size <= MAX_ENTRIES_PER_KIND) {
            "Join message catalog is too large"
        }
        val all = join + leave
        require(all.map(JoinMessageCatalogEntry::id).distinct().size == all.size) {
            "Join message catalog contains duplicate ids"
        }
        all.forEach { entry ->
            require(SAFE_ID.matches(entry.id)) { "Invalid join message id: ${entry.id}" }
            require(entry.message.isNotBlank() && entry.message.length <= MAX_MESSAGE_LENGTH) {
                "Invalid text for join message '${entry.id}'"
            }
            require(entry.displayName.isNotBlank() && entry.displayName.length <= MAX_DISPLAY_LENGTH) {
                "Invalid display name for join message '${entry.id}'"
            }
            require(entry.material.matches(MATERIAL_NAME)) { "Invalid material name for join message '${entry.id}'" }
            require(entry.customModelData >= 0) { "Invalid custom model data for join message '${entry.id}'" }
            require(entry.rank.isNotBlank() && entry.rank.length <= MAX_DISPLAY_LENGTH) {
                "Invalid rank label for join message '${entry.id}'"
            }
        }
    }

    companion object {
        const val CATALOG_ID = "catalog"
        const val SCHEMA_VERSION = 1
        private const val MAX_ENTRIES_PER_KIND = 100
        private const val MAX_MESSAGE_LENGTH = 512
        private const val MAX_DISPLAY_LENGTH = 256
        private val SAFE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val MATERIAL_NAME = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

internal object JoinMessageMaterial {
    fun resolve(raw: String): Material = Material.matchMaterial(raw.uppercase()) ?: Material.PAPER
}

object JoinMessageCatalogManager {
    private const val UNAVAILABLE_MESSAGE = "Join message catalog is unavailable"
    private lateinit var repo: CachedRepository<JoinMessageCatalog>
    private lateinit var scope: CoroutineScope

    @Volatile
    private var initialized = false

    @JvmStatic
    @Synchronized
    fun init() {
        if (initialized || ru.arc.ARC.redisManager == null) return
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repo =
            redisRepo<JoinMessageCatalog>(
                id = "join_message_catalog",
                storageKey = "arc.join_message_catalog",
                updateChannel = "arc.join_message_catalog_update",
                scope = scope,
            ) {
                loadAllOnStart(true)
                enableCleanup(false)
                saveInterval(1.hours)
            }
        initialized = true
    }

    @JvmStatic
    fun currentAsync(): CompletableFuture<JoinMessageCatalog> =
        if (initialized) {
            scope.future {
                val catalog = repo.get(JoinMessageCatalog.CATALOG_ID).getOrThrow()
                    ?: error(UNAVAILABLE_MESSAGE)
                catalog.validate()
                catalog
            }
        } else {
            CompletableFuture.failedFuture(IllegalStateException(UNAVAILABLE_MESSAGE))
        }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        if (!initialized) return
        initialized = false
        runBlocking { repo.shutdown() }
        scope.cancel()
    }
}
