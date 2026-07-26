package ru.arc.jobs

import com.gamingmesh.jobs.Jobs
import com.gamingmesh.jobs.container.CurrencyType
import com.gamingmesh.jobs.container.Job
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.*
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.repository.CachedRepository
import ru.arc.repository.Entity
import ru.arc.repository.Mergeable
import ru.arc.repository.redisRepo
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Represents which job(s) a boost applies to.
 */
sealed interface JobTarget {
    /** Boost applies to all jobs */
    data object All : JobTarget

    /** Boost applies to a specific job */
    data class Specific(
        val name: String,
    ) : JobTarget

    fun matches(jobName: String): Boolean =
        when (this) {
            is All -> true
            is Specific -> name.equals(jobName, ignoreCase = true)
        }

    fun displayName(): String =
        when (this) {
            is All -> "all"
            is Specific -> name
        }

    companion object {
        /** Parse job target from string. "all" or empty = All, otherwise Specific */
        fun parse(value: String?): JobTarget =
            when {
                value.isNullOrBlank() -> All
                value.equals("all", ignoreCase = true) -> All
                else -> Specific(value)
            }
    }
}

/**
 * Jobs boost data class.
 * Represents a single boost applied to a player for specific job(s) and type(s).
 */
data class JobsBoostData(
    /** Boost multiplier (0.5 = +50%, 1.0 = +100%) */
    val boost: Double = 0.0,
    /** Type of reward this boost applies to */
    val type: BoostType = BoostType.ALL,
    /** Which job(s) this boost applies to */
    val jobTarget: JobTarget = JobTarget.All,
    /** Expiration timestamp in milliseconds */
    val expires: Long = 0,
    /** UUID of the player who has this boost */
    val boostUuid: UUID = UUID.randomUUID(),
    /** Unique identifier for this boost */
    val id: String = "",
) {
    fun expiresInMillis(nowMillis: Long = System.currentTimeMillis()): Long = expires - nowMillis

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean = expires <= nowMillis

    /**
     * Check if this boost applies to the given job.
     */
    fun appliesTo(job: Job): Boolean = appliesToJob(job.name)

    /**
     * Check if this boost applies to the given job name.
     */
    fun appliesToJob(targetJobName: String): Boolean = jobTarget.matches(targetJobName)

    /**
     * Check if this boost applies to the given type.
     */
    fun appliesTo(targetType: BoostType): Boolean = type == BoostType.ALL || type == targetType
}

/**
 * Boost type enum.
 */
enum class BoostType(
    val display: String,
) {
    MONEY("Деньги"),
    EXP("Опыт"),
    POINTS("Очки"),
    ALL("Все"),
}

/**
 * Boost data for a player.
 * Thread-safe container for all boosts a player has.
 */
