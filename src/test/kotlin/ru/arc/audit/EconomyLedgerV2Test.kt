package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.arc.core.TestTimeProvider
import ru.arc.util.Common
import java.util.UUID

class EconomyLedgerV2Test : FreeSpec({
    beforeEach {
        EconomyPendingContextTracker.clear()
        EconomyTransferCorrelationTracker.clear()
    }

    "serialization contract" - {
        "loads the original transaction JSON as a successful transaction" {
            val restored =
                Common.gson.fromJson(
                    """{"t":"JOB","a":125.0,"c":"legacy","ts":1000,"ts2":2000}""",
                    Transaction::class.java,
                )

            restored.context shouldBe null
            restored.normalizedRecordKind shouldBe EconomyRecordKind.TRANSACTION
            restored.normalizedStatus shouldBe EconomyEventStatus.SUCCEEDED
        }

        "round-trips every v2 evidence field" {
            val context =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    accountId = "00000000-0000-0000-0000-000000000001",
                    providerTimestamp = 1_000,
                    correlationId = "corr-1",
                    counterparty = EconomyLedgerParty("server", "Shop", "server"),
                    world = "survival",
                    sessionId = "session-1",
                    sessionStartedAt = 900,
                    balanceBefore = 100.0,
                    balanceAfter = 75.0,
                    balanceEvidence = BalanceEvidence.OBSERVED_AFTER_INFERRED_BEFORE,
                    requestedAmount = -25.0,
                    action = "buy_screen",
                    shopId = "blocks",
                    items =
                        listOf(
                            EconomyLedgerItem(
                                "blocks.stone",
                                "minecraft:stone",
                                5,
                                5.0,
                                customItemId = "SLIMEFUN_TEST_ITEM",
                            ),
                        ),
                    priceComponents = mapOf("vault" to 25.0),
                    capturedAt = 1_010,
                )
            val original =
                Transaction(
                    type = Type.SHOP,
                    amount = -25.0,
                    comment = "Withdraw",
                    timestamp = 1_000,
                    timestamp2 = 1_000,
                    source = EconomySource.SHOP,
                    flow = EconomyFlow.BURN,
                    currency = "vault",
                    server = "survival",
                    origin = "EconomyShopGUI",
                    context = context,
                )

            val restored = Common.gson.fromJson(Common.gson.toJson(original), Transaction::class.java)

            restored shouldBe original
            restored.context?.normalizedItems?.single()?.customItemId shouldBe "SLIMEFUN_TEST_ITEM"
        }
    }

    "record fidelity" - {
        "does not aggregate enriched records with distinct event identities" {
            val data = AuditData.create("Player")
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            val context = EconomyLedgerContext(correlationId = "first", recordKind = EconomyRecordKind.TRANSACTION)

            data.operation(10.0, Type.JOB, "Mining", metadata, context, at = 1_000)
            data.operation(10.0, Type.JOB, "Mining", metadata, context.copy(correlationId = "second"), at = 1_001)

            data.transactions shouldHaveSize 2
        }

        "marks inferred, exact and unchanged balance evidence explicitly" {
            EconomyBalanceObservation.inferredFromAfter(-25.0, 75.0) shouldBe
                EconomyBalanceObservation(100.0, 75.0, BalanceEvidence.OBSERVED_AFTER_INFERRED_BEFORE)
            EconomyBalanceObservation.exact(10.0, 20.0) shouldBe
                EconomyBalanceObservation(10.0, 20.0, BalanceEvidence.EXACT_BEFORE_AFTER)
            EconomyBalanceObservation.unchanged(15.0) shouldBe
                EconomyBalanceObservation(15.0, 15.0, BalanceEvidence.OBSERVED_UNCHANGED_FAILURE)
            EconomyBalanceObservation.inferredFromAfter(Double.NaN, 10.0) shouldBe null
        }
    }

    "session evidence" - {
        "retains a recently ended session for delayed provider events and then expires it" {
            val playerId = UUID.randomUUID()
            val tracker = EconomySessionTracker(idProvider = { "session-1" }, endedSessionRetentionMillis = 1_000)

            tracker.joined(playerId, "survival", 100).sessionId shouldBe "session-1"
            tracker.snapshot(playerId, "survival_nether", 200)?.world shouldBe "survival_nether"
            tracker.left(playerId, "survival_nether", 300)
            tracker.snapshot(playerId, null, 1_000)?.active shouldBe false
            tracker.snapshot(playerId, null, 1_301) shouldBe null
        }
    }

    "correlation" - {
        "attaches a structured shop attempt only to the matching balance delta" {
            val playerId = UUID.randomUUID()
            val context =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.ATTEMPT,
                    status = EconomyEventStatus.SUCCEEDED,
                    correlationId = "shop-correlation",
                    requestedAmount = -50.0,
                )
            EconomyPendingContextTracker.register(playerId, -50.0, context, 1_000)

            EconomyPendingContextTracker.consume(playerId, -25.0, 1_100) shouldBe null
            val matched = EconomyPendingContextTracker.consume(playerId, -50.0, 1_200)
            matched?.normalizedRecordKind shouldBe EconomyRecordKind.TRANSACTION
            matched?.correlationId shouldBe "shop-correlation"
        }

        "matches an AutoSell balance delta against configured multiplier outcomes" {
            val playerId = UUID.randomUUID()
            val context =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.ATTEMPT,
                    status = EconomyEventStatus.SUBMITTED,
                    correlationId = "autosell-correlation",
                    action = "auto_sell_chest",
                )
            EconomyPendingContextTracker.register(
                playerId,
                listOf(100.0, 110.0, 115.0, 125.0),
                context,
                1_000,
            )

            EconomyPendingContextTracker.consume(playerId, 117.0, 1_100) shouldBe null
            val matched = EconomyPendingContextTracker.consume(playerId, 125.0, 1_200)
            matched?.normalizedRecordKind shouldBe EconomyRecordKind.TRANSACTION
            matched?.normalizedStatus shouldBe EconomyEventStatus.SUCCEEDED
            matched?.action shouldBe "auto_sell_chest"
        }

        "does not treat a missing expected amount as a wildcard correlation" {
            val playerId = UUID.randomUUID()
            EconomyPendingContextTracker.register(
                playerId,
                null,
                EconomyLedgerContext(correlationId = "must-not-match"),
                1_000,
            )

            EconomyPendingContextTracker.consume(playerId, 50.0, 1_100) shouldBe null
        }

        "pairs complementary sides of one player transfer" {
            val debit =
                EconomyTransferCorrelationTracker.correlate(
                    account = "player-a",
                    actor = "player-b",
                    currency = "vault",
                    amount = -100.0,
                    reason = "Payment",
                    timestamp = 1_000,
                )
            val credit =
                EconomyTransferCorrelationTracker.correlate(
                    account = "player-b",
                    actor = "player-a",
                    currency = "vault",
                    amount = 100.0,
                    reason = "Payment",
                    timestamp = 1_001,
                )

            credit shouldBe debit
        }
    }

    "reporting" - {
        "separates failed attempts from supply and returns bounded detailed evidence" {
            val data = AuditData.create("Player", "survival:player")
            val transactionContext =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    correlationId = "corr-success",
                    sessionId = "session",
                    world = "survival",
                    balanceBefore = 10.0,
                    balanceAfter = 20.0,
                    providerTimestamp = 1_000,
                    items = listOf(EconomyLedgerItem("shop.item", "minecraft:stone", 1, 10.0)),
                )
            val attemptContext =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.ATTEMPT,
                    status = EconomyEventStatus.FAILED,
                    correlationId = "corr-failed",
                    action = "buy_screen",
                    failureReason = "insufficient_funds",
                    capturedAt = 2_000,
                )
            data.operation(
                10.0,
                Type.SHOP,
                "sale",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival"),
                transactionContext,
                at = 1_000,
            )
            data.operation(
                0.0,
                Type.SHOP,
                "failed",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.BURN, server = "survival"),
                attemptContext,
                at = 2_000,
            )

            val summary = buildAuditSummary(listOf(data), 3_000, 0, 20, null, emptyList())
            val totals = summary["totals"] as Map<*, *>
            val attempts = summary["attempts"] as Map<*, *>
            val coverage = summary["contextCoverage"] as Map<*, *>
            val actions = summary["actions"] as List<*>

            summary["ledgerSchemaVersion"] shouldBe 2
            totals["minted"] shouldBe 10.0
            attempts["total"] shouldBe 1L
            (attempts["byStatus"] as Map<*, *>)["failed"] shouldBe 1L
            coverage.containsKey("balance") shouldBe true
            coverage.containsKey("action") shouldBe true
            (actions.single() as Map<*, *>)["action"] shouldBe "shop_sell"
            (summary["recentFailures"] as List<*>).shouldHaveSize(1)
            (summary["recentEvents"] as List<*>).shouldHaveSize(2)
        }

        "ranks admin shop sales by exact quantity and attributed income" {
            val data = AuditData.create("Seller", "survival:seller")
            data.operation(
                100.0,
                Type.SHOP,
                "single item sale",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival"),
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    action = "sell_screen",
                    items = listOf(EconomyLedgerItem("ores.diamond", "minecraft:diamond", 10, 10.0)),
                ),
                at = 1_000,
            )
            data.operation(
                125.0,
                Type.SHOP,
                "automated batch sale",
                AuditMetadata(EconomySource.AUTOSELL, EconomyFlow.MINT, server = "survival"),
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    action = "auto_sell_chest",
                    items =
                        listOf(
                            EconomyLedgerItem("blocks.stone", "minecraft:stone", 5, 10.0),
                            EconomyLedgerItem("ores.coal", "minecraft:coal", 5, 15.0, customItemId = "CARBONADO"),
                        ),
                ),
                at = 2_000,
            )
            data.operation(
                30.0,
                Type.SHOP,
                "sale without item evidence",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival"),
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                ),
                at = 3_000,
            )

            val summary = buildAuditSummary(listOf(data), 4_000, 0, 20, null, emptyList())
            val sales = summary["adminShopSales"] as Map<*, *>
            val items = (sales["items"] as List<*>).map { it as Map<*, *> }

            sales["income"] shouldBe 255.0
            sales["attributedIncome"] shouldBe 225.0
            sales["exactIncome"] shouldBe 100.0
            sales["allocatedIncome"] shouldBe 125.0
            sales["unattributedIncome"] shouldBe 30.0
            sales["quantity"] shouldBe 20L
            items.map { it["item"] } shouldBe listOf("ores.diamond", "ores.coal", "blocks.stone")
            items.map { it["quantity"] } shouldBe listOf(10L, 5L, 5L)
            items.map { it["income"] } shouldBe listOf(100.0, 75.0, 50.0)
            items.map { it["incomeEvidence"] } shouldBe listOf("exact", "allocated", "allocated")
            items.map { it["effectiveUnitPrice"] } shouldBe listOf(10.0, 15.0, 10.0)
            items.map { it["firstTimestamp"] } shouldBe listOf(1_000L, 2_000L, 2_000L)
            items.map { it["lastTimestamp"] } shouldBe listOf(1_000L, 2_000L, 2_000L)
            items.map { it["topPlayerIncomeShare"] } shouldBe listOf(1.0, 1.0, 1.0)
            items.map { it["topPlayerQuantityShare"] } shouldBe listOf(1.0, 1.0, 1.0)
            items.map { it["customItemId"] } shouldBe listOf(null, "CARBONADO", null)
        }

        "reports only post-activation Slimefun sales as policy violations" {
            val data = AuditData.create("Seller", "survival:seller")
            fun saleContext(providerTimestamp: Long) =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    providerTimestamp = providerTimestamp,
                    action = "sell_all_command",
                    shopId = "Slimefun",
                    items =
                        listOf(
                            EconomyLedgerItem(
                                "Slimefun.page2.items.49",
                                "minecraft:activator_rail",
                                1,
                                165.0,
                                customItemId = "BASIC_CIRCUIT_BOARD",
                            ),
                        ),
                )
            data.operation(
                165.0,
                Type.SHOP,
                "pre-policy sale",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival"),
                saleContext(1_000),
                at = 1_000,
            )
            data.operation(
                330.0,
                Type.SHOP,
                "post-policy sale",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival"),
                saleContext(20_000),
                at = 20_000,
            )

            val summary =
                buildAuditSummary(
                    data = listOf(data),
                    generatedAt = 30_000,
                    since = 0,
                    limit = 20,
                    serverFilter = null,
                    anomalies = emptyList(),
                    slimefunBuyOnlyPolicyEnabled = true,
                    slimefunBuyOnlyPolicyActivatedAt = 10_000,
                )
            val policy = summary["policyViolations"] as Map<*, *>
            val policySummary = (policy["policies"] as List<*>).single() as Map<*, *>
            val recent = (policy["recent"] as List<*>).single() as Map<*, *>
            val sale = ((summary["adminShopSales"] as Map<*, *>)["items"] as List<*>).single() as Map<*, *>

            policySummary["policy"] shouldBe "slimefun_buy_only"
            policySummary["enabled"] shouldBe true
            policySummary["activatedAt"] shouldBe 10_000L
            policySummary["records"] shouldBe 1
            policySummary["operations"] shouldBe 1L
            policySummary["income"] shouldBe 330.0
            policySummary["players"] shouldBe 1
            recent["amount"] shouldBe 330.0
            recent["policy"] shouldBe "slimefun_buy_only"
            recent["evidence"] shouldBe "persisted_admin_shop_sale_after_policy_activation"
            sale["firstTimestamp"] shouldBe 1_000L
            sale["lastTimestamp"] shouldBe 20_000L
        }

        "profiles source and action concentration with bounded activity evidence" {
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            val first = AuditData.create("First", "survival:first")
            val second = AuditData.create("Second", "survival:second")
            val third = AuditData.create("Third", "survival:third")
            first.operation(100.0, Type.JOB, "Mining", metadata, at = 60_000)
            first.operation(100.0, Type.JOB, "Mining", metadata, at = 360_000)
            second.operation(200.0, Type.JOB, "Mining", metadata, at = 120_000)
            third.operation(600.0, Type.JOB, "Mining", metadata, at = 660_000)
            first.operation(
                -100.0,
                Type.SHOP,
                "Purchase",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.BURN, server = "survival"),
                at = 50_000,
            )
            second.operation(
                -300.0,
                Type.SHOP,
                "Purchase",
                AuditMetadata(EconomySource.SHOP, EconomyFlow.BURN, server = "survival"),
                at = 350_000,
            )
            first.operation(
                50.0,
                Type.PAY,
                "Friend",
                AuditMetadata(EconomySource.PLAYER_TRANSFER, EconomyFlow.TRANSFER, server = "survival"),
                at = 720_000,
            )

            val summary = buildAuditSummary(listOf(first, second, third), 900_000, 0, 20, null, emptyList())
            val sources = (summary["sources"] as List<*>).map { it as Map<*, *> }
            val actions = (summary["actions"] as List<*>).map { it as Map<*, *> }
            val source = sources.single { it["source"] == "jobs" }
            val action = actions.single { it["source"] == "jobs" }
            val shop = sources.single { it["source"] == "shop" }
            val transfer = sources.single { it["source"] == "player_transfer" }
            val distribution = source["mintDistribution"] as Map<*, *>
            val activity = source["activity"] as Map<*, *>
            val evidence = summary["balanceProfileEvidence"] as Map<*, *>

            distribution["players"] shouldBe 3
            distribution["topPlayerShare"] shouldBe 0.6
            distribution["p50"] shouldBe 200.0
            distribution["p90"] shouldBe 600.0
            distribution["p99"] shouldBe 600.0
            activity["mintPlayerBuckets"] shouldBe 4
            activity["mintActivityPlayerHoursProxy"] shouldBe (4.0 / 12.0)
            activity["mintPerActivityPlayerHourProxy"] shouldBe 3_000.0
            (action["mintDistribution"] as Map<*, *>)["topPlayerShare"] shouldBe 0.6
            (action["activity"] as Map<*, *>)["mintPlayerBuckets"] shouldBe 4
            (shop["burnDistribution"] as Map<*, *>)["players"] shouldBe 2
            (shop["burnDistribution"] as Map<*, *>)["topPlayerShare"] shouldBe 0.75
            (shop["burnDistribution"] as Map<*, *>)["p50"] shouldBe 100.0
            (shop["burnDistribution"] as Map<*, *>)["p90"] shouldBe 300.0
            (shop["activity"] as Map<*, *>)["burnPlayerBuckets"] shouldBe 2
            (shop["activity"] as Map<*, *>)["burnPerActivityPlayerHourProxy"] shouldBe 2_400.0
            (transfer["mintDistribution"] as Map<*, *>)["players"] shouldBe 0
            (transfer["activity"] as Map<*, *>)["mintPlayerBuckets"] shouldBe 0
            (transfer["activity"] as Map<*, *>)["mintPerActivityPlayerHourProxy"] shouldBe null
            evidence["distributionUnit"] shouldBe "per_player_window_mint_or_burn_total"
            evidence["percentileMethod"] shouldBe "nearest_rank"
            evidence["bucketMinutes"] shouldBe 5
            evidence["interpretation"] shouldBe "five_minute_presence_proxy_not_measured_session_duration"
        }

        "does not invent an activity bucket when an aggregate crosses the query cutoff" {
            val data = AuditData.create("Player")
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            data.operation(10.0, Type.JOB, "Mining", metadata, at = 299_990)
            data.operation(10.0, Type.JOB, "Mining", metadata, at = 300_001)

            val summary = buildAuditSummary(listOf(data), 600_000, 299_995, 20, null, emptyList())
            val source = (summary["sources"] as List<*>).single() as Map<*, *>
            val activity = source["activity"] as Map<*, *>

            activity["mintPlayerBuckets"] shouldBe 1
            activity["mintActivityPlayerHoursProxy"] shouldBe (1.0 / 12.0)
        }

        "exports low-cardinality attempt outcomes and context coverage" {
            val registry = SimpleMeterRegistry()
            val clock = TestTimeProvider(1_000)
            val monitor = EconomyAuditMonitor(TestAuditConfig(), { registry }, clock)
            val metadata = AuditMetadata(EconomySource.SHOP, EconomyFlow.BURN, server = "survival")
            val context =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    balanceBefore = 100.0,
                    balanceAfter = 90.0,
                    sessionId = "session",
                    world = "survival",
                    correlationId = "corr",
                    providerTimestamp = 1_000,
                )

            monitor.observe("Player", -10.0, metadata, "buy", context)
            monitor.observeAttempt(
                metadata,
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.ATTEMPT,
                    status = EconomyEventStatus.FAILED,
                    action = "buy_screen",
                ),
            )

            registry.get("arc_economy_attempts_total")
                .tags("source", "shop", "status", "failed", "action", "buy_screen")
                .counter().count() shouldBeExactly 1.0
            registry.get("arc_economy_context_total")
                .tags("source", "shop", "field", "balance", "present", "true")
                .counter().count() shouldBeExactly 1.0
            registry.get("arc_economy_context_total")
                .tags("source", "shop", "field", "items", "present", "false")
                .counter().count() shouldBeExactly 1.0
        }

        "counts active policy violations without player or item labels" {
            val registry = SimpleMeterRegistry()
            val clock = TestTimeProvider(20_000)
            val config =
                TestAuditConfig(
                    slimefunBuyOnlyPolicyEnabled = true,
                    slimefunBuyOnlyPolicyActivatedAt = 10_000,
                )
            val monitor = EconomyAuditMonitor(config, { registry }, clock)
            val metadata = AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival")
            fun context(timestamp: Long) =
                EconomyLedgerContext(
                    recordKind = EconomyRecordKind.TRANSACTION,
                    status = EconomyEventStatus.SUCCEEDED,
                    providerTimestamp = timestamp,
                    action = "sell_all_command",
                    shopId = "Slimefun",
                    items = listOf(EconomyLedgerItem("Slimefun.page2.items.49", quantity = 1)),
                )

            monitor.observe("Before", 165.0, metadata, "pre-policy", context(9_999))
            monitor.observe("After", 165.0, metadata, "post-policy", context(10_000))

            registry.get("arc_economy_policy_violations_total")
                .tag("policy", "slimefun_buy_only")
                .counter().count() shouldBeExactly 1.0
            registry.get("arc_economy_policy_enabled")
                .tag("policy", "slimefun_buy_only")
                .gauge().value() shouldBeExactly 1.0
            registry.get("arc_economy_policy_activation_timestamp_seconds")
                .tag("policy", "slimefun_buy_only")
                .gauge().value() shouldBeExactly 10.0
            monitor.recent(20).single().kind shouldBe "policy_violation_slimefun_buy_only"
            registry.meters.flatMap { it.id.tags }.map { it.key }.toSet()
                .intersect(setOf("player", "item", "counterparty", "correlation")) shouldBe emptySet()
        }
    }
})
