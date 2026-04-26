package ru.arc.core

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TaskDslTest :
    DescribeSpec({

        describe("Duration extensions") {
            it("should convert Int to ticks") {
                20.ticks.inWholeMilliseconds shouldBe 1000L // 20 ticks = 1 second
                1.ticks.inWholeMilliseconds shouldBe 50L
            }

            it("should convert Long to ticks") {
                100L.ticks.inWholeMilliseconds shouldBe 5000L
            }

            it("should convert Duration to ticks") {
                1.seconds.inWholeTicks shouldBe 20L
                2.seconds.inWholeTicks shouldBe 40L
            }
        }

        describe("TestTaskScheduler") {
            it("should execute immediate tasks") {
                val scheduler = TestTaskScheduler()
                var executed = false

                scheduler.sync { executed = true }
                executed shouldBe false

                scheduler.executeImmediate()
                executed shouldBe true
            }

            it("should execute delayed tasks after tick") {
                val scheduler = TestTaskScheduler()
                var executed = false

                scheduler.delayed(10.ticks) { executed = true }
                executed shouldBe false

                scheduler.tick(9)
                executed shouldBe false

                scheduler.tick(1)
                executed shouldBe true
            }

            it("should execute repeating tasks") {
                val scheduler = TestTaskScheduler()
                var count = 0

                scheduler.repeating(5.ticks) { count++ }

                scheduler.tick(5)
                count shouldBe 1

                scheduler.tick(5)
                count shouldBe 2

                scheduler.tick(5)
                count shouldBe 3
            }

            it("should cancel tasks") {
                val scheduler = TestTaskScheduler()
                var count = 0

                val task = scheduler.repeating(5.ticks) { count++ }

                scheduler.tick(5)
                count shouldBe 1

                task.cancel()

                scheduler.tick(5)
                count shouldBe 1 // Should not increase
            }

            it("should track pending task count") {
                val scheduler = TestTaskScheduler()

                scheduler.delayed(10.ticks) {}
                scheduler.delayed(20.ticks) {}

                scheduler.pendingCount() shouldBe 2

                scheduler.tick(10)
                scheduler.pendingCount() shouldBe 1

                scheduler.tick(10)
                scheduler.pendingCount() shouldBe 0
            }

            it("should track timer task count") {
                val scheduler = TestTaskScheduler()

                val task1 = scheduler.repeating(5.ticks) {}
                scheduler.repeating(10.ticks) {}

                scheduler.timerCount() shouldBe 2

                task1.cancel()
                scheduler.timerCount() shouldBe 1
            }
        }

        describe("TaskContext") {
            it("should allow cancellation from within task") {
                val scheduler = TestTaskScheduler()
                var count = 0

                scheduler.repeating(5.ticks) {
                    count++
                    if (count >= 3) cancel()
                }

                scheduler.tick(25) // Would run 5 times without cancel
                count shouldBe 3
            }

            it("should provide task ID") {
                val scheduler = TestTaskScheduler()
                var capturedId: Int? = null

                scheduler.sync { capturedId = taskId }
                scheduler.executeImmediate()

                capturedId shouldBe 1
            }

            it("should report cancellation status") {
                val scheduler = TestTaskScheduler()
                var wasCancelled: Boolean? = null

                val task =
                    scheduler.repeating(5.ticks) {
                        wasCancelled = isCancelled
                    }

                scheduler.tick(5)
                wasCancelled shouldBe false

                task.cancel()
                // After cancel, task won't run again, so we check the task itself
                task.isCancelled shouldBe true
            }
        }

        describe("repeat function") {
            it("should run limited number of times") {
                val scheduler = TestTaskScheduler()
                var count = 0
                val iterations = mutableListOf<Int>()

                scheduler.repeat(times = 3, period = 5.ticks) { iteration ->
                    count++
                    iterations.add(iteration)
                }

                scheduler.tick(50) // More than enough
                count shouldBe 3
                iterations shouldBe listOf(0, 1, 2)
            }
        }

        describe("repeatWhile function") {
            it("should run while condition is true") {
                val scheduler = TestTaskScheduler()
                var count = 0
                var shouldContinue = true

                scheduler.repeatWhile(
                    period = 5.ticks,
                    condition = { shouldContinue },
                ) { count++ }

                scheduler.tick(10)
                count shouldBe 2

                shouldContinue = false
                scheduler.tick(10)
                count shouldBe 2 // Should not increase
            }
        }

        describe("repeatUntil function") {
            it("should run until condition becomes true") {
                val scheduler = TestTaskScheduler()
                var count = 0
                var shouldStop = false

                scheduler.repeatUntil(
                    period = 5.ticks,
                    condition = { shouldStop },
                ) { count++ }

                scheduler.tick(10)
                count shouldBe 2

                shouldStop = true
                scheduler.tick(10)
                count shouldBe 2
            }
        }

        describe("TaskBuilder DSL") {
            it("should build simple delayed task") {
                val scheduler = TestTaskScheduler()
                var executed = false

                task(scheduler) {
                    delay(10.ticks)
                }.run { executed = true }

                scheduler.tick(9)
                executed shouldBe false

                scheduler.tick(1)
                executed shouldBe true
            }

            it("should build repeating task") {
                val scheduler = TestTaskScheduler()
                var count = 0

                task(scheduler) {
                    delay(5.ticks)
                    every(10.ticks)
                }.run { count++ }

                scheduler.tick(5)
                count shouldBe 1

                scheduler.tick(10)
                count shouldBe 2
            }

            it("should build limited repeating task") {
                val scheduler = TestTaskScheduler()
                var count = 0

                task(scheduler) {
                    every(5.ticks)
                    times(3)
                }.run { count++ }

                scheduler.tick(50)
                count shouldBe 3
            }

            it("should build conditional repeating task") {
                val scheduler = TestTaskScheduler()
                var count = 0
                var shouldRun = true

                task(scheduler) {
                    every(5.ticks)
                    whileTrue { shouldRun }
                }.run { count++ }

                scheduler.tick(15)
                count shouldBe 3

                shouldRun = false
                scheduler.tick(10)
                count shouldBe 3
            }

            it("should call onComplete callback") {
                val scheduler = TestTaskScheduler()
                var completed = false

                task(scheduler) {
                    every(5.ticks)
                    times(2)
                    onComplete { completed = true }
                }.run {}

                scheduler.tick(10)
                completed shouldBe true
            }

            it("should call onCancel callback") {
                val scheduler = TestTaskScheduler()
                var cancelled = false

                val scheduledTask =
                    task(scheduler) {
                        every(5.ticks)
                        onCancel { cancelled = true }
                    }.run {}

                scheduledTask.cancel()
                cancelled shouldBe true
            }
        }

        describe("Global Tasks object") {
            it("should use custom scheduler with withScheduler") {
                val testScheduler = TestTaskScheduler()
                var executed = false

                Tasks.withScheduler(testScheduler) {
                    sync { executed = true }
                }

                testScheduler.executeImmediate()
                executed shouldBe true
            }
        }

        describe("TaskGroup") {
            it("should manage multiple tasks") {
                val scheduler = TestTaskScheduler()
                val group = TaskGroup(scheduler)
                var count = 0

                group.sync { count++ }
                group.delayed(10.ticks) { count++ }
                group.repeating(period = 5.ticks, delay = 5.ticks) { count++ }

                scheduler.executeImmediate()
                count shouldBe 1 // sync executed

                scheduler.tick(5)
                count shouldBe 2 // first repeating

                scheduler.tick(5)
                count shouldBe 4 // delayed + second repeating
            }

            it("should cancel all tasks at once") {
                val scheduler = TestTaskScheduler()
                val group = TaskGroup(scheduler)
                var count = 0

                group.repeating(5.ticks) { count++ }
                group.repeating(5.ticks) { count++ }

                scheduler.tick(5)
                count shouldBe 2

                group.cancelAll()

                scheduler.tick(5)
                count shouldBe 2 // Should not increase
            }

            it("should track active count") {
                val scheduler = TestTaskScheduler()
                val group = TaskGroup(scheduler)

                val task1 = group.repeating(5.ticks) {}
                group.repeating(5.ticks) {}

                group.activeCount() shouldBe 2

                task1.cancel()
                group.activeCount() shouldBe 1
            }
        }

        describe("countdown") {
            it("should count down from start to end") {
                val scheduler = TestTaskScheduler()
                val counts = mutableListOf<Int>()
                var completed = false

                scheduler.countdown(
                    from = 3,
                    to = 0,
                    period = 5.ticks,
                    onTick = { counts.add(it) },
                    onComplete = { completed = true },
                )

                scheduler.tick(20)

                counts shouldBe listOf(3, 2, 1, 0)
                completed shouldBe true
            }

            it("should count up when from < to") {
                val scheduler = TestTaskScheduler()
                val counts = mutableListOf<Int>()

                scheduler.countdown(
                    from = 0,
                    to = 3,
                    period = 5.ticks,
                    onTick = { counts.add(it) },
                )

                scheduler.tick(20)

                counts shouldBe listOf(0, 1, 2, 3)
            }
        }

        describe("animate") {
            it("should run animation frames") {
                val scheduler = TestTaskScheduler()
                val frames = mutableListOf<Pair<Int, Float>>()
                var completed = false

                scheduler.animate(
                    frames = 5,
                    period = 2.ticks,
                    onFrame = { frame, progress -> frames.add(frame to progress) },
                    onComplete = { completed = true },
                )

                scheduler.tick(10)

                frames.size shouldBe 5
                frames[0] shouldBe (0 to 0f)
                frames[4] shouldBe (4 to 1f)
                completed shouldBe true
            }
        }

        describe("Debouncer") {
            it("should only execute after delay with no new calls") {
                val scheduler = TestTaskScheduler()
                val debouncer = Debouncer(scheduler, 10.ticks)
                var executed = 0

                debouncer.call { executed++ }
                scheduler.tick(5)
                executed shouldBe 0

                debouncer.call { executed++ } // Reset timer
                scheduler.tick(5)
                executed shouldBe 0

                scheduler.tick(5) // Now 10 ticks since last call
                executed shouldBe 1
            }

            it("should cancel pending action") {
                val scheduler = TestTaskScheduler()
                val debouncer = Debouncer(scheduler, 10.ticks)
                var executed = false

                debouncer.call { executed = true }
                debouncer.cancel()
                scheduler.tick(20)

                executed shouldBe false
            }
        }

        describe("TaskChain") {
            it("should execute steps in sequence") {
                val scheduler = TestTaskScheduler()
                val order = mutableListOf<String>()

                chain(scheduler) {
                    sync { order.add("step1") }
                    sync { order.add("step2") }
                    sync { order.add("step3") }
                }.execute()

                scheduler.executeImmediate()
                order shouldBe listOf("step1")

                scheduler.executeImmediate()
                order shouldBe listOf("step1", "step2")

                scheduler.executeImmediate()
                order shouldBe listOf("step1", "step2", "step3")
            }

            it("should handle delays between steps") {
                val scheduler = TestTaskScheduler()
                val order = mutableListOf<String>()

                chain(scheduler) {
                    sync { order.add("before") }
                    delay(10.ticks)
                    sync { order.add("after") }
                }.execute()

                scheduler.executeImmediate()
                order shouldBe listOf("before")

                scheduler.tick(9)
                order shouldBe listOf("before")

                scheduler.tick(1)
                scheduler.executeImmediate()
                order shouldBe listOf("before", "after")
            }

            it("should call onComplete when finished") {
                val scheduler = TestTaskScheduler()
                var completed = false

                chain(scheduler) {
                    sync {}
                    onComplete { completed = true }
                }.execute()

                scheduler.executeImmediate()
                scheduler.executeImmediate() // Process completion

                completed shouldBe true
            }

            it("should handle conditional branches") {
                val scheduler = TestTaskScheduler()
                val order = mutableListOf<String>()
                var condition = true

                chain(scheduler) {
                    sync { order.add("start") }
                    branch(
                        condition = { condition },
                        ifTrue = {
                            sync { order.add("true-branch") }
                        },
                        ifFalse = {
                            sync { order.add("false-branch") }
                        },
                    )
                    sync { order.add("end") }
                }.execute()

                // Execute all steps
                repeat(10) { scheduler.executeImmediate() }

                order shouldBe listOf("start", "true-branch", "end")
            }
        }

        describe("RetryStrategy") {
            it("Fixed should return constant delay") {
                val strategy = RetryStrategy.Fixed(5.seconds)
                strategy.getDelay(0) shouldBe 5.seconds
                strategy.getDelay(1) shouldBe 5.seconds
                strategy.getDelay(5) shouldBe 5.seconds
            }

            it("Exponential should increase delay") {
                val strategy = RetryStrategy.Exponential(1.seconds, multiplier = 2.0)
                strategy.getDelay(0) shouldBe 1.seconds
                strategy.getDelay(1) shouldBe 2.seconds
                strategy.getDelay(2) shouldBe 4.seconds
            }

            it("Exponential should respect max delay") {
                val strategy = RetryStrategy.Exponential(1.seconds, multiplier = 10.0, maxDelay = 5.seconds)
                strategy.getDelay(0) shouldBe 1.seconds
                strategy.getDelay(1) shouldBe 5.seconds // Would be 10s but capped
                strategy.getDelay(2) shouldBe 5.seconds // Would be 100s but capped
            }

            it("Linear should increase by increment") {
                val strategy = RetryStrategy.Linear(1.seconds, increment = 500.milliseconds)
                strategy.getDelay(0) shouldBe 1.seconds
                strategy.getDelay(1) shouldBe 1500.milliseconds
                strategy.getDelay(2) shouldBe 2.seconds
            }
        }

        describe("ProgressTask") {
            it("should track progress") {
                val scheduler = TestTaskScheduler()
                val progress = ProgressTask(scheduler, totalSteps = 5)
                val progressValues = mutableListOf<Float>()

                progress.onProgress { _, _, percent ->
                    progressValues.add(percent)
                }

                progress.advance()
                progress.advance()
                progress.advance()

                progressValues shouldBe listOf(0.2f, 0.4f, 0.6f)
                progress.getProgress() shouldBe 0.6f
            }

            it("should call onComplete when finished") {
                val scheduler = TestTaskScheduler()
                val progress = ProgressTask(scheduler, totalSteps = 2)
                var completed = false

                progress.onComplete { completed = true }

                progress.advance()
                completed shouldBe false

                progress.advance()
                completed shouldBe true
                progress.isComplete() shouldBe true
            }

            it("should allow setting progress directly") {
                val scheduler = TestTaskScheduler()
                val progress = ProgressTask(scheduler, totalSteps = 10)

                progress.setProgress(5)
                progress.getProgress() shouldBe 0.5f

                progress.setProgress(10)
                progress.isComplete() shouldBe true
            }
        }
    })
