package ru.arc.audit.bank

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class BankAuditServiceTest :
    StringSpec({
        "aggregates available balance pending interest and known supply" {
            val service = BankAuditService(TestBankAuditConfig(topAccounts = 2)) { 1_000L }

            val snapshot =
                service.accept(
                    read(
                        account("a", wallet = 100.0, bank = 100.0, pending = 10.0),
                        account("b", wallet = 50.0, bank = 400.0),
                    ),
                )

            snapshot.complete shouldBe true
            snapshot.walletSupply shouldBeExactly 150.0
            snapshot.bankBalance shouldBeExactly 500.0
            snapshot.pendingInterest shouldBeExactly 10.0
            snapshot.bankSupply shouldBeExactly 510.0
            snapshot.knownSupply shouldBeExactly 660.0
            snapshot.positiveBankAccounts shouldBe 2
            snapshot.bankQuantiles["0.50"]!! shouldBeExactly 110.0
            snapshot.bankQuantiles["0.90"]!! shouldBeExactly 400.0
            snapshot.topBankAccounts.first().playerId shouldBe "b"
            snapshot.bankSupplyDelta.shouldBeNull()
        }

        "observes account movement and keeps player identity out of metric labels" {
            var now = 1_000L
            val service = BankAuditService(TestBankAuditConfig(minimumChange = 0.01)) { now }
            service.accept(
                read(
                    account("a", wallet = 100.0, bank = 100.0, pending = 10.0),
                    account("b", wallet = 50.0, bank = 400.0),
                ),
            )

            now = 2_000L
            val snapshot =
                service.accept(
                    read(
                        account("a", wallet = 100.0, bank = 125.0),
                        account("b", wallet = 50.0, bank = 350.0),
                    ),
                )

            snapshot.bankSupplyDelta!! shouldBeExactly -35.0
            snapshot.observedBankIncrease shouldBeExactly 15.0
            snapshot.observedBankDecrease shouldBeExactly 50.0
            snapshot.changedAccounts shouldBe 2
            val summary = service.summary(10)
            val changes = summary["recentBankChanges"] as List<*>
            changes.size shouldBe 2
            service.metricPoints(snapshot).flatMap { it.tags.keys }.contains("player") shouldBe false
            service.metricPoints(snapshot).flatMap { it.tags.values }.contains("Alice") shouldBe false
        }

        "classifies interest capitalization without inventing a Bank supply change" {
            val service = BankAuditService(TestBankAuditConfig())
            service.accept(read(account("a", bank = 100.0, pending = 20.0)))

            val snapshot = service.accept(read(account("a", bank = 120.0, pending = 0.0)))

            snapshot.bankSupplyDelta!! shouldBeExactly 0.0
            snapshot.changedAccounts shouldBe 0
            snapshot.classifiedChanges shouldBe 1
            snapshot.changeTypes shouldBe mapOf("observed_interest_capitalization" to 1)
            @Suppress("UNCHECKED_CAST")
            val change = (service.summary(10)["recentBankChanges"] as List<Map<String, Any?>>).single()
            change["classification"] shouldBe "observed_interest_capitalization"
            change["classificationEvidence"] shouldBe "snapshot_delta_inferred"
        }

        "classifies transfers interest accrual and unexplained Bank supply changes" {
            val service = BankAuditService(TestBankAuditConfig(minimumChange = 0.01))
            service.accept(
                read(
                    account("a", wallet = 100.0, bank = 100.0),
                    account("b", wallet = 100.0, bank = 100.0),
                    account("c", wallet = 100.0, bank = 100.0),
                    account("d", wallet = 100.0, bank = 100.0),
                ),
            )

            val snapshot =
                service.accept(
                    read(
                        account("a", wallet = 75.0, bank = 125.0),
                        account("b", wallet = 140.0, bank = 60.0),
                        account("c", wallet = 100.0, bank = 100.0, pending = 5.0),
                        account("d", wallet = 100.0, bank = 110.0),
                    ),
                )

            snapshot.changeTypes shouldBe
                mapOf(
                    "observed_interest_accrual" to 1,
                    "observed_transfer_from_bank" to 1,
                    "observed_transfer_to_bank" to 1,
                    "unexplained_supply_increase" to 1,
                )
            val actionPoints = service.metricPoints(snapshot).filter { it.name == "arc_bank_last_change_accounts" }
            actionPoints.size shouldBe 4
            actionPoints.all { it.tags["evidence"] == "snapshot_delta_inferred" } shouldBe true
        }

        "ignores wallet-only activity outside Bank" {
            val service = BankAuditService(TestBankAuditConfig(minimumChange = 0.01))
            service.accept(read(account("a", wallet = 100.0, bank = 100.0)))

            val snapshot = service.accept(read(account("a", wallet = 250.0, bank = 100.0)))

            snapshot.classifiedChanges shouldBe 0
            snapshot.changeTypes shouldBe emptyMap()
            (service.summary(10)["recentBankChanges"] as List<*>).shouldBeEmpty()
        }

        "does not classify unchanged accounts when configured minimum is zero" {
            val service = BankAuditService(TestBankAuditConfig(minimumChange = 0.0))
            service.accept(read(account("a", wallet = 100.0, bank = 100.0)))

            val snapshot = service.accept(read(account("a", wallet = 100.0, bank = 100.0)))

            snapshot.classifiedChanges shouldBe 0
            snapshot.changeTypes shouldBe emptyMap()
        }

        "marks capped or failed reads partial and suppresses misleading total deltas" {
            val service = BankAuditService(TestBankAuditConfig())
            service.accept(read(account("a", bank = 100.0), account("b", bank = 200.0)))

            val snapshot =
                service.accept(
                    BankAuditReadResult(
                        discoveredAccounts = 2,
                        accounts = listOf(account("a", bank = 120.0)),
                        failedAccounts = 1,
                    ),
                )

            snapshot.complete shouldBe false
            snapshot.failedAccounts shouldBe 1
            snapshot.bankSupplyDelta.shouldBeNull()
            snapshot.changedAccounts shouldBe 0
            (service.summary(10)["recentBankChanges"] as List<*>).shouldBeEmpty()
            val points = service.metricPoints(snapshot)
            points.associateBy { it.name } shouldContainKey "arc_bank_snapshot_coverage_ratio"
            points.first { it.name == "arc_bank_money_currency" && it.tags["component"] == "bank_supply" }.value shouldBeExactly 300.0
        }

        "keeps the last complete baseline across a partial read" {
            val service = BankAuditService(TestBankAuditConfig())
            service.accept(read(account("a", bank = 100.0), account("b", bank = 200.0)))
            service.accept(
                BankAuditReadResult(
                    discoveredAccounts = 2,
                    accounts = listOf(account("a", bank = 120.0)),
                    failedAccounts = 1,
                ),
            )

            val recovered = service.accept(read(account("a", bank = 130.0), account("b", bank = 200.0)))

            recovered.complete shouldBe true
            recovered.bankSupplyDelta!! shouldBeExactly 30.0
            recovered.observedBankIncrease shouldBeExactly 30.0
            recovered.changedAccounts shouldBe 1
        }

        "retains the largest changes when one snapshot exceeds the evidence cap" {
            val service = BankAuditService(TestBankAuditConfig(recentChanges = 2))
            service.accept(read(account("a"), account("b"), account("c")))

            service.accept(read(account("a", bank = 1.0), account("b", bank = 10.0), account("c", bank = 100.0)))

            @Suppress("UNCHECKED_CAST")
            val changes = service.summary(10)["recentBankChanges"] as List<Map<String, Any?>>
            changes.map { it["delta"] } shouldBe listOf(100.0, 10.0)
        }

        "marks a fatal collection failure without discarding the last complete money snapshot" {
            var now = 1_000L
            val service = BankAuditService(TestBankAuditConfig()) { now }
            service.accept(read(account("a", bank = 100.0)))
            now = 2_000L
            service.recordFailure(IllegalStateException("database unavailable"))

            val points = service.failureMetricPoints()

            points.first { it.name == "arc_bank_collection_success" }.value shouldBeExactly 0.0
            points.first { it.name == "arc_bank_snapshot_complete" }.value shouldBeExactly 0.0
            points.first { it.name == "arc_bank_snapshot_timestamp_seconds" }.value shouldBeExactly 1.0
            points.first { it.name == "arc_bank_money_currency" && it.tags["component"] == "bank_supply" }.value shouldBeExactly 100.0
        }
    }) {
    companion object {
        private fun account(
            id: String,
            wallet: Double = 0.0,
            bank: Double = 0.0,
            pending: Double = 0.0,
        ) = BankAuditAccount(
            playerId = id,
            player = if (id == "a") "Alice" else "Bob",
            walletBalance = wallet,
            bankBalance = bank,
            pendingInterest = pending,
        )

        private fun read(vararg accounts: BankAuditAccount) =
            BankAuditReadResult(
                discoveredAccounts = accounts.size,
                accounts = accounts.toList(),
            )
    }
}