class BoostDataEntity(
    var player: UUID = UUID.randomUUID(),
    boosts: Set<JobsBoostData> = emptySet(),
) : Entity,
    Mergeable<BoostDataEntity> {
    // Thread-safe set for boosts
    private val _boosts: MutableSet<JobsBoostData> =
        ConcurrentHashMap.newKeySet<JobsBoostData>().apply {
            addAll(boosts)
        }

    /** Detached read-only snapshot of boosts for serialization and callers. */
    @get:Synchronized
    val boosts: Set<JobsBoostData> get() = _boosts.toSet()

    @Transient
    private val boostCache: Cache<BoostContext, Double> =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build()

    private data class BoostContext(
        val jobName: String,
        val type: BoostType,
    )

    override fun id(): String = player.toString()

    override fun merge(other: BoostDataEntity) {
        if (this === other) return
        val incoming = other.boosts
        synchronized(this) {
            _boosts.clear()
            _boosts.addAll(incoming)
            boostCache.invalidateAll()
        }
    }

    /**
     * Remove expired boosts.
     * @return true if any boosts were removed
     */
    @Synchronized
    fun removeExpired(): Boolean {
        val removed = _boosts.removeIf { it.isExpired() }
        if (removed) {
            boostCache.invalidateAll()
        }
        return removed
    }

    /**
     * Calculate total boost multiplier for a job and type.
     * @return Multiplier (1.0 = no boost, 1.5 = +50%, 2.0 = +100%)
     */
    fun getBoost(
        job: Job,
        type: BoostType,
    ): Double = getBoost(job.name, type)

    /**
     * Calculate total boost multiplier for a job name and type.
     * @return Multiplier (1.0 = no boost, 1.5 = +50%, 2.0 = +100%)
     */
    @Synchronized
    fun getBoost(
        jobName: String,
        type: BoostType,
    ): Double {
        removeExpired()

        val context = BoostContext(jobName, type)
        boostCache.getIfPresent(context)?.let { return it }

        val boost =
            1.0 +
                _boosts
                    .filter { it.appliesTo(type) && it.appliesToJob(jobName) }
                    .sumOf { it.boost }

        boostCache.put(context, boost)
        return boost
    }

    /**
     * Find a boost by its ID.
     */
    @Synchronized
    fun findById(id: String): JobsBoostData? {
        removeExpired()
        return _boosts.find { it.id == id }
    }

    /**
     * Check if a boost with the given ID exists.
     */
    @Synchronized
    fun hasBoostWithId(id: String): Boolean {
        removeExpired()
        return _boosts.any { it.id == id }
    }

    /**
     * Get all boosts applicable to a job (for GUI display).
     */
    fun boostsForJob(job: Job): List<JobsBoostData> = boostsForJob(job.name)

    /**
     * Get all boosts applicable to a job name.
     */
    @Synchronized
    fun boostsForJob(jobName: String): List<JobsBoostData> {
        removeExpired()
        return _boosts.filter { it.appliesToJob(jobName) }.toList()
    }

    /**
     * Get all active (non-expired) boosts.
     */
    @Synchronized
    fun activeBoosts(): List<JobsBoostData> {
        removeExpired()
        return _boosts.toList()
    }

    /**
     * Add a new boost.
     * @return true if boost was added, false if a boost with same ID already exists
     */
    @Synchronized
    fun addBoost(boost: JobsBoostData): Boolean {
        removeExpired()

        if (hasBoostWithId(boost.id)) {
            error("Boost with id {} already exists for {}", boost.id, player)
            return false
        }

        _boosts.add(boost)
        boostCache.invalidateAll()
        return true
    }

    /**
     * Remove a boost by ID.
     * @return true if boost was removed
     */
    @Synchronized
    fun removeBoost(boostId: String): Boolean {
        val removed = _boosts.removeIf { it.id == boostId }
        if (removed) {
            boostCache.invalidateAll()
        }
        return removed
    }

    /**
     * Remove only the exact boost IDs supplied by the caller.
     * Used to undo a partially prepared add without touching older variants.
     */
    @Synchronized
    fun removeBoostIds(boostIds: Set<String>): Int {
        if (boostIds.isEmpty()) return 0
        val sizeBefore = _boosts.size
        _boosts.removeIf { it.id in boostIds }
        val removed = sizeBefore - _boosts.size
        if (removed > 0) boostCache.invalidateAll()
        return removed
    }

    /**
     * Clear all boosts.
     */
    @Synchronized
    fun clearBoosts() {
        _boosts.clear()
        boostCache.invalidateAll()
    }

    /**
     * Get the count of active boosts.
     */
    @Synchronized
    fun boostCount(): Int {
        removeExpired()
        return _boosts.size
    }
}

/**
 * Jobs module for managing job boosts.
 *
 * Provides custom boost multipliers on top of Jobs plugin's built-in boosts.
 * Boosts are stored in Redis and synchronized across servers.
 */
object JobsModule {
    private data class JobsRuntime(
        val repo: CachedRepository<BoostDataEntity>,
        val config: Config,
        val scope: CoroutineScope,
    )

    @Volatile
    private var runtime: JobsRuntime? = null
    private var listenerRegistered = false

    private fun JobsRuntime.launchOperation(
        operation: String,
        block: suspend JobsRuntime.() -> Unit,
    ) {
        scope.launch {
            try {
                this@launchOperation.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error("Jobs operation '$operation' failed", failure)
            }
        }
    }

