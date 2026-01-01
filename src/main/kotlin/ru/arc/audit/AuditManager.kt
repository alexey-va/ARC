package ru.arc.audit

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.BukkitTaskScheduler
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

    private val balanceHistoryPath: Path by lazy {
        ARC.plugin.dataPath.resolve("balance-history")
    }

    // ==================== Lifecycle ====================

    /**
     * Initialize with default production dependencies.
     */
    @JvmStatic
    fun init() {
        val scheduler = BukkitTaskScheduler(ARC.plugin)
        config = AuditConfig.load()

        service = AuditService(
            repository = RedisAuditRepository.create(),
            config = config,
            scheduler = scheduler
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
    }

    // ==================== Player Context ====================

    @JvmStatic
    fun join(name: String) {
        service.playerJoined(name)
    }

    @JvmStatic
    fun leave(name: String) {
        service.playerLeft(name)
    }

    // ==================== Operations ====================

    @JvmStatic
    fun operation(name: String, amount: Double, type: Type, comment: String) {
        service.operation(name, amount, type, comment)
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
        val economy = ARC.getEcon() ?: return
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

    /**
     * For backward compatibility with old Filter enum.
     * @deprecated Use [AuditFilter] directly
     */
    @Deprecated("Use AuditFilter directly", ReplaceWith("AuditFilter"))
    enum class Filter {
        INCOME, EXPENSE, ALL, SHOP, JOB, PAY;

        fun toAuditFilter(): AuditFilter = AuditFilter.valueOf(name)
    }

    @JvmStatic
    @Suppress("DEPRECATION")
    fun sendAudit(audience: Audience, playerName: String, page: Int, filter: Filter) {
        sendAudit(audience, playerName, page, filter.toAuditFilter())
    }
}
