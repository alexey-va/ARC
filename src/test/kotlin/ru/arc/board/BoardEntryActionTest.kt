package ru.arc.board

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.KotestTestBase
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoardEntryActionTest :
    KotestTestBase({
        fun entry(ownerUuid: UUID = UUID.randomUUID()) =
            BoardEntryData(
                entryUuid = UUID.randomUUID(),
                playerUuid = ownerUuid,
                playerName = "Owner",
                type = BoardEntryType.SELL,
                text = "text",
                title = "title",
                icon = ItemIcon.of(Material.DIAMOND, 0),
            )

        describe("board entry actions") {
            it("tryRate returns semantic results and switches rating atomically") {
                val entry = entry()
                val player = server.addPlayer("Rater")

                entry.tryRate(player, 1) shouldBe BoardActionResult.APPLIED
                entry.tryRate(player, 1) shouldBe BoardActionResult.ALREADY_APPLIED
                entry.tryRate(player, -1) shouldBe BoardActionResult.APPLIED

                entry.positiveRatings.isEmpty().shouldBeTrue()
                entry.negativeRatings.shouldContainExactly("Rater")
            }

            it("tryRate rejects rating an own entry without permission") {
                val owner = server.addPlayer("Owner")
                val entry = entry(owner.uniqueId)

                entry.tryRate(owner, 1) shouldBe BoardActionResult.NOT_ALLOWED

                entry.positiveRatings.isEmpty().shouldBeTrue()
                entry.negativeRatings.isEmpty().shouldBeTrue()
            }

            it("tryReport applies a report only once") {
                val entry = entry()
                val player = server.addPlayer("Reporter")

                entry.tryReport(player) shouldBe BoardActionResult.APPLIED
                entry.tryReport(player) shouldBe BoardActionResult.ALREADY_APPLIED

                entry.reports.shouldContainExactly("Reporter")
            }

            it("tryReport rejects an own entry without permission") {
                val owner = server.addPlayer("Owner")
                val entry = entry(owner.uniqueId)

                entry.tryReport(owner) shouldBe BoardActionResult.NOT_ALLOWED

                entry.reports.isEmpty().shouldBeTrue()
            }

            it("tryRate rejects unsupported rating values without mutation") {
                val entry = entry()
                val player = server.addPlayer("Rater")

                shouldThrow<IllegalArgumentException> {
                    entry.tryRate(player, 0)
                }

                entry.positiveRatings.isEmpty().shouldBeTrue()
                entry.negativeRatings.isEmpty().shouldBeTrue()
            }

            it("concurrent opposite ratings preserve one-rating invariant") {
                val entry = entry()
                val start = CountDownLatch(1)
                val finished = CountDownLatch(200)
                val executor = Executors.newFixedThreadPool(8)
                try {
                    repeat(200) { index ->
                        executor.execute {
                            start.await()
                            entry.tryRate("Rater", if (index % 2 == 0) 1 else -1)
                            finished.countDown()
                        }
                    }
                    start.countDown()

                    finished.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    (
                        entry.positiveRatings.contains("Rater") xor
                            entry.negativeRatings.contains("Rater")
                    ).shouldBeTrue()
                } finally {
                    executor.shutdownNow()
                }
            }

            it("concurrent duplicate reports remain idempotent") {
                val entry = entry()
                val start = CountDownLatch(1)
                val finished = CountDownLatch(100)
                val executor = Executors.newFixedThreadPool(8)
                try {
                    repeat(100) {
                        executor.execute {
                            start.await()
                            entry.tryReport("Reporter")
                            finished.countDown()
                        }
                    }
                    start.countDown()

                    finished.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    entry.reports.shouldContainExactly("Reporter")
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    })
