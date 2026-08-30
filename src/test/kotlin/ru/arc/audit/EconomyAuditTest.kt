package ru.arc.audit

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
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

        "classifies ArcEcoJobs batch payouts as Jobs" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:ru.ruscrafting.ecojobs.payout.RewardBatcher",
                    15.0,
                    "vault",
                    "survival",
                )

            attribution.metadata.source shouldBe EconomySource.JOBS
            attribution.metadata.flow shouldBe EconomyFlow.MINT
        }

        "classifies current RusCrafting and command origins without unknown leakage" {
            val cases =
                mapOf(
                    "ru.ruscrafting.farms.paper.VaultFarmEconomyGateway" to EconomySource.FARMS,
                    "net.advancedplugins.ae.features.enchanter.PaymentHandler" to EconomySource.ADVANCED_ENCHANTMENTS,
                    "net.minecraft.commands.execution.tasks.ExecuteCommand" to EconomySource.ADMIN_COMMAND,
                )

            cases.forEach { (origin, expected) ->
                EconomyAttributionResolver.resolve("Deposit\nCall:$origin", 10.0, "vault", "survival")
                    .metadata.source shouldBe expected
            }
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

        "classifies AdvancedEnchantments enchanter purchases as burns" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Withdraw\nCall:net.advancedplugins.ae.features.enchanter.PaymentHandler",
                    -12_500.0,
                    "vault",
                    "spawn",
                )

            attribution.metadata.source shouldBe EconomySource.ADVANCED_ENCHANTMENTS
            attribution.metadata.source.label shouldBe "advanced_enchantments"
            attribution.metadata.flow shouldBe EconomyFlow.BURN
            attribution.reason shouldBe "Withdraw"
        }

        "classifies ARC treasure payouts separately from generic ARC activity" {
            val attribution =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:ru.arc.treasure.core.TreasureService",
                    4_500.0,
                    "vault",
                    "spawn",
                )

            attribution.metadata.source shouldBe EconomySource.TREASURE
            attribution.metadata.source.label shouldBe "treasure"
            attribution.metadata.flow shouldBe EconomyFlow.MINT
        }

        "classifies season project and dungeon burns into exact budget portfolios" {
            val project =
                EconomyAttributionResolver.resolve(
                    "arc-season:public_projects:action-project-1\nCall:ru.arc.contracts.RedisEconomySeasonMoneyGateway",
                    -1_000.0,
                    "vault",
                    "spawn",
                )
            val dungeon =
                EconomyAttributionResolver.resolve(
                    "arc-season:dungeon_entry:action-pass-1\nCall:ru.arc.contracts.RedisEconomySeasonMoneyGateway",
                    -750.0,
                    "vault",
                    "spawn",
                )

            project.metadata.source shouldBe EconomySource.PUBLIC_PROJECTS
            project.metadata.source.label shouldBe "public_projects"
            project.metadata.flow shouldBe EconomyFlow.BURN
            dungeon.metadata.source shouldBe EconomySource.DUNGEON_ENTRY
            dungeon.metadata.source.label shouldBe "dungeon_entry"
            dungeon.metadata.flow shouldBe EconomyFlow.BURN
        }

        "classifies mount purchases as burns and compensating refunds as internal" {
            val transactionId = "57f33e4f-17c6-4a91-85e1-29366ca1d13c"
            val purchase =
                EconomyAttributionResolver.resolve(
                    "arc-mount:$transactionId\nCall:ru.arc.mounts.RedisEconomyMountWallet",
                    -5_000_000.0,
                    "vault",
                    "spawn",
                )
            val refund =
                EconomyAttributionResolver.resolve(
                    "arc-mount-refund:$transactionId\nCall:ru.arc.mounts.RedisEconomyMountWallet",
                    5_000_000.0,
                    "vault",
                    "spawn",
                )

            purchase.metadata.source shouldBe EconomySource.MOUNTS
            purchase.metadata.flow shouldBe EconomyFlow.BURN
            refund.metadata.source shouldBe EconomySource.MOUNTS
            refund.metadata.flow shouldBe EconomyFlow.INTERNAL
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

        "normalizes transaction actions into a bounded source-aware vocabulary" {
            EconomyActionClassifier.classify(EconomySource.BANK, -100.0) shouldBe EconomyAction.WALLET_TO_BANK
            EconomyActionClassifier.classify(EconomySource.BANK, 100.0) shouldBe EconomyAction.BANK_TO_WALLET
            EconomyActionClassifier.classify(EconomySource.GAMBLING, -50.0) shouldBe EconomyAction.GAMBLING_WAGER
            EconomyActionClassifier.classify(EconomySource.GAMBLING, 75.0) shouldBe EconomyAction.GAMBLING_PAYOUT
            EconomyActionClassifier.classify(EconomySource.SHOP, 20.0, "auto_sell_chest") shouldBe EconomyAction.AUTOSELL_SALE
            EconomyActionClassifier.classify(EconomySource.INTERNAL_STOCK, -100.0, "stock_buy") shouldBe EconomyAction.STOCK_BUY
            EconomyActionClassifier.classify(EconomySource.INTERNAL_STOCK, -100.0, "stock_short") shouldBe EconomyAction.STOCK_SHORT
            EconomyActionClassifier.classify(EconomySource.INTERNAL_STOCK, 100.0, "stock_close") shouldBe EconomyAction.STOCK_CLOSE
            EconomyActionClassifier.classify(EconomySource.INTERNAL_STOCK, 100.0, "stock_dividend") shouldBe EconomyAction.STOCK_DIVIDEND
            EconomyActionClassifier.classify(EconomySource.JOBS, 100.0) shouldBe EconomyAction.JOB_REWARD
            EconomyActionClassifier.classify(EconomySource.QUESTS, 100.0) shouldBe EconomyAction.QUEST_REWARD
            EconomyActionClassifier.classify(EconomySource.TREASURE, 100.0) shouldBe EconomyAction.TREASURE_REWARD
            EconomyActionClassifier.classify(EconomySource.LANDS, -1_000.0) shouldBe EconomyAction.LAND_CHARGE
            EconomyActionClassifier.classify(EconomySource.LANDS, 150.0) shouldBe EconomyAction.LAND_CREDIT
            EconomyActionClassifier.classify(EconomySource.ADVANCED_ENCHANTMENTS, -1_000.0) shouldBe
                EconomyAction.ENCHANTMENT_PURCHASE
            EconomyActionClassifier.classify(
                EconomySource.CMI,
                -300.0,
                providerOrigin = "com.Zrips.CMI.commands.list.repair",
            ) shouldBe EconomyAction.CMI_REPAIR
            EconomyActionClassifier.classify(
                EconomySource.CMI,
                -20_000.0,
                providerOrigin = "com.Zrips.CMI.commands.list.flightcharge",
            ) shouldBe EconomyAction.CMI_FLIGHT_CHARGE
            EconomyActionClassifier.classify(
                EconomySource.CMI,
                -100.0,
                providerOrigin = "com.Zrips.CMI.Modules.CmdCost.CMICommandCostManager",
            ) shouldBe EconomyAction.CMI_COMMAND_COST
            EconomyActionClassifier.classify(EconomySource.CMI, -50.0, providerOrigin = "untrusted.dynamic.Class") shouldBe
                EconomyAction.CMI_SERVICE_CHARGE
            EconomyActionClassifier.classify(EconomySource.CMI, -300.0, providerAction = "cmi_repair") shouldBe
                EconomyAction.CMI_REPAIR
            EconomyActionClassifier.classify(EconomySource.DENIZEN, -300.0, providerAction = "cmi_repair") shouldBe
                EconomyAction.SOURCE_DEBIT
            EconomyActionClassifier.classify(EconomySource.DENIZEN, 20.0, "untrusted arbitrary script id") shouldBe
                EconomyAction.SOURCE_CREDIT
        }

        "keeps CMI pay and cheque movements out of supply mint and burn" {
            val payOut =
                EconomyAttributionResolver.resolve(
                    "Withdraw\nCall:com.Zrips.CMI.commands.list.pay",
                    -1_000.0,
                    "vault",
                    "spawn",
                )
            val chequeRedeem =
                EconomyAttributionResolver.resolve(
                    "Deposit\nCall:com.Zrips.CMI.Modules.MoneyCheque.MoneyChequeListener",
                    2_000.0,
                    "vault",
                    "spawn",
                )

            payOut.metadata.source shouldBe EconomySource.CMI
            payOut.metadata.flow shouldBe EconomyFlow.TRANSFER
            EconomyActionClassifier.classify(EconomySource.CMI, -1_000.0, providerOrigin = payOut.metadata.origin) shouldBe
                EconomyAction.CMI_PAY_OUT
            chequeRedeem.metadata.flow shouldBe EconomyFlow.INTERNAL
            EconomyActionClassifier.classify(EconomySource.CMI, 2_000.0, providerOrigin = chequeRedeem.metadata.origin) shouldBe
                EconomyAction.CMI_CHEQUE_REDEEM
        }

        "recognizes EliteMobs gambling only after the economy bridge is skipped" {
            val gambling =
                EconomyAttributionResolver.resolve(
                    "Withdraw\nCall:com.magmaguy.elitemobs.economy.GamblingEconomyHandler",
                    -500.0,
                    "vault",
                    "spawn",
                )

            gambling.metadata.source shouldBe EconomySource.GAMBLING
            EconomyActionClassifier.classify(gambling.metadata.source, -500.0) shouldBe EconomyAction.GAMBLING_WAGER
        }
    }

    "monitor" - {
        "preserves a trusted bounded CMI action in Prometheus labels" {
            val registry = SimpleMeterRegistry()
            val monitor = EconomyAuditMonitor(TestAuditConfig(), { registry })
            val metadata = AuditMetadata(EconomySource.CMI, EconomyFlow.BURN, server = "spawn")

            monitor.observe(
                "Player",
                -300.0,
                metadata,
                "Withdraw",
                EconomyLedgerContext(action = "cmi_repair"),
            )

            registry.get("arc_economy_action_transactions_total")
                .tags("source", "cmi", "action", "cmi_repair", "direction", "expense")
                .counter().count() shouldBeExactly 1.0
        }

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
            registry.get("arc_economy_action_transactions_total")
                .tags("source", "jobs", "action", "job_reward", "direction", "income")
                .counter().count() shouldBeExactly 2.0
            registry.get("arc_economy_action_amount_total")
                .tags("source", "jobs", "action", "job_reward", "direction", "income")
                .counter().count() shouldBeExactly 110.0
            monitor.recent(10).shouldHaveSize(1)
            monitor.recent(10).single().kind shouldBe "rapid_income"
        }

        "records Jobs profession and activity metrics without target or player labels" {
            val registry = SimpleMeterRegistry()
            val monitor = EconomyAuditMonitor(TestAuditConfig(), { registry })
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            val context =
                EconomyLedgerContext(
                    action = "job_reward",
                    jobBreakdown =
                        listOf(
                            EconomyJobRewardComponent("hunter", "kill", "minecraft:zombie", "natural", 30.0, 3),
                            EconomyJobRewardComponent("hunter", "kill", "minecraft:zombie", "spawner", 20.0, 4),
                        ),
                )

            monitor.observe("Player", 50.0, metadata, "Jobs payout", context)

            registry.get("arc_jobs_reward_amount_total")
                .tags("job", "hunter", "activity", "kill", "origin", "natural")
                .counter().count() shouldBeExactly 30.0
            registry.get("arc_jobs_reward_actions_total")
                .tags("job", "hunter", "activity", "kill", "origin", "spawner")
                .counter().count() shouldBeExactly 4.0
            registry.meters.flatMap { it.id.tags }.map { it.key }.toSet()
                .intersect(setOf("player", "target", "entity", "account")) shouldBe emptySet()
        }

        "does not export a Jobs breakdown that disagrees with the provider amount" {
            val registry = SimpleMeterRegistry()
            val monitor = EconomyAuditMonitor(TestAuditConfig(), { registry })
            monitor.observe(
                "Player",
                50.0,
                AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival"),
                "Jobs payout",
                EconomyLedgerContext(
                    action = "job_reward",
                    jobBreakdown =
                        listOf(EconomyJobRewardComponent("hunter", "kill", "minecraft:zombie", "natural", 49.0, 1)),
                ),
            )

            registry.find("arc_jobs_reward_amount_total").counter() shouldBe null
            registry.find("arc_jobs_reward_actions_total").counter() shouldBe null
        }

        "exports the exact Prometheus metric names consumed by the gameplay dashboard" {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val monitor = EconomyAuditMonitor(TestAuditConfig(), { registry })
            monitor.observe(
                "Player",
                25.0,
                AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival"),
                "Jobs payout",
                EconomyLedgerContext(
                    action = "job_reward",
                    jobBreakdown =
                        listOf(EconomyJobRewardComponent("miner", "break", "minecraft:stone", "not_applicable", 25.0, 5)),
                ),
            )

            val scrape = registry.scrape()
            scrape.contains("arc_jobs_reward_amount_total_currency_total") shouldBe true
            scrape.contains("arc_jobs_reward_actions_total") shouldBe true
            scrape.contains("source=\"jobs\"") shouldBe true
            scrape.contains("job=\"miner\"") shouldBe true
            scrape.contains("target=") shouldBe false
            scrape.contains("player=") shouldBe false
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
        "recognizes Denizen-backed console economy commands as one bounded source" {
            val caller =
                EconomyCommandOriginResolver.resolve(
                    arrayOf(
                        StackTraceElement("org.bukkit.craftbukkit.CraftServer", "dispatchCommand", "CraftServer.java", 1),
                        StackTraceElement("com.denizenscript.denizen.scripts.commands.server.ExecuteCommand", "execute", "ExecuteCommand.java", 2),
                    ),
                )

            caller.source shouldBe EconomySource.DENIZEN
            caller.origin shouldBe "com.denizenscript.denizen.scripts.commands.server.ExecuteCommand"
        }

        "recognizes Quests reward commands dispatched through the console" {
            val caller =
                EconomyCommandOriginResolver.resolve(
                    arrayOf(
                        StackTraceElement("net.minecraft.commands.execution.tasks.ExecuteCommand", "execute", "ExecuteCommand.java", 1),
                        StackTraceElement(
                            "com.leonardobishop.quests.bukkit.util.DispatchUtils",
                            "dispatchCommand",
                            "DispatchUtils.java",
                            2,
                        ),
                        StackTraceElement(
                            "com.denizenscript.denizen.scripts.commands.server.ExecuteCommand",
                            "execute",
                            "ExecuteCommand.java",
                            3,
                        ),
                    ),
                )

            caller.source shouldBe EconomySource.QUESTS
            caller.origin shouldBe "com.leonardobishop.quests.bukkit.util.DispatchUtils"
        }

        "keeps ordinary console economy commands administrative" {
            val caller =
                EconomyCommandOriginResolver.resolve(
                    arrayOf(StackTraceElement("org.bukkit.craftbukkit.CraftServer", "dispatchCommand", "CraftServer.java", 1)),
                )

            caller.source shouldBe EconomySource.ADMIN_COMMAND
            caller.origin shouldBe "Server"
        }

        "matches canonical give take and set without leaking stale entries" {
            AdminEconomyCommandTracker.clear()

            AdminEconomyCommandTracker.track(listOf("/money", "Player", "vault", "give", "100"), "Admin", 1_000) shouldBe true
            val adminPending = AdminEconomyCommandTracker.consumeDelta("player", 100.0, 1_001)
            adminPending?.actor shouldBe "Admin"
            adminPending?.source shouldBe EconomySource.ADMIN_COMMAND
            adminPending?.origin shouldBe "Admin"
            AdminEconomyCommandTracker.consumeDelta("player", 100.0, 1_002) shouldBe null

            AdminEconomyCommandTracker.track(listOf("cmi", "money", "take", "Player", "25"), "Server", 2_000) shouldBe true
            AdminEconomyCommandTracker.consumeDelta("Player", -25.0, 2_001)?.actor shouldBe "Server"

            AdminEconomyCommandTracker.track(
                listOf("cmi", "money", "give", "Player", "50"),
                "Server",
                2_100,
                source = EconomySource.DENIZEN,
                origin = "com.denizenscript.denizen.scripts.commands.server.ExecuteCommand",
            ) shouldBe true
            val denizenPending = AdminEconomyCommandTracker.consumeDelta("Player", 50.0, 2_101)
            denizenPending?.source shouldBe EconomySource.DENIZEN
            denizenPending?.origin shouldBe "com.denizenscript.denizen.scripts.commands.server.ExecuteCommand"

            AdminEconomyCommandTracker.track(listOf("money", "Player", "vault", "set", "500"), "Admin", 3_000) shouldBe true
            AdminEconomyCommandTracker.consumeSet("Player", 500.0, 3_001)?.kind shouldBe AdminEconomyCommandTracker.Kind.SET

            AdminEconomyCommandTracker.track(listOf("money", "Player", "tokens", "give", "10"), "Admin", 4_000) shouldBe true
            AdminEconomyCommandTracker.consumeDelta("Player", 10.0, 4_001, currency = "vault") shouldBe null
            AdminEconomyCommandTracker.consumeDelta("Player", 10.0, 4_002, currency = "tokens")?.currency shouldBe "tokens"
            AdminEconomyCommandTracker.clear()
        }
    }

    "summary" - {
        "uses an exact absolute boundary without rounding to hours" {
            val repository = InMemoryAuditRepository()
            val clock = TestTimeProvider(10_000L)
            val service = AuditService(repository, TestAuditConfig(), timeProvider = clock)
            val jobs = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            service.economyOperation("Player1", 100.0, Type.JOB, "before", jobs)
            clock.advance(1_001L)
            service.economyOperation("Player1", 200.0, Type.JOB, "after", jobs)

            val summary = service.economySummarySince(10_001L, 20)
            val totals = summary["totals"] as Map<*, *>

            summary["since"] shouldBe 10_001L
            totals["minted"] shouldBe 200.0
        }

        "rejects future and over-retention absolute boundaries" {
            val clock = TestTimeProvider(10_000L)
            val service = AuditService(InMemoryAuditRepository(), TestAuditConfig(), timeProvider = clock)

            shouldThrow<IllegalArgumentException> {
                service.economySummarySince(10_000L, 20)
            }
            shouldThrow<IllegalArgumentException> {
                service.economySummarySince(10_000L - 31L * 24 * 60 * 60 * 1_000 - 1, 20)
            }
        }

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
