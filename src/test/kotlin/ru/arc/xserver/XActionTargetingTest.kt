package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler

class XActionTargetingTest :
    FreeSpec({
        lateinit var scheduler: TrackingTaskScheduler

        beforeTest {
            scheduler = TrackingTaskScheduler()
            Tasks.install(scheduler)
        }

        afterTest {
            Tasks.reset()
        }

        "server targeting" - {
            "null means every server" {
                targetsCurrentServer(null, null) shouldBe true
                targetsCurrentServer(null, "spawn") shouldBe true
            }

            "an empty set means no servers" {
                targetsCurrentServer(emptySet(), "spawn") shouldBe false
            }

            "matches names without depending on case or surrounding whitespace" {
                targetsCurrentServer(setOf(" Survival "), "survival") shouldBe true
                targetsCurrentServer(setOf("SURVIVAL"), "spawn") shouldBe false
            }

            "all is accepted defensively on the wire" {
                targetsCurrentServer(setOf("ALL"), null) shouldBe true
            }

            "a specific target never runs when the current server is unknown" {
                targetsCurrentServer(setOf("spawn"), null) shouldBe false
            }
        }

        "action scheduling" - {
            "legacy async flag cannot move Bukkit actions off the main scheduler" {
                val action =
                    RecordingAction().apply {
                        afterTimestamp = System.currentTimeMillis() + 1_000
                        async = true
                    }

                action.run()

                scheduler.syncDelayedCalls shouldBe 1
                scheduler.asyncDelayedCalls shouldBe 0
            }
        }

        "payment validation" - {
            "accepts only finite positive amounts" {
                isValidPaymentAmount(10.0) shouldBe true
                isValidPaymentAmount(0.0) shouldBe false
                isValidPaymentAmount(-1.0) shouldBe false
                isValidPaymentAmount(Double.NaN) shouldBe false
                isValidPaymentAmount(Double.POSITIVE_INFINITY) shouldBe false
            }
        }
    })

private class RecordingAction : XAction() {
    override fun runInternal() = Unit
}

private class TrackingTaskScheduler(
    private val delegate: TestTaskScheduler = TestTaskScheduler(),
) : TaskScheduler by delegate {
    var syncDelayedCalls = 0
        private set
    var asyncDelayedCalls = 0
        private set

    override fun runLater(
        delayTicks: Long,
        task: Runnable,
    ): ScheduledTask {
        syncDelayedCalls++
        return delegate.runLater(delayTicks, task)
    }

    override fun runLaterAsync(
        delayTicks: Long,
        task: Runnable,
    ): ScheduledTask {
        asyncDelayedCalls++
        return delegate.runLaterAsync(delayTicks, task)
    }
}
