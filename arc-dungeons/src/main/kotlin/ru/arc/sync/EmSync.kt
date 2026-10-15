package ru.arc.sync

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.magmaguy.elitemobs.playerdata.database.PlayerData
import com.magmaguy.elitemobs.skills.SkillType
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import ru.arc.dungeons.api.ArcPlayerSaveService
import ru.arc.paper.api.ArcRedisLease
import ru.arc.paper.api.ArcRedisService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.logging.Level

/** Personal virtual entitlement; XP keys are the native SkillType names, without conversion. */
internal data class DungeonXpBookRecord(
    val id: String,
    val title: String,
    val earnedAt: Long,
    val xp: Map<String, Long>,
)

/** EliteMobs state synchronization owned by ArcDungeons, independent of ARC's SyncManager. */
class EmSync private constructor(
    private val plugin: Plugin,
    private val serverName: String,
    private val saveService: ArcPlayerSaveService,
    private val lease: ArcRedisLease,
    scheduler: TaskScheduler,
    private val playerData: EmSyncPlayerData,
) : Listener, AutoCloseable {

    constructor(plugin: Plugin, serverName: String) : this(
        plugin = plugin,
        serverName = serverName.also { require(it.isNotBlank()) { "Canonical server name cannot be blank" } },
        saveService = requireNotNull(plugin.server.servicesManager.load(ArcPlayerSaveService::class.java)) {
            "ArcPlayerSaveService is not registered; EmSync cannot start without the host save scheduler"
        },
        lease = requireNotNull(plugin.server.servicesManager.load(ArcRedisService::class.java)) {
            "ArcRedisService is not registered; EmSync cannot start without the host Redis service"
        }.acquirePrimary(REDIS_OWNER),
        scheduler = Tasks.scheduler,
        playerData = EliteMobsPlayerData,
    )

    internal constructor(
        plugin: Plugin,
        serverName: String,
        redisService: ArcRedisService,
        scheduler: TaskScheduler,
        playerData: EmSyncPlayerData,
        saveService: ArcPlayerSaveService,
    ) : this(
        plugin = plugin,
        serverName = serverName.also { require(it.isNotBlank()) { "Canonical server name cannot be blank" } },
        saveService = saveService,
        lease = redisService.acquirePrimary(REDIS_OWNER),
        scheduler = scheduler,
        playerData = playerData,
    )

    private companion object {
        const val REDIS_OWNER = "ArcDungeons-EmSync"
        const val REDIS_HASH = "arc.em_data"
        const val PLAYER_DATA_POLL_DELAY_TICKS = 5L
        const val PLAYER_DATA_POLL_PERIOD_TICKS = 5L
        /** 60 × 5 ticks ≈ 15 s — EliteMobs may load its player record asynchronously on join. */
        const val MAX_PLAYER_DATA_WAIT_CYCLES = 60
        val gson = Gson()
        val skillNames = SkillType.values().map { it.name }.toSet()
    }

    private val tasks = LifecycleTaskScope(scheduler)
    private val saveMonitor = Any()
    private val saveTails = mutableMapOf<UUID, CompletableFuture<Void>>()
    private val lootCreditReceipts = ConcurrentHashMap<UUID, String>()
    private val skillBookLibraries = mutableMapOf<UUID, List<DungeonXpBookRecord>>()
    private val loaded = ConcurrentHashMap<UUID, Boolean>()
    private val joinGenerations = ConcurrentHashMap<UUID, Long>()
    private val joinTasks = ConcurrentHashMap<UUID, ru.arc.core.ScheduledTask>()
    private val closeCompletion = CompletableFuture<Void>()
    @Volatile private var started = false
    @Volatile private var closing = false
    private var saveRegistration: AutoCloseable? = null

    /** Register this listener and begin loading players already online. Call once from plugin onEnable. */
    fun start() {
        requirePrimaryThread()
        check(!closing) { "EmSync is closed" }
        check(!started) { "EmSync is already started" }
        saveRegistration = saveService.register(plugin, Consumer { player ->
            if (!closing && isReady(player.uniqueId)) persist(player)
        })
        started = true
        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.server.onlinePlayers.toList().forEach(::beginJoin)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) = beginJoin(event.player)

    @EventHandler(priority = org.bukkit.event.EventPriority.LOW)
    fun onPlayerQuit(event: PlayerQuitEvent) = leave(event.player)

    @EventHandler(ignoreCancelled = true)
    fun onPlayerKick(event: PlayerKickEvent) = leave(event.player)

    /** Whether this player's EliteMobs state has been applied and remains available. */
    internal fun isReady(uuid: UUID): Boolean {
        requirePrimaryThread()
        return !closing && loaded[uuid] == true && playerData.isAvailable(uuid)
    }

    internal fun lootCreditReceipt(uuid: UUID): String? =
        if (isReady(uuid)) lootCreditReceipts[uuid] else null

    internal fun markLootCredit(uuid: UUID, token: String) {
        requirePrimaryThread()
        check(isReady(uuid)) { "EliteMobs synchronization is not ready for $uuid" }
        require(token.isNotBlank()) { "Lost-loot receipt token cannot be blank" }
        lootCreditReceipts[uuid] = token
    }

    /** A detached library, or null while this session is not ready. Primary thread only. */
    internal fun skillBooks(uuid: UUID): List<DungeonXpBookRecord>? =
        if (isReady(uuid)) skillBookLibraries[uuid].orEmpty().map(::copySkillBook) else null

    /**
     * Adds a new live entitlement once. The completion owner must not re-award a consumed run ID.
     * This only changes memory: acknowledge the grant after [persist] completes successfully.
     */
    internal fun addSkillBook(uuid: UUID, book: DungeonXpBookRecord): Boolean {
        if (!isReady(uuid)) return false
        val library = skillBookLibraries[uuid].orEmpty()
        if (library.any { it.id == book.id }) return false
        skillBookLibraries[uuid] = library + copySkillBook(book)
        return true
    }

    /**
     * Remove and apply native XP on the same primary-thread turn, then [persist] both together.
     * An unknown save outcome is not permission to restore the book or replay its XP grant.
     */
    internal fun removeSkillBook(uuid: UUID, id: String): DungeonXpBookRecord? {
        if (!isReady(uuid)) return null
        val library = skillBookLibraries[uuid] ?: return null
        val book = library.firstOrNull { it.id == id } ?: return null
        // Keep [] as an authoritative marker after the final entitlement is consumed.
        skillBookLibraries[uuid] = library.filterNot { it.id == id }
        return copySkillBook(book)
    }

    /** Snapshot on the caller thread, then save behind this player's earlier accepted writes. */
    fun persist(player: Player): CompletableFuture<Void> {
        requirePrimaryThread()
        val uuid = player.uniqueId
        if (!isReady(uuid)) {
            return CompletableFuture.failedFuture(IllegalStateException("EliteMobs synchronization is not ready"))
        }
        // A caller may cancel its view; the host-owned tail must keep tracking the Redis operation.
        return snapshotAndPersist(uuid, allowClosing = false).copy()
    }

    /**
     * Stop accepting new work, capture final online-player snapshots, and release only this lease
     * after every accepted per-player save tail completes. Never wait on a Paper lifecycle thread.
     */
    override fun close() {
        requirePrimaryThread()
        if (closing) return
        closing = true
        runCatching { saveRegistration?.close() }
            .onFailure { plugin.logger.log(Level.WARNING, "Could not unregister EmSync snapshots", it) }
        saveRegistration = null
        if (started) HandlerList.unregisterAll(this)
        runCatching { tasks.close() }
            .onFailure { plugin.logger.log(Level.WARNING, "Could not cancel every EmSync task", it) }

        // Capture snapshots while both readiness and receipt maps still describe the live session.
        plugin.server.onlinePlayers.toList().forEach { player ->
            val uuid = player.uniqueId
            if (loaded[uuid] == true && playerData.isAvailable(uuid)) {
                runCatching { snapshotAndPersist(uuid, allowClosing = true) }
                    .onFailure { plugin.logger.log(Level.WARNING, "Could not snapshot EliteMobs data for $uuid", it) }
            }
        }

        loaded.clear()
        lootCreditReceipts.clear()
        skillBookLibraries.clear()
        joinGenerations.clear()
        joinTasks.clear()

        val tails = synchronized(saveMonitor) { saveTails.values.toList() }
        CompletableFuture.allOf(*tails.toTypedArray()).whenCompleteAsync { _, failure ->
            var releaseFailure: Throwable? = null
            try {
                lease.close()
            } catch (closeFailure: Throwable) {
                releaseFailure = closeFailure
            } finally {
                synchronized(saveMonitor) { saveTails.clear() }
            }
            when {
                failure != null -> closeCompletion.completeExceptionally(unwrap(failure))
                releaseFailure != null -> closeCompletion.completeExceptionally(releaseFailure)
                else -> closeCompletion.complete(null)
            }
            if (failure != null) plugin.logger.log(Level.WARNING, "An accepted EmSync save failed during shutdown", unwrap(failure))
            if (releaseFailure != null) plugin.logger.log(Level.WARNING, "Could not release the EmSync Redis lease", releaseFailure)
        }
    }

    /** Internal observation point for deterministic lifecycle tests and the owning plugin. */
    internal fun closeCompletion(): CompletableFuture<Void> = closeCompletion.copy()

    private fun beginJoin(player: Player) {
        if (closing || !player.isOnline) return
        val uuid = player.uniqueId
        val generation = joinGenerations.merge(uuid, 1L, Long::plus) ?: 1L
        val counter = AtomicInteger(0)
        val task = tasks.runTimer(PLAYER_DATA_POLL_DELAY_TICKS, PLAYER_DATA_POLL_PERIOD_TICKS) {
            if (!isCurrentJoin(uuid, generation)) {
                cancelJoinTask(uuid)
                return@runTimer
            }
            if (!playerData.isAvailable(uuid)) {
                if (counter.incrementAndGet() > MAX_PLAYER_DATA_WAIT_CYCLES) {
                    plugin.logger.warning(
                        "EliteMobs PlayerData is still absent for $uuid after " +
                            "$MAX_PLAYER_DATA_WAIT_CYCLES cycles (~" +
                            "${MAX_PLAYER_DATA_WAIT_CYCLES * PLAYER_DATA_POLL_PERIOD_TICKS / 20}s); sync skipped",
                    )
                    cancelJoinTask(uuid)
                }
                return@runTimer
            }
            loadAndApply(uuid, generation)
            cancelJoinTask(uuid)
        }
        if (task != null) joinTasks.put(uuid, task)?.cancel()
    }

    private fun loadAndApply(uuid: UUID, generation: Long) {
        val token = runCatching { tasks.token() }.getOrNull() ?: return
        // A reconnect must observe the previous session's final accepted snapshot, even if its
        // acknowledgement failed. Never let a load overtake that outstanding write.
        val previous = synchronized(saveMonitor) { saveTails[uuid] }
        val loadedFuture = (previous ?: CompletableFuture.completedFuture(null))
            .handle { _, _ -> null }
            .thenCompose { load(uuid) }
            .thenApplyAsync { entries ->
                entries?.firstOrNull()?.let { json ->
                    gson.fromJson(json, EmDataDTO::class.java)?.let { data ->
                        val books = data.skillBooks
                        if (books != null) {
                            require(data.id == uuid && data.skillXP != null) { "Incomplete dungeon book snapshot" }
                            val detached = books.map(::copySkillBook)
                            require(detached.map { it.id }.toSet().size == detached.size) {
                                "Duplicate dungeon book identities"
                            }
                            data.copy(skillBooks = detached)
                        } else data
                    }
                }
            }
        loadedFuture.whenComplete { data, failure ->
            tasks.runSync(token) {
                if (!isCurrentJoin(uuid, generation)) return@runSync
                if (failure != null) {
                    loaded.remove(uuid)
                    plugin.logger.log(Level.WARNING, "Could not load EliteMobs sync data for $uuid", unwrap(failure))
                    return@runSync
                }
                try {
                    if (data != null && !(data.srv == serverName && data.lostLootCreditReceipt == null && data.skillBooks == null)) {
                        val dataId = data.id
                        if (dataId != null) {
                            require(dataId == uuid) { "EliteMobs snapshot belongs to another player" }
                            playerData.apply(data)
                            if (data.lostLootCreditReceipt == null) lootCreditReceipts.remove(dataId)
                            else lootCreditReceipts[dataId] = data.lostLootCreditReceipt
                            if (data.skillBooks == null) skillBookLibraries.remove(dataId)
                            else skillBookLibraries[dataId] = data.skillBooks
                        }
                    }
                    // Missing data and intentionally skipped same-server data still establish readiness.
                    loaded[uuid] = true
                } catch (applyFailure: Throwable) {
                    loaded.remove(uuid)
                    plugin.logger.log(Level.WARNING, "Could not apply EliteMobs sync data for $uuid", applyFailure)
                }
            }
        }
    }

    private fun leave(player: Player) {
        val uuid = player.uniqueId
        joinGenerations.compute(uuid) { _, current -> (current ?: 0L) + 1L }
        // The serializer reads the receipt before the local receipt/readiness gates are cleared.
        if (loaded[uuid] == true && playerData.isAvailable(uuid)) {
            runCatching { snapshotAndPersist(uuid, allowClosing = closing) }
                .onFailure { plugin.logger.log(Level.WARNING, "Could not snapshot EliteMobs data for $uuid", it) }
        }
        lootCreditReceipts.remove(uuid)
        skillBookLibraries.remove(uuid)
        loaded.remove(uuid)
        cancelJoinTask(uuid)
    }

    private fun snapshotAndPersist(uuid: UUID, allowClosing: Boolean): CompletableFuture<Void> {
        if (closing && !allowClosing) {
            return CompletableFuture.failedFuture(IllegalStateException("EmSync is closing"))
        }
        val native = playerData.snapshot(uuid, serverName, lootCreditReceipts[uuid])
            ?: return CompletableFuture.failedFuture(IllegalStateException("EliteMobs PlayerData is absent for $uuid"))
        val snapshot = native.copy(
            skillXP = native.skillXP?.copyOf(),
            skillBooks = skillBookLibraries[uuid]?.map(::copySkillBook),
        )
        synchronized(saveMonitor) {
            if (closing && !allowClosing) {
                return CompletableFuture.failedFuture(IllegalStateException("EmSync is closing"))
            }
            val previous = saveTails[uuid]
            val accepted = CompletableFuture<Void>()
            saveTails[uuid] = accepted
            // Only immutable snapshots cross this boundary. JSON work also stays off Paper's
            // thread as a personal library grows; accepted writes outlive plugin task shutdown.
            val write = (previous ?: CompletableFuture.completedFuture(null))
                .handleAsync { _, _ -> gson.toJson(snapshot) }
                .thenCompose { payload -> save(uuid, payload) }
            write.whenComplete { _, failure ->
                if (failure == null) accepted.complete(null)
                else accepted.completeExceptionally(unwrap(failure))
            }
            accepted.whenComplete { _, failure ->
                synchronized(saveMonitor) {
                    if (saveTails[uuid] === accepted) saveTails.remove(uuid)
                }
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "Could not persist EliteMobs sync data for $uuid", unwrap(failure))
                }
            }
            return accepted
        }
    }

    private fun save(uuid: UUID, payload: String): CompletableFuture<Void> = try {
        lease.saveMapEntries(REDIS_HASH, uuid.toString(), payload).thenAccept { }
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }

    private fun load(uuid: UUID): CompletableFuture<List<String?>> = try {
        lease.loadMapEntries(REDIS_HASH, uuid.toString())
    } catch (failure: Throwable) {
        CompletableFuture.failedFuture(failure)
    }

    private fun copySkillBook(book: DungeonXpBookRecord): DungeonXpBookRecord {
        require(book.id.isNotBlank() && book.title.isNotBlank() && book.earnedAt >= 0L) { "Invalid dungeon book identity" }
        require(book.xp.isNotEmpty() && book.xp.all { (skill, xp) -> skill in skillNames && xp > 0L }) {
            "Dungeon book XP must use positive native skill amounts"
        }
        return book.copy(xp = book.xp.toMap())
    }

    private fun cancelJoinTask(uuid: UUID) {
        joinTasks.remove(uuid)?.cancel()
    }

    private fun isCurrentJoin(uuid: UUID, generation: Long): Boolean =
        !closing && joinGenerations[uuid] == generation && plugin.server.getPlayer(uuid)?.isOnline == true

    private fun requirePrimaryThread() {
        check(Bukkit.isPrimaryThread()) { "EliteMobs state synchronization must run on Paper's primary thread" }
    }

    private fun unwrap(failure: Throwable): Throwable =
        if ((failure is java.util.concurrent.CompletionException || failure is java.util.concurrent.ExecutionException) && failure.cause != null) {
            failure.cause!!
        } else {
            failure
        }

    internal data class EmDataDTO(
        @SerializedName("t") val ts: Long = 0L,
        @SerializedName("s") val srv: String = "",
        @SerializedName("u") val id: UUID? = null,
        @SerializedName("c") val currency: Double = 0.0,
        @SerializedName("h") val highestLevelKilled: Int = 0,
        @SerializedName("sc") val score: Int = 0,
        @SerializedName("k") val kills: Int = 0,
        @SerializedName("de") val deaths: Int = 0,
        @SerializedName("b") val useBookMenu: Boolean = false,
        @SerializedName("d") val dismissEmStatus: Boolean = false,
        @SerializedName("pl") val playerLevel: Int = 0,
        @SerializedName("sx") val skillXP: LongArray? = null,
        @SerializedName("sb") val skillBonusSelections: String? = null,
        @SerializedName("gd") val gamblingDebt: Double = 0.0,
        @SerializedName("qc") val questsCompleted: Int = 0,
        @SerializedName("lr") val lostLootCreditReceipt: String? = null,
        /** null is a legacy snapshot; [] remains authoritative after the final book is consumed. */
        @SerializedName("xb") val skillBooks: List<DungeonXpBookRecord>? = null,
    )
}

