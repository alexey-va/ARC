package ru.arc.audit.stock

import ru.arc.ARC
import ru.arc.config.StockConfig
import ru.arc.core.PluginModule
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.core.modules.StockModule
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.core.MetricPoint
import ru.arc.stock.Position
import ru.arc.stock.StockMarket
import ru.arc.stock.StockPlayerManager
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

/** Single-leader aggregate sampler for stock liabilities outside wallet and Bank supply. */
object StockAuditModule : PluginModule {
    override val name = "StockAudit"
    override val priority = 77

    private val service = StockAuditService()
    private var task: ScheduledTask? = null

    @Volatile
    private var state = "disabled"

    @Volatile
    private var latest: StockAuditSnapshot? = null

    @Volatile
    private var lastFailure: String? = null

    override fun init() {
        latest = null
        lastFailure = null
        if (System.getProperty("arc.test.unit") != null) return
        when {
            !StockModule.isAvailable() -> {
                state = "disabled"
                info("Stock audit disabled because the stock module is unavailable")
                return
            }
            !StockConfig.mainServer -> {
                state = "standby"
                info("Stock audit standby on {}", ARC.serverName ?: "unknown")
                return
            }
        }

        state = "warming_up"
        sample()
        task = Tasks.scheduler.runTimer(SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS, Runnable(::sample))
        info("Stock audit sampler started on {}", ARC.serverName ?: "unknown")
    }

    override fun shutdown() {
        task?.cancel()
        task = null
        latest = null
        lastFailure = null
        state = "disabled"
    }

    fun summary(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["collectorState"] = state
        result["collectorServer"] = "spawn"
        result["localServer"] = ARC.serverName ?: "unknown"
        result["singleLeader"] = true
        result["source"] = "network-shared arc.stock_players and arc.stocks repositories"
        latest?.summary()?.forEach(result::put)
        result["status"] = if (state == "ready") result["status"] ?: "ready" else state
        result["lastFailure"] = lastFailure
        return result
    }

    private fun sample() {
        runCatching {
            val market = StockMarket.stocks().associateBy { it.symbol }
            val accounts =
                StockPlayerManager.playerRepo.allNow().map { player ->
                    synchronized(player) {
                        StockAuditAccountObservation(
                            tradingBalance = player.getBalance(),
                            lifetimeDividends = player.receivedDividend,
                            positions =
                                player.positions().map { position ->
                                    val stock = market[position.symbol]
                                    val price = stock?.price ?: 0.0
                                    StockAuditPositionObservation(
                                        side = if (position.type == Position.Type.BOUGHT) StockAuditSide.LONG else StockAuditSide.SHORT,
                                        priced = stock != null,
                                        principal = position.startPrice * position.amount,
                                        leverage = position.leverage,
                                        unrealizedPnl = position.gains(price),
                                        nextDividend =
                                            if (position.type == Position.Type.BOUGHT && stock != null) {
                                                StockMarket.effectiveDividendPerShare(stock) * position.amount
                                            } else {
                                                0.0
                                            },
                                    )
                                },
                        )
                    }
                }
            service.summarize(
                StockAuditSample(
                    timestamp = System.currentTimeMillis(),
                    accounts = accounts,
                    commissionRate = StockConfig.commission,
                    configuredDividendRatePerPayout = StockConfig.dividendPercentFromPrice,
                    effectiveDividendRatePerPayout = StockMarket.effectiveDividendRate(),
                    configuredDividendPeriodSeconds = StockConfig.dividendPeriod,
                    effectiveDividendPeriodSeconds = StockMarket.effectiveDividendPeriodSeconds(),
                    maxConfiguredLeveragedPrice = StockConfig.maxLeveragedPrice,
                    effectiveMaxNewOrderLeverage = market.values.maxOfOrNull(StockMarket::effectiveMaxLeverage) ?: 0,
                    effectiveMaxNewOrderPrincipal = StockMarket.effectiveMaxBuyPrice(),
                    effectiveMaxNewOrderExposure = StockMarket.effectiveMaxLeveragedPrice(),
                ),
            )
        }.onSuccess { snapshot ->
            latest = snapshot
            lastFailure = null
            state = if (snapshot.complete) "ready" else "partial"
            MetricsModule.recordSnapshot("stock-audit", "stock-audit", snapshot::metricPoints)
        }.onFailure { failure ->
            lastFailure = failure.javaClass.simpleName.take(80)
            state = "error"
            MetricsModule.recordSnapshot("stock-audit", "stock-audit") {
                listOf(
                    MetricPoint(
                        "arc_stock_collection_success",
                        "Whether the latest stock liability snapshot completed",
                        0.0,
                    ),
                )
            }
            warn("Stock audit snapshot failed: {}", failure.javaClass.simpleName)
        }
    }

    private const val SAMPLE_PERIOD_TICKS = 20L * 60L * 5L
}
