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
    fun expiresInMillis(): Long = expires - System.currentTimeMillis()

    fun isExpired(): Boolean = expires < System.currentTimeMillis()

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

    /** Read-only view of boosts for serialization */
    val boosts: Set<JobsBoostData> get() = _boosts.toSet()

    @Transient
    var isDirty: Boolean = false

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
        _boosts.clear()
        _boosts.addAll(other._boosts)
        boostCache.invalidateAll()
    }

    /**
     * Remove expired boosts.
     * @return true if any boosts were removed
     */
    fun removeExpired(): Boolean {
        val removed = _boosts.removeIf { it.isExpired() }
        if (removed) {
            isDirty = true
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
    fun findById(id: String): JobsBoostData? = _boosts.find { it.id == id }

    /**
     * Check if a boost with the given ID exists.
     */
    fun hasBoostWithId(id: String): Boolean = _boosts.any { it.id == id }

    /**
     * Get all boosts applicable to a job (for GUI display).
     */
    fun boostsForJob(job: Job): List<JobsBoostData> = boostsForJob(job.name)

    /**
     * Get all boosts applicable to a job name.
     */
    fun boostsForJob(jobName: String): List<JobsBoostData> {
        removeExpired()
        return _boosts.filter { it.appliesToJob(jobName) }.toList()
    }

    /**
     * Get all active (non-expired) boosts.
     */
    fun activeBoosts(): List<JobsBoostData> {
        removeExpired()
        return _boosts.toList()
    }

    /**
     * Add a new boost.
     * @return true if boost was added, false if a boost with same ID already exists
     */
    fun addBoost(boost: JobsBoostData): Boolean {
        removeExpired()

        if (hasBoostWithId(boost.id)) {
            error("Boost with id {} already exists for {}", boost.id, player)
            return false
        }

        _boosts.add(boost)
        isDirty = true
        boostCache.invalidateAll()
        return true
    }

    /**
     * Remove a boost by ID.
     * @return true if boost was removed
     */
    fun removeBoost(boostId: String): Boolean {
        val removed = _boosts.removeIf { it.id == boostId }
        if (removed) {
            isDirty = true
            boostCache.invalidateAll()
        }
        return removed
    }

    /**
     * Clear all boosts.
     */
    fun clearBoosts() {
        _boosts.clear()
        isDirty = true
        boostCache.invalidateAll()
    }

    /**
     * Get the count of active boosts.
     */
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
    private lateinit var repo: CachedRepository<BoostDataEntity>
    private lateinit var config: Config
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var initialized = false
    private var listenerRegistered = false

    @JvmStatic
    fun init() {
        if (initialized) return
        if (ru.arc.ARC.redisManager == null) return

        info("Jobs hook enabled")

        // Register listener only once
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(JobsModuleListener, ARC.instance)
            listenerRegistered = true
        }

        config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "jobs.yml")

        repo =
            redisRepo<BoostDataEntity>(
                id = "jobs",
                storageKey = "arc.jobs_boosts",
                updateChannel = "arc.jobs_boosts_update",
                scope = scope,
            ) {
                loadAllOnStart(true)
                saveInterval(500.milliseconds)
            }
        initialized = true
    }

    @JvmStatic
    fun shutdown() {
        if (!initialized) return
        runBlocking { repo.shutdown() }
        initialized = false
    }

    @JvmStatic
    fun getConfig(): Config = config

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
    ) {
        // Parse job targets
        val jobTargets: List<JobTarget> =
            when {
                jobs.isEmpty() -> listOf(JobTarget.All)
                jobs.any { it.equals("all", ignoreCase = true) } -> listOf(JobTarget.All)
                else -> jobs.map { JobTarget.Specific(it) }
            }

        // Parse boost types
        val typesToApply: List<BoostType> =
            when {
                types.isEmpty() -> listOf(BoostType.ALL)
                types.contains(BoostType.ALL) -> listOf(BoostType.ALL)
                else -> types
            }

        scope.launch {
            val data =
                repo
                    .getOrCreate(player.toString()) {
                        BoostDataEntity(player)
                    }.getOrThrow()

            info("Adding boost for player $player jobs $jobs boost $boost expires $expires boostId $boostId types $types")

            var addedCount = 0
            for (jobTarget in jobTargets) {
                for (type in typesToApply) {
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
                        addedCount++
                    }
                }
            }

            if (addedCount > 0) {
                repo.save(data)
                info("Added $addedCount boost(s) for player $player")
            }
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

    @JvmStatic
    fun getJobNames(): List<String> = Jobs.getJobs().map { it.name }

    @JvmStatic
    fun openBoostGui(player: Player) {
        GuiUtils.constructAndShowAsync(
            {
                _root_ide_package_.ru.arc.jobs.guis
                    .createJobsListGui(config, player)
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
    ): Boolean =
        runBlocking {
            val data = repo.get(player.uniqueId.toString()).getOrNull() ?: return@runBlocking false
            // Check for exact match or prefixed match (for multi-job/type boosts)
            data.boosts.any { it.id == boostId || it.id.startsWith("${boostId}_") }
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
        scope.launch {
            val data =
                repo
                    .getOrCreate(player.uniqueId.toString()) {
                        BoostDataEntity(player.uniqueId)
                    }.getOrThrow()
            data.clearBoosts()
            repo.save(data)
        }
    }

    /**
     * Get boost data for a player (for GUI and listener).
     * Returns null if player has no boost data.
     */
    @JvmStatic
    fun getBoostData(playerUuid: UUID): BoostDataEntity? =
        runBlocking {
            repo.get(playerUuid.toString()).getOrNull()
        }
}