/** Narrow owner-local seam: all calls are made on Paper's primary thread. */
internal interface EmSyncPlayerData {
    fun isAvailable(uuid: UUID): Boolean
    fun snapshot(uuid: UUID, serverName: String, receipt: String?): EmSync.EmDataDTO?
    fun apply(data: EmSync.EmDataDTO)
}

private object EliteMobsPlayerData : EmSyncPlayerData {
    override fun isAvailable(uuid: UUID): Boolean = PlayerData.getPlayerData(uuid) != null

    override fun snapshot(uuid: UUID, serverName: String, receipt: String?): EmSync.EmDataDTO? {
        if (!isAvailable(uuid)) return null
        return EmSync.EmDataDTO(
            ts = System.currentTimeMillis(),
            srv = serverName,
            id = uuid,
            currency = PlayerData.getCurrency(uuid),
            highestLevelKilled = PlayerData.getHighestLevelKilled(uuid),
            useBookMenu = PlayerData.getUseBookMenus(uuid),
            dismissEmStatus = PlayerData.getDismissEMStatusScreenMessage(uuid),
            score = PlayerData.getScore(uuid),
            kills = PlayerData.getKills(uuid),
            deaths = PlayerData.getDeaths(uuid),
            playerLevel = PlayerData.getPlayerLevel(uuid),
            skillXP = PlayerData.getAllSkillXP(uuid),
            skillBonusSelections = PlayerData.getSkillBonusSelections(uuid),
            gamblingDebt = PlayerData.getGamblingDebt(uuid),
            questsCompleted = PlayerData.getQuestsCompleted(uuid),
            lostLootCreditReceipt = receipt,
        )
    }

