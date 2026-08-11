package ru.arc.audit.autosell

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AutoSellAuditServiceTest :
    StringSpec({
        "summarizes loaded chests without exposing owner or location identity" {
            var now = 1_000_000L
            val service = AutoSellAuditService { now }

            service.accept(
                AutoSellRuntimeSample(
                    pluginVersion = "2.9.0",
                    ownerMustBeOnline = true,
                    chests =
                        listOf(
                            chest(
                                ownerOnline = true,
                                itemsSold = 120,
                                nextInterval = now + 10_000,
                                interval = 30_000,
                                multiplier = 1.25,
                            ),
                            chest(
                                ownerOnline = false,
                                itemsSold = 0,
                                nextInterval = now + 90_000,
                                interval = 60_000,
                                multiplier = 1.0,
                            ),
                            chest(
                                ownerOnline = true,
                                itemsSold = 5,
                                nextInterval = now - 1_000,
                                interval = 30_000,
                                multiplier = 1.25,
                            ),
                        ),
                ),
            )

            val summary = service.summary()
            summary["status"] shouldBe "ready"
            summary["loadedChests"] shouldBe 3
            summary["onlineOwnerChests"] shouldBe 2
            summary["eligibleChests"] shouldBe 2
            summary["chestsWithPriorSales"] shouldBe 2
            summary["lifetimeItemsSold"] shouldBe 125L
            summary["dueWithin60Seconds"] shouldBe 2
            summary["nextDueInSeconds"] shouldBe 0L
            summary["multipliers"] shouldBe mapOf("1" to 1, "1.25" to 2)
            summary["intervalSeconds"] shouldBe mapOf("30" to 2, "60" to 1)
            summary.containsKey("owners") shouldBe false
            summary.containsKey("locations") shouldBe false
        }

        "keeps capture counters current between runtime samples" {
            var now = 2_000L
            val service = AutoSellAuditService { now }
            service.accept(AutoSellRuntimeSample("2.9.0", true, emptyList()))

            now = 3_000L
            service.recordCapture(itemQuantity = 64)
            now = 4_000L
            service.recordCapture(itemQuantity = 0)

            val summary = service.summary()
            summary["capturedPreTransactionsSinceStart"] shouldBe 2L
            summary["capturedItemsSinceStart"] shouldBe 64L
            summary["lastPreTransactionAt"] shouldBe 4_000L
        }

        "reads the exact public AutoSell API through a bounded reflection adapter" {
            val onlineOwner = UUID.randomUUID()
            val offlineOwner = UUID.randomUUID()
            val plugin = FakePlugin(listOf(FakeChest(onlineOwner), FakeChest(offlineOwner)))

            val sample =
                AutoSellReflectionReader.read(
                    plugin = plugin,
                    pluginVersion = "2.9.0",
                    ownerMustBeOnline = true,
                    ownerOnline = { it == onlineOwner },
                )

            sample.chests.size shouldBe 2
            sample.chests.count(AutoSellChestObservation::ownerOnline) shouldBe 1
            sample.chests.sumOf(AutoSellChestObservation::itemsSold) shouldBe 14L
            sample.chests.map(AutoSellChestObservation::multiplier).toSet() shouldBe setOf(1.25)
        }
    }) {
    companion object {
        private fun chest(
            ownerOnline: Boolean,
            itemsSold: Long,
            nextInterval: Long,
            interval: Long,
            multiplier: Double,
        ) = AutoSellChestObservation(ownerOnline, itemsSold, nextInterval, interval, multiplier)
    }

    private class FakePlugin(chests: List<FakeChest>) {
        private val manager = FakeManager(chests)

        fun getManager(): FakeManager = manager
    }

    private class FakeManager(chests: List<FakeChest>) {
        private val loaded = chests.associateBy { Any() }

        fun getLoadedChests(): Map<Any, FakeChest> = loaded
    }

    private class FakeChest(
        private val owner: UUID,
    ) {
        fun getOwner(): UUID = owner

        fun getItemsSold(): Int = 7

        fun getNextInterval(): Long = 10_000L

        fun getInterval(): Long = 30_000L

        fun getMultiplier(): Double = 1.25
    }
}
