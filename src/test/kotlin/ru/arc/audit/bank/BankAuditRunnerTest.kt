package ru.arc.audit.bank

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler

class BankAuditRunnerTest :
    StringSpec({
        "starts once repeats on policy and stops cleanly" {
            val scheduler = TestTaskScheduler()
            var samples = 0
            val runner =
                BankAuditRunner(
                    scheduler = scheduler,
                    initialDelaySeconds = 2,
                    sampleIntervalSeconds = 5,
                ) {
                    samples++
                }

            runner.start()
            runner.start()
            scheduler.timerCount() shouldBe 1
            scheduler.advanceMs(1_999)
            samples shouldBe 0
            scheduler.advanceMs(1)
            samples shouldBe 1
            scheduler.advanceMs(5_000)
            samples shouldBe 2

            runner.close()
            scheduler.advanceMs(10_000)
            samples shouldBe 2
            scheduler.timerCount() shouldBe 0
        }
    })
