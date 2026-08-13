package ru.arc.audit.stock

import ru.arc.metrics.core.MetricPoint
import kotlin.math.max

internal enum class StockAuditSide(val label: String) {
    LONG("long"),
    SHORT("short"),
}

internal data class StockAuditPositionObservation(
    val side: StockAuditSide,
    val priced: Boolean,
    val principal: Double,
    val leverage: Double,
    val unrealizedPnl: Double,
    val nextDividend: Double,
)

internal data class StockAuditAccountObservation(
    val tradingBalance: Double,
    val lifetimeDividends: Double,
    val positions: List<StockAuditPositionObservation>,
)

internal data class StockAuditSample(
    val timestamp: Long,
    val accounts: List<StockAuditAccountObservation>,
    val commissionRate: Double,
    val configuredDividendRatePerPayout: Double,
    val effectiveDividendRatePerPayout: Double,
    val configuredDividendPeriodSeconds: Long,
    val effectiveDividendPeriodSeconds: Long,
    val maxConfiguredLeveragedPrice: Double,
    val effectiveMaxNewOrderLeverage: Int,
    val effectiveMaxNewOrderPrincipal: Double,
    val effectiveMaxNewOrderExposure: Double,
)

internal data class StockAuditSnapshot(
    val timestamp: Long,
    val accounts: Int,
    val accountsWithPositions: Int,
    val positiveEquityAccounts: Int,
    val positionCounts: Map<String, Int>,
    val tradingBalance: Double,
    val positiveTradingBalance: Double,
    val negativeTradingBalance: Double,
    val positionPrincipal: Double,
    val grossExposure: Double,
    val unrealizedPnl: Double,
    val accountEquity: Double,
    val redeemableLiability: Double,
    val lifetimeDividends: Double,
    val nextDividendLiability: Double,
    val shortDividendLiability: Double,
    val shortPositionsReceivingDividend: Int,
    val maxObservedLeverage: Double,
    val commissionRate: Double,
    val configuredDividendRatePerPayout: Double,
    val effectiveDividendRatePerPayout: Double,
    val configuredDividendPeriodSeconds: Long,
    val effectiveDividendPeriodSeconds: Long,
    val annualizedSimpleDividendRate: Double,
    val maxConfiguredLeveragedPrice: Double,
    val effectiveMaxNewOrderLeverage: Int,
    val effectiveMaxNewOrderPrincipal: Double,
    val effectiveMaxNewOrderExposure: Double,
    val rejectedAccounts: Int,
    val rejectedPositions: Int,
) {
    val complete: Boolean get() = rejectedAccounts == 0 && rejectedPositions == 0

    fun summary(): Map<String, Any?> =
        linkedMapOf(
            "status" to if (complete) "ready" else "partial",
            "complete" to complete,
            "timestamp" to timestamp,
            "accounts" to
                linkedMapOf(
                    "total" to accounts,
                    "withPositions" to accountsWithPositions,
                    "positiveEquity" to positiveEquityAccounts,
                    "rejected" to rejectedAccounts,
                ),
            "positions" to
                linkedMapOf(
                    "total" to positionCounts.values.sum(),
                    "bySide" to positionCounts,
                    "shortReceivingDividend" to shortPositionsReceivingDividend,
                    "rejected" to rejectedPositions,
                    "maxObservedLeverage" to maxObservedLeverage,
                ),
            "money" to
                linkedMapOf(
                    "tradingBalance" to tradingBalance,
                    "positiveTradingBalance" to positiveTradingBalance,
                    "negativeTradingBalance" to negativeTradingBalance,
                    "positionPrincipal" to positionPrincipal,
                    "grossExposure" to grossExposure,
                    "unrealizedPnl" to unrealizedPnl,
                    "accountEquity" to accountEquity,
                    "redeemableLiabilityOutsideBankAudit" to redeemableLiability,
                    "lifetimeDividends" to lifetimeDividends,
                    "nextDividendLiability" to nextDividendLiability,
                    "shortDividendLiability" to shortDividendLiability,
                ),
            "policy" to
                linkedMapOf(
                    "commissionRate" to commissionRate,
                    "configuredDividendRatePerPayout" to configuredDividendRatePerPayout,
                    "effectiveDividendRatePerPayout" to effectiveDividendRatePerPayout,
                    "configuredDividendPeriodSeconds" to configuredDividendPeriodSeconds,
                    "effectiveDividendPeriodSeconds" to effectiveDividendPeriodSeconds,
                    "annualizedSimpleDividendRate" to annualizedSimpleDividendRate,
                    "maxConfiguredLeveragedPrice" to maxConfiguredLeveragedPrice,
                    "effectiveMaxNewOrderLeverage" to effectiveMaxNewOrderLeverage,
                    "effectiveMaxNewOrderPrincipal" to effectiveMaxNewOrderPrincipal,
                    "effectiveMaxNewOrderExposure" to effectiveMaxNewOrderExposure,
                ),
            "risks" to
                buildList {
                    add("external_prices_drive_internal_liability")
                    add("trading_and_position_equity_excluded_from_bank_known_supply")
                    if (configuredDividendRatePerPayout != effectiveDividendRatePerPayout || configuredDividendPeriodSeconds != effectiveDividendPeriodSeconds) {
                        add("configured_dividend_policy_is_safety_clamped")
                    }
                },
            "scope" to "network-shared ARC stock repositories; aggregate only; no player or position identifiers",
        )

    fun metricPoints(): List<MetricPoint> =
        buildList {
            add(point("arc_stock_collection_success", "Whether the latest stock liability snapshot completed", 1.0))
            add(point("arc_stock_snapshot_complete", "Whether every stock account and position had finite priced data", if (complete) 1.0 else 0.0))
            add(accountPoint("total", accounts))
            add(accountPoint("with_positions", accountsWithPositions))
            add(accountPoint("positive_equity", positiveEquityAccounts))
            positionCounts.forEach { (side, count) ->
                add(
                    MetricPoint(
                        "arc_stock_positions",
                        "Open ARC stock positions by bounded side",
                        count.toDouble(),
                        mapOf("side" to side),
                    ),
                )
            }
            add(moneyPoint("trading_balance", tradingBalance))
            add(moneyPoint("positive_trading_balance", positiveTradingBalance))
            add(moneyPoint("position_principal", positionPrincipal))
            add(moneyPoint("account_equity", accountEquity))
            add(moneyPoint("redeemable_liability", redeemableLiability))
            add(moneyPoint("lifetime_dividends", lifetimeDividends))
            add(moneyPoint("next_dividend", nextDividendLiability))
            add(moneyPoint("short_next_dividend", shortDividendLiability))
            add(point("arc_stock_gross_exposure_currency", "Gross leveraged ARC stock exposure", grossExposure))
            add(point("arc_stock_unrealized_pnl_currency", "Aggregate unrealized ARC stock profit and loss", unrealizedPnl))
            add(point("arc_stock_max_observed_leverage", "Maximum leverage among open ARC stock positions", maxObservedLeverage))
            add(point("arc_stock_short_positions_receiving_dividend", "Short positions eligible for a positive dividend under the current implementation", shortPositionsReceivingDividend.toDouble()))
            add(point("arc_stock_annualized_simple_dividend_rate", "Simple annualized dividend rate implied by the current payout floor", annualizedSimpleDividendRate))
            add(point("arc_stock_snapshot_timestamp_seconds", "Unix timestamp of the latest stock liability snapshot", timestamp / 1_000.0))
        }

    private fun accountPoint(state: String, value: Int): MetricPoint =
        MetricPoint(
            "arc_stock_accounts",
            "ARC stock account counts",
            value.toDouble(),
            mapOf("state" to state),
        )

    private fun moneyPoint(component: String, value: Double): MetricPoint =
        MetricPoint(
            "arc_stock_money_currency",
            "ARC stock balances and liabilities outside the wallet and Bank snapshot",
            value,
            mapOf("component" to component),
        )

    private fun point(name: String, description: String, value: Double): MetricPoint =
        MetricPoint(name, description, value)
}

