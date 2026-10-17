package ru.arc.audit

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.Tasks
import ru.arc.core.modules.EconomyModule
import ru.arc.metrics.MetricsModule
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.xserver.playerlist.PlayerManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Менеджер аудита экономических операций.
 *
 * Facade object for Java interoperability.
 * Delegates to [AuditService] for business logic.
 *
 * For tests, use [AuditService] directly with mocked dependencies.
 */
object AuditManager {

    private lateinit var service: AuditService
    private lateinit var config: AuditConfig
    private data class Prepared(val service: AuditService, val config: AuditConfig)
    private var prepared: Prepared? = null
    private var storageBinding: AuditRuntimeBinding? = null
    private var balanceHistoryPath: Path? = null
    private var balanceHistoryTask: ru.arc.core.ScheduledTask? = null
    @Volatile private var balanceHistoryGeneration = 0L
    private val migrationReport = AtomicReference<AuditMigrationReport?>()
    private val sessionTracker = EconomySessionTracker()


    // ==================== Lifecycle ====================

    /**
     * Initialize with default production dependencies.
     */
    internal fun prepareOnWorker(
        root: Path,
        scheduler: ru.arc.core.TaskScheduler,
        redis: ru.arc.redis.RedisOperations?,
        serverName: String,
    ) {
        check(prepared == null) { "Audit is already prepared" }
        migrationReport.set(null)
        val config = AuditConfig.fromFile(root)
        val binding = AuditRuntimeBinding.capture(config, serverName)
        balanceHistoryPath = root.resolve("balance-history")

        val monitor = EconomyAuditMonitor(config, registryProvider = MetricsModule::registry)
        val redisStore =
            if (config.storageMode == AuditStorageMode.SQL) {
                null
            } else {
                RedisAuditEventStore(
                    if (redis != null) RedisAuditRepository.create(root, redis, serverName) else InMemoryAuditRepository(),
                    maximumPendingEvents = config.maximumPendingEvents,
                )
            }
        val sqlStore = try {
            if (config.storageMode == AuditStorageMode.REDIS) {
                null
            } else {
                SqlAuditEventStore.open(
                    config = requireNotNull(config.mysql) { "Audit MySQL config is required" },
                    telemetry = AuditStorageTelemetry(MetricsModule::registry, config.storageMode),
                    measurementBoundary = { redis?.let { ru.arc.metrics.MeasurementResetControl(it).read() }?.thenApply { it?.boundaryAt ?: 0L } ?: CompletableFuture.completedFuture(0L) },
                    writerSettings = requireNotNull(binding.writer),
                    maintenanceSettings = requireNotNull(binding.maintenance).toSettings(),
                )
            }
        } catch (failure: Throwable) {
            try { redisStore?.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            throw failure
        }
        val store =
            when (config.storageMode) {
                AuditStorageMode.REDIS -> requireNotNull(redisStore)
                AuditStorageMode.DUAL -> DualWriteAuditEventStore(requireNotNull(redisStore), requireNotNull(sqlStore))
                AuditStorageMode.SQL -> requireNotNull(sqlStore)
            }
        val service = AuditService(
            eventStore = store,
            config = config,
            scheduler = scheduler,
            monitor = monitor,
            localServer = serverName,
        )

        prepared = Prepared(service, config)
        storageBinding = binding
        if (
            config.storageMode == AuditStorageMode.DUAL &&
            serverName.equals(config.migrationOwnerServer, ignoreCase = true)
        ) {
            AuditLegacyMigration(requireNotNull(redisStore), requireNotNull(sqlStore), config.migrationBatchSize).migrate()
                .whenComplete { report, failure ->
                    if (failure != null) {
                        error("Audit Redis-to-SQL migration failed", failure)
                    } else {
                        migrationReport.set(report)
                        info(
                            "Audit Redis-to-SQL migration finished: scanned={}, inserted={}, duplicates={}, failed={}",
                            report.scanned,
                            report.inserted,
                            report.duplicates,
                            report.failed,
                        )
                    }
                }
        }
        ensureBalanceHistoryDirectory()
    }

    /** Pure native attachment after cold SQL/Redis/file preparation. */
    @JvmStatic
    fun init() {
        val candidate = checkNotNull(prepared) { "Audit storage preparation is not ready" }
        prepared = null
        service = candidate.service
        config = candidate.config
        service.start()
        startBalanceHistoryTask(Tasks.scheduler)
    }

    internal fun requireReloadCompatible(root: Path) {
        val candidate = AuditConfig(ru.arc.config.Config(root, "modules/audit.yml"))
        val active = checkNotNull(storageBinding) { "Audit storage is not prepared" }
        check(active == AuditRuntimeBinding.capture(candidate, active.serverName)) {
            "Audit storage connection or immutable writer/maintenance settings changed; a server restart is required. The active runtime was retained."
        }
    }

    internal fun discardPreparedOnWorker() {
        val candidate = prepared ?: return
        prepared = null
        candidate.service.shutdown()
    }

    fun reload() {
        balanceHistoryGeneration++
        service.stop()
        balanceHistoryTask?.cancel()
        service.start()
        startBalanceHistoryTask(Tasks.scheduler)
    }

    /**
     * Initialize with custom service (for testing).
     */
    @JvmStatic
    fun init(customService: AuditService, customConfig: AuditConfig = AuditConfig.default()) {
        migrationReport.set(null)
        service = customService
        config = customConfig
        service.start()
    }

    private fun ensureBalanceHistoryDirectory() {
        val path = requireNotNull(balanceHistoryPath)
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path)
            } catch (e: IOException) {
                error("Failed to create balance history directory", e)
            }
        }
    }

    private fun startBalanceHistoryTask(scheduler: ru.arc.core.TaskScheduler) {
        if (!config.balanceHistoryEnabled) return

        val interval = 20L * 60 * 5 // 5 minutes
        balanceHistoryTask = scheduler.runTimerAsync(20, interval) {
            recordBalanceHistory()
        }
    }

    /**
     * Stop all tasks and cleanup.
     */
    @JvmStatic
    fun cancel() {
        balanceHistoryGeneration++
        if (::service.isInitialized) {
            service.stop()
        }
        balanceHistoryTask?.cancel()
        balanceHistoryTask = null
        sessionTracker.clear()
        EconomyPendingContextTracker.clear()
        EconomyTransferCorrelationTracker.clear()
        JobsEconomyContextTracker.clear()
    }

    /**
     * Shutdown the repository.
     */
    @JvmStatic
    fun shutdown() {
        cancel()
        if (::service.isInitialized) {
            val retiring = service
            ARC.instance.retireInBackground { retiring.shutdown() }
                .whenComplete { _, failure -> if (failure != null) error("Could not drain audit storage", failure) }
        }
    }

    // ==================== Player Context ====================

    @JvmStatic
    fun join(name: String) {
        service.playerJoined(name)
    }

    @JvmStatic
    fun join(player: Player) {
        service.playerJoined(player.name)
        sessionTracker.joined(player.uniqueId, player.world.name, System.currentTimeMillis())
    }

    @JvmStatic
    fun leave(name: String) {
        service.playerLeft(name)
    }

    @JvmStatic
    fun leave(player: Player) {
        service.playerLeft(player.name)
        sessionTracker.left(player.uniqueId, player.world.name, System.currentTimeMillis())
        EconomyPendingContextTracker.clear(player.uniqueId)
    }

    @JvmStatic
    fun session(playerId: java.util.UUID, currentWorld: String? = null): EconomySessionSnapshot? =
        sessionTracker.snapshot(playerId, currentWorld, System.currentTimeMillis())

    // ==================== Operations ====================

    @JvmStatic
    fun operation(name: String, amount: Double, type: Type, comment: String) {
        service.operation(name, amount, type, comment)
    }

    @JvmStatic
    fun operation(name: String, amount: Double, type: Type, comment: String, metadata: AuditMetadata) {
        service.operation(name, amount, type, comment, metadata)
    }

    @JvmStatic
    fun economyOperation(
        name: String,
        amount: Double,
        type: Type,
        comment: String,
        metadata: AuditMetadata,
        context: EconomyLedgerContext? = null,
    ) {
        service.economyOperation(name, amount, type, comment, metadata, context)
    }

    @JvmStatic
    fun economyAttempt(
        name: String,
        type: Type,
        comment: String,
        metadata: AuditMetadata,
        context: EconomyLedgerContext,
    ) {
        service.economyAttempt(name, type, comment, metadata, context)
    }

    @JvmStatic
    fun unresolvedBalanceSet(
        name: String,
        absoluteBalance: Double,
        metadata: AuditMetadata,
        context: EconomyLedgerContext? = null,
    ) {
        service.unresolvedBalanceSet(name, absoluteBalance, metadata, context)
    }

    @JvmStatic
    fun income(name: String, amount: Double, type: Type, comment: String) {
        service.income(name, amount, type, comment)
    }

    @JvmStatic
    fun expense(name: String, amount: Double, type: Type, comment: String) {
        service.expense(name, amount, type, comment)
    }

    // ==================== Queries ====================

    @JvmStatic
    fun weight(): Long = service.totalWeight()

    @JvmStatic
    fun weightAsync(): CompletableFuture<Long> = service.totalWeightAsync()

    @JvmStatic
    fun storageStatusAsync(): CompletableFuture<AuditStorageStatus> =
        service.storageStatusAsync().thenApply { status -> status.copy(migration = migrationReport.get()) }

    @JvmStatic
    fun economySummary(
        hours: Int,
        limit: Int,
        serverFilter: String? = null,
        shopMaterials: Set<String> = emptySet(),
        concentrationGroups: Map<String, Set<String>> = emptyMap(),
    ): Map<String, Any?> =
        economySummaryAsync(hours, limit, serverFilter, shopMaterials, concentrationGroups).join()

    @JvmStatic
    fun economySummaryAsync(
        hours: Int,
        limit: Int,
        serverFilter: String? = null,
        shopMaterials: Set<String> = emptySet(),
        concentrationGroups: Map<String, Set<String>> = emptyMap(),
    ): CompletableFuture<Map<String, Any?>> =
        economySummarySinceAsync(System.currentTimeMillis() - hours.coerceIn(1, 744) * 3_600_000L, limit, serverFilter, shopMaterials, concentrationGroups)

    @JvmStatic
    fun economySummarySince(
        sinceEpochMs: Long,
        limit: Int,
        serverFilter: String? = null,
        shopMaterials: Set<String> = emptySet(),
        concentrationGroups: Map<String, Set<String>> = emptyMap(),
    ): Map<String, Any?> =
        economySummarySinceAsync(sinceEpochMs, limit, serverFilter, shopMaterials, concentrationGroups).join()

    @JvmStatic
    fun economySummarySinceAsync(
        sinceEpochMs: Long,
        limit: Int,
        serverFilter: String? = null,
        shopMaterials: Set<String> = emptySet(),
        concentrationGroups: Map<String, Set<String>> = emptyMap(),
    ): CompletableFuture<Map<String, Any?>> =
        readMeasurementPeriod().thenCompose { period ->
            service.economySummarySinceAsync(maxOf(sinceEpochMs, period?.boundaryAt ?: 0L), limit, serverFilter, shopMaterials, concentrationGroups)
                .thenApply { summary ->
                    summary + ("measurementPeriod" to mapOf(
                        "generation" to (period?.generation ?: 0L),
                        "boundaryAt" to period?.boundaryAt,
                        "requestedSinceEpochMs" to sinceEpochMs,
                        "historicalAuditRetained" to true,
                    ))
                }
        }

    private fun readMeasurementPeriod(): CompletableFuture<ru.arc.metrics.MeasurementResetState?> =
        ARC.redisManager?.let { ru.arc.metrics.MeasurementResetControl(it).read() }
            ?: CompletableFuture.completedFuture(null)

    @JvmStatic
    fun sendAudit(audience: Audience, playerName: String, page: Int, filter: AuditFilter) {
        service.sendAudit(audience, playerName, page, filter)
    }

    // ==================== Clear ====================

    @JvmStatic
    fun clear(playerName: String): CompletableFuture<Int> = service.clearPlayerAsync(playerName)

    @JvmStatic
    fun clearAll(): CompletableFuture<Int> = service.clearAllAsync()

    // ==================== Service Access ====================

    /**
     * Get the underlying service for advanced operations.
     */
    fun getService(): AuditService = service

    // ==================== Balance History ====================

    private fun recordBalanceHistory() {
        val economy = EconomyModule.getEconomy() ?: return
        val root = balanceHistoryPath ?: return
        val owner = ARC.instance
        val generation = balanceHistoryGeneration
        val timestamp = System.currentTimeMillis()
        // Network identity is already resolved. Never perform a name-to-UUID lookup here.
        val players = PlayerManager.getPlayerUuids().mapNotNull(PlayerManager::getPlayerData)
        for (player in players) {
            owner.nativeWork.call({ balanceHistoryGeneration == generation }) {
                economy.getBalance(Bukkit.getOfflinePlayer(player.uuid))
            }.thenCompose { balance ->
                owner.submitBackground {
                    Files.writeString(
                        root.resolve("${player.username}.csv"), "$timestamp,$balance\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE,
                    )
                }
            }.whenComplete { _, failure ->
                if (failure != null) error("Could not record balance history for {}", player.username, failure)
            }
        }
    }

    // ==================== Legacy Compatibility ====================

}
