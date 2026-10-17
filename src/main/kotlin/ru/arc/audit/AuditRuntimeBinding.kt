package ru.arc.audit

import ru.arc.sql.SqlConnectionConfig

/** Only settings captured by a store constructor; live policy and presentation stay reloadable. */
internal data class AuditRuntimeBinding(
    val mode: AuditStorageMode,
    val mysql: SqlConnectionConfig?,
    val serverName: String,
    val writer: AuditWriterSettings?,
    val maintenance: MaintenanceSettings?,
    val redis: RedisSettings?,
    val migration: MigrationSettings?,
) {
    data class RedisSettings(val saveInterval: Long, val invalidationCoalesceSeconds: Int, val maximumPendingEvents: Int)
    data class MigrationSettings(val ownerServer: String, val batchSize: Int)
    data class MaintenanceSettings(
        val enabled: Boolean,
        val retentionDays: Int,
        val jobsRawRetentionDays: Int,
        val intervalHours: Long,
        val maxCompactionDaysPerRun: Int,
        val deleteBatchSize: Int,
    ) {
        fun toSettings(): AuditMaintenanceSettings = AuditMaintenanceSettings(
            enabled, AuditRetentionPolicy(retentionDays, jobsRawRetentionDays), intervalHours,
            maxCompactionDaysPerRun, deleteBatchSize,
        )
    }

    companion object {
        fun capture(config: AuditConfig, serverName: String): AuditRuntimeBinding = AuditRuntimeBinding(
            mode = config.storageMode,
            mysql = config.mysql,
            serverName = serverName,
            writer = if (config.storageMode == AuditStorageMode.REDIS) null else AuditWriterSettings(
                batchSize = config.writeBatchSize,
                flushIntervalMillis = config.writeFlushIntervalMillis,
                maximumPendingEvents = config.maximumPendingEvents,
                retryIntervalMillis = config.writeRetryIntervalMillis,
                shutdownTimeoutSeconds = config.shutdownTimeoutSeconds.toLong(),
                jobsCoalesceWindowMillis = config.jobsCoalesceWindowSeconds * 1_000L,
                jobsCoalesceMaximumEvents = config.jobsCoalesceMaximumEvents,
                jobsCoalescingEnabled = config.storageMode == AuditStorageMode.SQL,
            ),
            maintenance = if (config.storageMode == AuditStorageMode.REDIS) null else MaintenanceSettings(
                enabled = serverName.equals(config.cleanupOwnerServer, ignoreCase = true),
                retentionDays = config.retentionDays,
                jobsRawRetentionDays = config.jobsRawRetentionDays,
                intervalHours = config.cleanupIntervalHours.toLong(),
                maxCompactionDaysPerRun = config.maxCompactionDaysPerRun,
                deleteBatchSize = config.cleanupDeleteBatchSize,
            ),
            redis = if (config.storageMode == AuditStorageMode.SQL) null else RedisSettings(
                config.saveInterval, config.invalidationCoalesceSeconds, config.maximumPendingEvents,
            ),
            migration = if (config.storageMode != AuditStorageMode.DUAL) null else MigrationSettings(
                config.migrationOwnerServer, config.migrationBatchSize,
            ),
        )
    }
}
