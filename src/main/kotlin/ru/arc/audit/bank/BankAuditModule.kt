package ru.arc.audit.bank

import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.hooks.HookRegistry
import ru.arc.metrics.MetricsModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class BankAuditRunner(
    private val scheduler: TaskScheduler,
    private val initialDelaySeconds: Int,
    private val sampleIntervalSeconds: Int,
    private val sample: () -> Unit,
) : AutoCloseable {
    private var task: ru.arc.core.ScheduledTask? = null

    fun start() {
        if (task?.isCancelled == false) return
        task =
            scheduler.runTimerAsync(
                initialDelaySeconds * TICKS_PER_SECOND,
                sampleIntervalSeconds * TICKS_PER_SECOND,
            ) {
                sample()
            }
    }

    override fun close() {
        task?.cancel()
        task = null
    }

    private companion object {
        const val TICKS_PER_SECOND = 20L
    }
}

/** Single-leader Paper adapter for network Bank supply snapshots. */
object BankAuditModule : PluginModule {
    override val name = "BankAudit"
    override val priority = 55

    private var runner: BankAuditRunner? = null
    private var service: BankAuditService? = null
    private var config: BankAuditConfig? = null
    private val sampling = AtomicBoolean(false)
    private val generation = AtomicLong()

    @Volatile
    private var state = "disabled"

    override fun init() {
        if (System.getProperty("arc.test.unit") != null) return
        val cfg = BankAuditConfig.load()
        config = cfg
        val localServer = ARC.serverName
        when {
            !cfg.enabled -> {
                state = "disabled"
                info("Bank audit disabled")
                return
            }
            !cfg.isCollector(localServer) -> {
                state = "standby"
                info("Bank audit standby: collector={} local={}", cfg.collectorServer, localServer ?: "unknown")
                return
            }
            HookRegistry.bankHook == null || HookRegistry.redisEcoHook == null -> {
                state = "unavailable"
                warn("Bank audit unavailable: Bank or RedisEconomy hook missing")
                return
            }
        }

        service = BankAuditService(cfg)
        state = "warming_up"
        val activeGeneration = generation.incrementAndGet()
        runner =
            BankAuditRunner(
                scheduler = Tasks.scheduler,
                initialDelaySeconds = cfg.initialDelaySeconds,
                sampleIntervalSeconds = cfg.sampleIntervalSeconds,
            ) {
                sample(cfg, activeGeneration)
            }.also(BankAuditRunner::start)
        info(
            "Bank audit scheduled on {} every {}s (expected remote save lag <= {}s)",
            localServer ?: "unknown",
            cfg.sampleIntervalSeconds,
            cfg.expectedMaxLagSeconds,
        )
    }

    private fun sample(cfg: BankAuditConfig, activeGeneration: Long) {
        if (generation.get() != activeGeneration) return
        if (!sampling.compareAndSet(false, true)) return
        try {
            val redis = HookRegistry.redisEcoHook ?: error("RedisEconomy hook unavailable")
            val bank = HookRegistry.bankHook ?: error("Bank hook unavailable")
            val discovered = redis.getCachedAccounts()
            check(discovered.isNotEmpty()) { "RedisEconomy account cache is empty" }
            val capped = discovered.size > cfg.maxAccounts
            val candidates = discovered.take(cfg.maxAccounts)
            var failed = 0
            val accounts =
                candidates.mapNotNull { candidate ->
                    val playerId = candidate.uuid?.toString()
                    if (playerId == null) {
                        failed++
                        return@mapNotNull null
                    }
                    runCatching {
                        val bankAccount = bank.account(playerId, candidate.name)
                        BankAuditAccount(
                            playerId = playerId,
                            player = candidate.name,
                            walletBalance = candidate.balance,
                            bankBalance = bankAccount.balance,
                            pendingInterest = bankAccount.pendingInterest,
                        )
                    }.getOrElse {
                        failed++
                        null
                    }
                }
            if (generation.get() != activeGeneration) return
            val snapshot =
                checkNotNull(service).accept(
                    BankAuditReadResult(
                        discoveredAccounts = discovered.size,
                        accounts = accounts,
                        failedAccounts = failed,
                        capped = capped,
                    ),
                )
            state = if (snapshot.complete) "ready" else "partial"
            MetricsModule.recordSnapshot("bank-audit", "bank-audit") {
                checkNotNull(service).metricPoints(snapshot)
            }
        } catch (failure: Throwable) {
            if (generation.get() != activeGeneration) return
            val activeService = service ?: return
            activeService.recordFailure(failure)
            state = "error"
            MetricsModule.recordSnapshot("bank-audit", "bank-audit") {
                activeService.failureMetricPoints()
            }
            warn("Bank audit snapshot failed: {}", failure.javaClass.simpleName)
        } finally {
            sampling.set(false)
        }
    }

    fun summary(limit: Int): Map<String, Any?> {
        val cfg = config
        val result = LinkedHashMap<String, Any?>()
        result["collectorState"] = state
        result["collectorServer"] = cfg?.collectorServer ?: "spawn"
        result["localServer"] = ARC.serverName ?: "unknown"
        result["singleLeader"] = true
        result["source"] = "RedisEconomy account cache + Bank API on collector server"
        result["expectedMaxLagSeconds"] = cfg?.expectedMaxLagSeconds ?: 600
        service?.summary(limit)?.forEach(result::put)
        result["status"] =
            when (state) {
                "ready", "partial", "warming_up" -> result["status"] ?: state
                else -> state
            }
        return result
    }

    override fun shutdown() {
        generation.incrementAndGet()
        runner?.close()
        runner = null
        service = null
        config = null
        state = "disabled"
    }
}