    private fun <T> JobsRuntime.submitOperation(
        operation: String,
        block: suspend JobsRuntime.() -> T,
    ): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        scope.launch {
            try {
                result.complete(this@submitOperation.block())
            } catch (cancelled: CancellationException) {
                result.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                error("Jobs operation '$operation' failed", failure)
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    @JvmStatic
    @Synchronized
    fun init(): Boolean {
        if (runtime != null) return true
        if (ru.arc.ARC.redisManager == null) return false

        info("Jobs hook enabled")

        val newConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "jobs.yml")
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newRepo =
            try {
                redisRepo<BoostDataEntity>(
                    id = "jobs",
                    storageKey = "arc.jobs_boosts",
                    updateChannel = "arc.jobs_boosts_update",
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    saveInterval(500.milliseconds)
                }
            } catch (failure: Throwable) {
                newScope.cancel()
                throw failure
            }

        try {
            if (!listenerRegistered) {
                Bukkit.getPluginManager().registerEvents(JobsModuleListener, ARC.instance)
                listenerRegistered = true
            }
        } catch (failure: Throwable) {
            try {
                runBlocking { newRepo.shutdown() }
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                newScope.cancel()
            }
            throw failure
        }

        runtime = JobsRuntime(newRepo, newConfig, newScope)
        return true
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        val current = runtime ?: return
        runtime = null
        try {
            runBlocking { current.repo.shutdown() }
        } finally {
            current.scope.cancel()
        }
    }

    @JvmStatic
    fun getConfig(): Config =
        runtime?.config ?: kotlin.error("JobsModule is not initialized")

    @JvmStatic
    fun jobDisplayMinimessage(jobName: String): String {
        val name =
            LegacyComponentSerializer
                .legacyAmpersand()
                .deserialize(
                    Jobs.getJob(jobName).displayName.replace("§", "&"),
                ).decoration(TextDecoration.ITALIC, false)
        return MiniMessage.miniMessage().serialize(name)
    }

    /**
     * Add boost(s) for a player.
     *
     * @param player Player UUID
     * @param jobs List of job names (empty or "all" = all jobs)
     * @param boost Boost multiplier (0.5 = +50%)
     * @param expires Expiration timestamp in milliseconds
     * @param boostId Base ID for the boost (will be suffixed for multiple jobs/types)
     * @param types List of boost types (empty = ALL)
     */
    @JvmStatic
    fun addBoost(
        player: UUID,
        jobs: List<String>,
        boost: Double,
        expires: Long,
        boostId: String,
        types: List<BoostType>,
    ): CompletableFuture<Boolean> {
        val current = runtime ?: return CompletableFuture.completedFuture(false)
        if (!isValidBoostRequest(boost, expires, boostId)) {
            return CompletableFuture.completedFuture(false)
        }

        val variants = boostVariants(jobs, types)

        return current.submitOperation("add boost") {
            val data =
                repo
                    .getOrCreate(player.toString()) {
                        BoostDataEntity(player)
                    }.getOrThrow()
            val expiredRemoved = data.removeExpired()

            info("Adding boost for player $player jobs $jobs boost $boost expires $expires boostId $boostId types $types")

            val addedIds = mutableSetOf<String>()
            for ((jobTarget, type) in variants) {
                val uniqueId = buildBoostId(boostId, jobTarget, type)

                val jobsBoost =
                    JobsBoostData(
                        boost = boost,
                        expires = expires,
                        boostUuid = player,
                        id = uniqueId,
                        type = type,
                        jobTarget = jobTarget,
                    )

                if (data.addBoost(jobsBoost)) {
                    addedIds += uniqueId
                }
            }

            if (addedIds.isNotEmpty()) {
                repo.markDirty(data)
                try {
                    repo.saveDirty().getOrThrow()
                } catch (failure: Exception) {
                    data.removeBoostIds(addedIds)
                    repo.markDirty(data)
                    throw failure
                }
            } else if (expiredRemoved) {
                repo.markDirty(data)
            }
            if (addedIds.isNotEmpty()) {
                info("Added ${addedIds.size} boost(s) for player $player")
            }
            addedIds.isNotEmpty()
        }
    }

    /**
     * Roll back every generated variant belonging to one shop boost ID.
     */
    @JvmStatic
    fun removeBoosts(
        player: UUID,
        boostId: String,
        jobs: List<String>,
        types: List<BoostType>,
    ): CompletableFuture<Boolean> {
        val current = runtime ?: return CompletableFuture.completedFuture(false)
        val boostIds = boostVariants(jobs, types).mapTo(mutableSetOf()) { (jobTarget, type) ->
            buildBoostId(boostId, jobTarget, type)
        }
        return current.submitOperation("remove boost") {
            val data = repo.get(player.toString()).getOrThrow() ?: return@submitOperation false
            val removed = data.removeBoostIds(boostIds)
            if (removed > 0) {
                repo.markDirty(data)
                repo.saveDirty().getOrThrow()
            }
            removed > 0
        }
    }

    /**
     * Build unique boost ID from base ID, job target, and type.
     */
    private fun buildBoostId(
        baseId: String,
        jobTarget: JobTarget,
        type: BoostType,
    ): String {
        val jobPart = jobTarget.displayName()
        val typePart = type.name.lowercase()
        return "${baseId}_${jobPart}_$typePart"
    }

    private fun boostVariants(
        jobs: List<String>,
        types: List<BoostType>,
    ): List<Pair<JobTarget, BoostType>> {
        val jobTargets =
            when {
                jobs.isEmpty() -> listOf(JobTarget.All)
                jobs.any { it.equals("all", ignoreCase = true) } -> listOf(JobTarget.All)
                else -> jobs.distinctBy { it.lowercase() }.map { JobTarget.Specific(it) }
            }
        val typesToApply =
            when {
                types.isEmpty() || BoostType.ALL in types -> listOf(BoostType.ALL)
                else -> types.distinct()
            }
        return jobTargets.flatMap { jobTarget ->
            typesToApply.map { type -> jobTarget to type }
        }
    }

    @JvmStatic
    fun getJobNames(): List<String> = Jobs.getJobs().map { it.name }

    @JvmStatic
    fun openBoostGui(player: Player) {
        val current = runtime ?: return
        GuiUtils.constructAndShowAsync(
            {
                _root_ide_package_.ru.arc.jobs.guis
                    .createJobsListGui(current.config, player)
            },
            player,
        )
    }

    /**
     * Check if player has a boost with the given base ID.
     * Checks for any boost that starts with the given ID prefix.
     */
    @JvmStatic
    fun hasBoost(
        player: OfflinePlayer,
        boostId: String,
    ): Boolean {
        val data = getBoostData(player.uniqueId) ?: return false
        return data.activeBoosts().any { it.id == boostId || it.id.startsWith("${boostId}_") }
    }

    /**
     * Check the exact variants configured for a shop entry.
     */
    @JvmStatic
    fun hasBoost(
        player: OfflinePlayer,
        boostId: String,
        jobs: List<String>,
        types: List<BoostType>,
    ): Boolean {
        val data = getBoostData(player.uniqueId) ?: return false
        return boostVariants(jobs, types).any { (jobTarget, type) ->
            data.hasBoostWithId(buildBoostId(boostId, jobTarget, type))
        }
    }

    /**
     * Get Jobs plugin's built-in boost for a player.
     */
    @JvmStatic
    fun getBoost(
        player: Player,
        jobName: String,
        type: BoostType,
    ): Double {
        val currencyType =
            when (type) {
                BoostType.EXP -> {
                    CurrencyType.EXP
                }

                BoostType.MONEY -> {
                    CurrencyType.MONEY
                }

                BoostType.POINTS -> {
                    CurrencyType.POINTS
                }

                BoostType.ALL -> {
                    error("Jobs does not have ALL currency type")
                    return 0.0
                }
            }
        return Jobs.getPlayerManager().getJobsPlayer(player).getBoost(jobName, currencyType)
    }

    /**
     * Reset all boosts for a player.
     */
    @JvmStatic
    fun resetBoosts(player: Player) {
        val current = runtime ?: return
        current.launchOperation("reset boosts") {
            val data =
                repo
                    .getOrCreate(player.uniqueId.toString()) {
                        BoostDataEntity(player.uniqueId)
                    }.getOrThrow()
            data.clearBoosts()
            repo.markDirty(data)
        }
    }

    /**
     * Get boost data for a player (for GUI and listener).
     * Returns null if player has no boost data.
     */
    @JvmStatic
    fun getBoostData(playerUuid: UUID): BoostDataEntity? {
        val current = runtime ?: return null
        val data = current.repo.getNow(playerUuid.toString()) ?: return null
        if (data.removeExpired()) {
            current.repo.markDirty(data)
        }
        return data
    }
}

internal fun isValidBoostRequest(
    boost: Double,
    expires: Long,
    boostId: String,
    now: Long = System.currentTimeMillis(),
): Boolean =
    boost.isFinite() &&
        boost > 0.0 &&
        expires > now &&
        boostId.isNotBlank() &&
        boostId == boostId.trim()
