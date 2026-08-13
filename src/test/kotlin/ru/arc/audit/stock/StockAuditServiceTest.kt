package ru.arc.audit.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe

class StockAuditServiceTest :
    FreeSpec({
        "aggregates redeemable stock liability without player identity" {
            val snapshot =
                StockAuditService().summarize(
                    StockAuditSample(
                        timestamp = 1_000L,
                        accounts =
                            listOf(
                                StockAuditAccountObservation(
                                    tradingBalance = 100.0,
                                    lifetimeDividends = 75.0,
                                    positions =
                                        listOf(
                                            StockAuditPositionObservation(StockAuditSide.LONG, true, 1_000.0, 2.0, 100.0, 20.0),
                                            StockAuditPositionObservation(StockAuditSide.SHORT, true, 500.0, 3.0, -50.0, 10.0),
                                        ),
                                ),
                                StockAuditAccountObservation(
                                    tradingBalance = -20.0,
                                    lifetimeDividends = 25.0,
                                    positions = emptyList(),
                                ),
                            ),
                        commissionRate = 0.005,
                        configuredDividendRatePerPayout = 0.02,
                        effectiveDividendRatePerPayout = 0.0002,
                        configuredDividendPeriodSeconds = 86_400L,
                        effectiveDividendPeriodSeconds = 86_400L,
                        maxConfiguredLeveragedPrice = 5_000_000.0,
                        effectiveMaxNewOrderLeverage = 10,
                        effectiveMaxNewOrderPrincipal = 100_000.0,
                        effectiveMaxNewOrderExposure = 1_000_000.0,
                    ),
                )

            snapshot.accounts shouldBe 2
            snapshot.accountsWithPositions shouldBe 1
            snapshot.positiveEquityAccounts shouldBe 1
            snapshot.positionCounts shouldBe mapOf("long" to 1, "short" to 1)
            snapshot.tradingBalance shouldBeExactly 80.0
            snapshot.positiveTradingBalance shouldBeExactly 100.0
            snapshot.negativeTradingBalance shouldBeExactly 20.0
            snapshot.positionPrincipal shouldBeExactly 1_500.0
            snapshot.grossExposure shouldBeExactly 3_500.0
            snapshot.unrealizedPnl shouldBeExactly 50.0
            snapshot.accountEquity shouldBeExactly 1_630.0
            snapshot.redeemableLiability shouldBeExactly 1_650.0
            snapshot.lifetimeDividends shouldBeExactly 100.0
            snapshot.nextDividendLiability shouldBeExactly 30.0
            snapshot.shortDividendLiability shouldBeExactly 10.0
            snapshot.shortPositionsReceivingDividend shouldBe 1
            snapshot.maxObservedLeverage shouldBeExactly 3.0
            snapshot.annualizedSimpleDividendRate shouldBeExactly 0.07300000000000001
        }

        "rejects non-finite observations before they reach metrics" {
            val snapshot =
                StockAuditService().summarize(
                    StockAuditSample(
                        timestamp = 2_000L,
                        accounts =
                            listOf(
                                StockAuditAccountObservation(Double.NaN, 0.0, emptyList()),
                                StockAuditAccountObservation(
                                    10.0,
                                    0.0,
                                    listOf(StockAuditPositionObservation(StockAuditSide.LONG, true, Double.POSITIVE_INFINITY, 1.0, 0.0, 0.0)),
                                ),
                            ),
                        commissionRate = 0.005,
                        configuredDividendRatePerPayout = 0.02,
                        effectiveDividendRatePerPayout = 0.0002,
                        configuredDividendPeriodSeconds = 86_400L,
                        effectiveDividendPeriodSeconds = 86_400L,
                        maxConfiguredLeveragedPrice = 5_000_000.0,
                        effectiveMaxNewOrderLeverage = 10,
                        effectiveMaxNewOrderPrincipal = 100_000.0,
                        effectiveMaxNewOrderExposure = 1_000_000.0,
                    ),
                )

            snapshot.accounts shouldBe 1
            snapshot.rejectedAccounts shouldBe 1
            snapshot.rejectedPositions shouldBe 1
            snapshot.complete shouldBe false
            snapshot.positionCounts shouldBe mapOf("long" to 0, "short" to 0)
        }
    })