    override fun apply(data: EmSync.EmDataDTO) {
        val uuid = data.id ?: return
        if (!isAvailable(uuid)) {
            Bukkit.getLogger().warning("EliteMobs PlayerData is not yet loaded for $uuid")
            return
        }
        val onlinePlayer = Bukkit.getPlayer(uuid)
        if (PlayerData.getCurrency(uuid) != data.currency) PlayerData.setCurrency(uuid, data.currency)
        if (PlayerData.getHighestLevelKilled(uuid) != data.highestLevelKilled) {
            PlayerData.setHighestLevelKilled(uuid, data.highestLevelKilled)
        }
        if (onlinePlayer != null) {
            if (PlayerData.getUseBookMenus(uuid) != data.useBookMenu) PlayerData.setUseBookMenus(onlinePlayer, data.useBookMenu)
            if (PlayerData.getDismissEMStatusScreenMessage(uuid) != data.dismissEmStatus) {
                PlayerData.setDismissEMStatusScreenMessage(onlinePlayer, data.dismissEmStatus)
            }
        }
        if (PlayerData.getScore(uuid) != data.score) PlayerData.setDatabaseValue(uuid, "Score", data.score)
        if (PlayerData.getKills(uuid) != data.kills) PlayerData.setDatabaseValue(uuid, "Kills", data.kills)
        if (PlayerData.getDeaths(uuid) != data.deaths) PlayerData.setDatabaseValue(uuid, "Deaths", data.deaths)
        if (data.skillBonusSelections != null && PlayerData.getSkillBonusSelections(uuid) != data.skillBonusSelections) {
            PlayerData.setSkillBonusSelections(uuid, data.skillBonusSelections)
        }
        if (PlayerData.getGamblingDebt(uuid) != data.gamblingDebt) PlayerData.setGamblingDebt(uuid, data.gamblingDebt)
        data.skillXP?.let { incomingXP ->
            val currentXP = PlayerData.getAllSkillXP(uuid)
            SkillType.values().forEachIndexed { index, skillType ->
                if (index < incomingXP.size && currentXP[index] != incomingXP[index]) {
                    PlayerData.setSkillXP(uuid, skillType, incomingXP[index])
                }
            }
        }
    }
}
