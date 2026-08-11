package ru.arc.audit.bank

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.Locale

/** Policy for the single-leader network Bank snapshot. */
open class BankAuditConfig(
    private val config: Config,
) {
    open val enabled: Boolean
        get() = config.bool("enabled", false)

    open val collectorServer: String
        get() = normalizeServer(config.string("collector-server", "spawn"))

    open val sampleIntervalSeconds: Int
        get() = config.integer("sample-interval-seconds", 600).coerceIn(60, 3_600)

    open val initialDelaySeconds: Int
        get() = config.integer("initial-delay-seconds", 30).coerceIn(1, 300)

    open val maxAccounts: Int
        get() = config.integer("max-accounts", 100_000).coerceIn(1, 100_000)

    open val topAccounts: Int
        get() = config.integer("top-accounts", 50).coerceIn(1, 100)

    open val recentChanges: Int
        get() = config.integer("recent-changes", 100).coerceIn(1, 200)

    open val minimumChange: Double
        get() = config.double("minimum-change", 0.01).coerceIn(0.0, 1_000_000_000.0)

    /** Bank autosave bounds the freshness of players active on another Paper server. */
    open val expectedMaxLagSeconds: Int
        get() = config.integer("expected-max-lag-seconds", 600).coerceIn(0, 3_600)

    fun isCollector(localServer: String?): Boolean =
        enabled && normalizeServer(localServer.orEmpty()) == collectorServer

    companion object {
        fun fromFile(dataPath: Path): BankAuditConfig =
            BankAuditConfig(ConfigManager.of(dataPath, "modules/bank-audit.yml"))

        fun load(): BankAuditConfig = fromFile(ARC.instance.dataPath)

        private fun normalizeServer(value: String): String =
            value
                .trim()
                .lowercase(Locale.ROOT)
                .takeIf { SERVER_NAME.matches(it) }
                ?: "spawn"

        private val SERVER_NAME = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    }
}

class TestBankAuditConfig(
    override val enabled: Boolean = true,
    override val collectorServer: String = "spawn",
    override val sampleIntervalSeconds: Int = 600,
    override val initialDelaySeconds: Int = 30,
    override val maxAccounts: Int = 100_000,
    override val topAccounts: Int = 50,
    override val recentChanges: Int = 100,
    override val minimumChange: Double = 0.01,
    override val expectedMaxLagSeconds: Int = 600,
) : BankAuditConfig(ru.arc.config.EmptyConfig)
