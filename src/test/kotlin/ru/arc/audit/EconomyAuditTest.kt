package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import ru.arc.core.TestTimeProvider

class EconomyAuditTest : FreeSpec({
    "attribution" - {
        "classifies Jobs deposits and strips call trace from reason" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:com.gamingmesh.jobs.economy.VaultEconomy",
                    125.0,
                    "Vault",
                    "Survival",
                )

            attribution.metadata.source shouldBe EconomySource.JOBS
            attribution.metadata.flow shouldBe EconomyFlow.MINT
            attribution.metadata.currency shouldBe "vault"
            attribution.metadata.server shouldBe "survival"
            attribution.reason shouldBe "Deposit"
        }

        "classifies player payments as transfers without a call trace" {
            val attribution = EconomyAttributionResolver.resolve("Payment", -50.0, "vault", "spawn")

            attribution.metadata.source shouldBe EconomySource.PLAYER_TRANSFER
            attribution.metadata.flow shouldBe EconomyFlow.TRANSFER
            attribution.type shouldBe Type.PAY
        }

        "classifies player-owned warp payments as transfers" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:com.olziedev.playerwarps.api.economy.VaultEconomy",
                    500.0,
                    "vault",
                    "survival",
                )

            attribution.metadata.source shouldBe EconomySource.PLAYER_WARPS
            attribution.metadata.flow shouldBe EconomyFlow.TRANSFER
        }

        "keeps Vault movement into and out of Bank as a transfer" {
            val depositIntoVault =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:me.dablakbandit.bank.economy.VaultHook",
                    500.0,
                    "vault",
                    "survival",
                )
            val withdrawalFromVault =
                EconomyAttributionResolver.resolve(
                    "Withdraw\nCall:me.dablakbandit.bank.economy.VaultHook",
                    -200.0,
                    "vault",
                    "survival",
                )

            depositIntoVault.metadata.source shouldBe EconomySource.BANK
            depositIntoVault.metadata.flow shouldBe EconomyFlow.TRANSFER
            withdrawalFromVault.metadata.flow shouldBe EconomyFlow.TRANSFER
        }

        "classifies Vault movement to the internal stock wallet as a transfer" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Withdraw\nCall:ru.arc.stock.StockPlayerManager",
                    -1_000.0,
                    "vault",
                    "spawn",
                )

            attribution.metadata.source shouldBe EconomySource.INTERNAL_STOCK
            attribution.metadata.flow shouldBe EconomyFlow.TRANSFER
        }

        "separates automated shop, fishing, player auction and Denizen callers" {
            val cases =
                mapOf(
                    "su.nightexpress.autosellchests.task.SellTask" to EconomySource.AUTOSELL,
                    "net.momirealms.customfishing.market.Market" to EconomySource.CUSTOM_FISHING,
                    "su.nightexpress.excellentcrates.opening.OpeningTask" to EconomySource.CRATES,
                    "com.olziedev.playerauctions.auction.Auction" to EconomySource.PLAYER_AUCTIONS,
                    "com.denizenscript.denizen.scripts.commands.core.MoneyCommand" to EconomySource.DENIZEN,
                )

            cases.forEach { (caller, expected) ->
                EconomyAttributionResolver.resolve("Deposit\nCall:$caller", 10.0, "vault", "survival")
                    .metadata.source shouldBe expected
            }
        }

        "keeps unresolved callers bounded as unknown" {
            val attribution =
                EconomyAttributionResolver.resolve("Reward\nCall:example.newplugin.RewardService", 10.0, "vault", "spawn")

            attribution.metadata.source shouldBe EconomySource.UNKNOWN
            attribution.metadata.origin shouldBe "example.newplugin.RewardService"
        }
    }

    "monitor" - {
        "records low-cardinality counters and rapid-income evidence" {
            val registry = SimpleMeterRegistry()
            val clock = TestTimeProvider(1_000)
            val config =
                TestAuditConfig(
                    largeTransactionAmount = 1_000_000.0,
                    rapidIncomeAmount = 100.0,
                    rapidIncomeTransactions = 10,
                    anomalyCooldownSeconds = 60,
                )
            val monitor = EconomyAuditMonitor(config, { registry }, clock)
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")

            monitor.observe("Player", 60.0, metadata, "Mining")
            clock.advance(1_000)
            monitor.observe("Player", 50.0, metadata, "Mining")

            registry.get("arc_economy_transactions_total").counter().count() shouldBeExactly 2.0
            registry.get("arc_economy_amount_total").counter().count() shouldBeExactly 110.0
            monitor.recent(10).shouldHaveSize(1)
            monitor.recent(10).single().kind shouldBe "rapid_income"
        }

        "suppressed anomalies do not extend the cooldown forever" {
            val clock = TestTimeProvider(1_000)
            val config = TestAuditConfig(rapidIncomeAmount = 1.0, rapidIncomeTransactions = 100, anomalyCooldownSeconds = 60)
            val monitor = EconomyAuditMonitor(config, timeProvider = clock)
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")

            monitor.observe("Player", 1.0, metadata, "first")
            clock.advance(30_000)
            monitor.observe("Player", 1.0, metadata, "suppressed")
            clock.advance(31_000)
            monitor.observe("Player", 1.0, metadata, "emitted")

            monitor.recent(10).shouldHaveSize(2)
        }
    }

    "admin command correlation" - {
        "matches canonical give take and set without leaking stale entries" {
            AdminEconomyCommandTracker.clear()

            AdminEconomyCommandTracker.track(listOf("/money", "Player", "vault", "give", "100"), "Admin", 1_000) shouldBe true
            AdminEconomyCommandTracker.consumeDelta("player", 100.0, 1_001)?.actor shouldBe "Admin"
            AdminEconomyCommandTracker.consumeDelta("player", 100.0, 1_002) shouldBe null

            AdminEconomyCommandTracker.track(listOf("cmi", "money", "take", "Player", "25"), "Server", 2_000) shouldBe true
            AdminEconomyCommandTracker.consumeDelta("Player", -25.0, 2_001)?.actor shouldBe "Server"

            AdminEconomyCommandTracker.track(listOf("money", "Player", "vault", "set", "500"), "Admin", 3_000) shouldBe true
            AdminEconomyCommandTracker.consumeSet("Player", 500.0, 3_001)?.kind shouldBe AdminEconomyCommandTracker.Kind.SET
            AdminEconomyCommandTracker.clear()
        }
    }

    "summary" - {
        "separates supply, transfer, unknown and internal flows" {
            val data = AuditData.create("Player")
            data.operation(200.0, Type.JOB, "Mining", AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT), at = 1_000)
            data.operation(-50.0, Type.SHOP, "Buy", AuditMetadata(EconomySource.SHOP, EconomyFlow.BURN), at = 2_000)
            data.operation(25.0, Type.PAY, "Friend", AuditMetadata(EconomySource.PLAYER_TRANSFER, EconomyFlow.TRANSFER), at = 3_000)
            data.operation(10.0, Type.OTHER, "Mystery", AuditMetadata(EconomySource.UNKNOWN, EconomyFlow.UNKNOWN, origin = "example.Plugin"), at = 4_000)
            data.operation(5.0, Type.STOCK, "Dividend", AuditMetadata(EconomySource.INTERNAL_STOCK, EconomyFlow.INTERNAL), at = 5_000)

            val summary = buildAuditSummary(listOf(data), 10_000, 0, 20, null, emptyList())
            val totals = summary["totals"] as Map<*, *>

            totals["minted"] shouldBe 200.0
            totals["burned"] shouldBe 50.0
            totals["classifiedMintBurnNet"] shouldBe 150.0
            totals["knownSupplyNet"] shouldBe 150.0
            totals["vaultObservedNet"] shouldBe 185.0
            totals["transferIn"] shouldBe 25.0
            totals["unknownNet"] shouldBe 10.0
            totals["internalNet"] shouldBe 5.0
            summary.containsKey("unknownOrigins") shouldBe true
        }

        "filters anomaly evidence by time and server" {
            val anomalies =
                listOf(
                    EconomyAnomaly(1_000, "rapid_income", "Old", 10.0, "jobs", "mint", "survival", "old"),
                    EconomyAnomaly(9_000, "rapid_income", "Spawn", 10.0, "jobs", "mint", "spawn", "spawn"),
                    EconomyAnomaly(9_500, "rapid_income", "Survival", 10.0, "jobs", "mint", "survival", "survival"),
                )

            val summary = buildAuditSummary(emptyList(), 10_000, 5_000, 20, "survival", anomalies)
            val recent = summary["recentAnomalies"] as List<*>

            recent.shouldHaveSize(1)
            (recent.single() as EconomyAnomaly).player shouldBe "Survival"
        }

        "derives restart-safe rapid income evidence from persisted transactions" {
            val data = AuditData.create("Player", "survival:player")
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            data.operation(60.0, Type.JOB, "Mining", metadata, at = 1_000)
            data.operation(50.0, Type.JOB, "Mining", metadata, at = 2_000)

            val summary =
                buildAuditSummary(
                    listOf(data),
                    generatedAt = 10_000,
                    since = 0,
                    limit = 20,
                    serverFilter = null,
                    anomalies = emptyList(),
                    rapidAmount = 100.0,
                    rapidTransactions = 10,
                    largeTransactionAmount = 1_000.0,
                )

            val derived = summary["derivedAnomalies"] as List<*>
            derived.shouldHaveSize(1)
            (derived.single() as Map<*, *>)["kind"] shouldBe "rapid_income_persisted"
        }
    }
})
