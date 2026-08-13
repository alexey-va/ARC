package ru.arc.audit

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.core.Tasks
import ru.arc.core.modules.EconomyModule
import ru.arc.metrics.MetricsModule
import ru.arc.util.Logging.error
import ru.arc.xserver.playerlist.PlayerManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
    private var balanceHistoryTask: ru.arc.core.ScheduledTask? = null
    private val sessionTracker = EconomySessionTracker()

    private val balanceHistoryPath: Path by lazy {
        ARC.instance.dataPath.resolve("balance-history")
    }

    // ==================== Lifecycle ====================

    /**
     * Initialize with default production dependencies.
     */
    @JvmStatic
    fun init() {
        val scheduler = Tasks.scheduler
        config = AuditConfig.load()

        val repository: AuditRepository = if (ARC.redisManager != null) {
            RedisAuditRepository.create()
        } else {
            InMemoryAuditRepository()
        }

        val monitor = EconomyAuditMonitor(config, registryProvider = MetricsModule::registry)
        service = AuditService(
            repository = repository,
            config = config,
            scheduler = scheduler,
            monitor = monitor,
            localServer = ARC.serverName,
        )

        service.start()
        ensureBalanceHistoryDirectory()
        startBalanceHistoryTask(scheduler)
    }

    /**
     * Initialize with custom service (for testing).
     */
    @JvmStatic
    fun init(customService: AuditService, customConfig: AuditConfig = AuditConfig.default()) {
        service = customService
        config = customConfig
        service.start()
    }

    private fun ensureBalanceHistoryDirectory() {
        if (!Files.exists(balanceHistoryPath)) {
            try {
                Files.createDirectories(balanceHistoryPath)
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
            service.shutdown()
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
    fun economySummary(hours: Int, limit: Int, serverFilter: String? = null): Map<String, Any?> =
        service.economySummary(hours, limit, serverFilter)

    @JvmStatic
    fun economySummarySince(sinceEpochMs: Long, limit: Int, serverFilter: String? = null): Map<String, Any?> =
        service.economySummarySince(sinceEpochMs, limit, serverFilter)

    @JvmStatic
    fun sendAudit(audience: Audience, playerName: String, page: Int, filter: AuditFilter) {
        service.sendAudit(audience, playerName, page, filter)
    }

    // ==================== Clear ====================

    @JvmStatic
    fun clear(playerName: String) {
        service.clearPlayer(playerName)
    }

    @JvmStatic
    fun clearAll() {
        service.clearAll()
    }

    // ==================== Service Access ====================

    /**
     * Get the underlying service for advanced operations.
     */
    fun getService(): AuditService = service

    // ==================== Balance History ====================

    private fun recordBalanceHistory() {
        val economy = EconomyModule.getEconomy() ?: return
        val timestamp = System.currentTimeMillis()

        for (playerName in PlayerManager.getPlayerNames()) {
            try {
                val offlinePlayer = Bukkit.getOfflinePlayer(playerName)
                if (offlinePlayer.name == null || !offlinePlayer.name.equals(playerName, ignoreCase = true)) {
                    error("Failed to get offline player for {}", playerName)
                    continue
                }

                val balance = economy.getBalance(offlinePlayer)
                val playerPath = balanceHistoryPath.resolve("$playerName.csv")

                if (!Files.exists(playerPath)) {
                    Files.createFile(playerPath)
                }

                Files.write(
                    playerPath,
                    "$timestamp,$balance\n".toByteArray(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                )
            } catch (e: IOException) {
                error("Failed to write balance history for {}", playerName, e)
            }
        }
    }

    // ==================== Legacy Compatibility ====================

}