/** Pure aggregation of identity-free stock observations for ops and Prometheus. */
internal class StockAuditService {
    fun summarize(sample: StockAuditSample): StockAuditSnapshot {
        val validAccounts = sample.accounts.filter(::validAccount)
        val rejectedAccounts = sample.accounts.size - validAccounts.size
        var rejectedPositions = 0
        val accounts =
            validAccounts.map { account ->
                val validPositions = account.positions.filter(::validPosition)
                rejectedPositions += account.positions.size - validPositions.size
                account to validPositions
            }
        val positions = accounts.flatMap { it.second }
        val accountEquities =
            accounts.map { (account, accountPositions) ->
                account.tradingBalance + accountPositions.sumOf { it.principal + it.unrealizedPnl }
            }
        val effectivePeriod = sample.effectiveDividendPeriodSeconds.coerceAtLeast(1L)
        val annualizedSimpleRate =
            sample.effectiveDividendRatePerPayout * SECONDS_PER_YEAR.toDouble() / effectivePeriod.toDouble()

        return StockAuditSnapshot(
            timestamp = sample.timestamp,
            accounts = accounts.size,
            accountsWithPositions = accounts.count { it.second.isNotEmpty() },
            positiveEquityAccounts = accountEquities.count { it > 0.0 },
            positionCounts =
                linkedMapOf(
                    StockAuditSide.LONG.label to positions.count { it.side == StockAuditSide.LONG },
                    StockAuditSide.SHORT.label to positions.count { it.side == StockAuditSide.SHORT },
                ),
            tradingBalance = accounts.sumOf { it.first.tradingBalance },
            positiveTradingBalance = accounts.sumOf { max(0.0, it.first.tradingBalance) },
            negativeTradingBalance = -accounts.sumOf { minOf(0.0, it.first.tradingBalance) },
            positionPrincipal = positions.sumOf { it.principal },
            grossExposure = positions.sumOf { it.principal * it.leverage },
            unrealizedPnl = positions.sumOf { it.unrealizedPnl },
            accountEquity = accountEquities.sum(),
            redeemableLiability = accountEquities.sumOf { max(0.0, it) },
            lifetimeDividends = accounts.sumOf { it.first.lifetimeDividends },
            nextDividendLiability = positions.sumOf { it.nextDividend },
            shortDividendLiability = positions.filter { it.side == StockAuditSide.SHORT }.sumOf { it.nextDividend },
            shortPositionsReceivingDividend = positions.count { it.side == StockAuditSide.SHORT && it.nextDividend > 0.0 },
            maxObservedLeverage = positions.maxOfOrNull { it.leverage } ?: 0.0,
            commissionRate = sample.commissionRate,
            configuredDividendRatePerPayout = sample.configuredDividendRatePerPayout,
            effectiveDividendRatePerPayout = sample.effectiveDividendRatePerPayout,
            configuredDividendPeriodSeconds = sample.configuredDividendPeriodSeconds,
            effectiveDividendPeriodSeconds = effectivePeriod,
            annualizedSimpleDividendRate = annualizedSimpleRate,
            maxConfiguredLeveragedPrice = sample.maxConfiguredLeveragedPrice,
            effectiveMaxNewOrderLeverage = sample.effectiveMaxNewOrderLeverage,
            effectiveMaxNewOrderPrincipal = sample.effectiveMaxNewOrderPrincipal,
            effectiveMaxNewOrderExposure = sample.effectiveMaxNewOrderExposure,
            rejectedAccounts = rejectedAccounts,
            rejectedPositions = rejectedPositions,
        )
    }

    private fun validAccount(account: StockAuditAccountObservation): Boolean =
        account.tradingBalance.isFinite() && account.lifetimeDividends.isFinite()

    private fun validPosition(position: StockAuditPositionObservation): Boolean =
        position.priced &&
            position.principal.isFinite() &&
            position.principal > 0.0 &&
            position.leverage.isFinite() &&
            position.leverage >= 1.0 &&
            position.unrealizedPnl.isFinite() &&
            position.nextDividend.isFinite() &&
            position.nextDividend >= 0.0

    private companion object {
        const val SECONDS_PER_YEAR = 365L * 24L * 60L * 60L
    }
}
