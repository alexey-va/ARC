@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class TaskSchedulerTest {

    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
    }

    @Nested
    @DisplayName("runAsync / runSync")
    inner class ImmediateTaskTests {

        @Test
        fun `runAsync adds pending task`() {
            val counter = AtomicInteger(0)
            scheduler.runAsync { counter.incrementAndGet() }

            assertEquals(1, scheduler.pendingCount())
            assertEquals(0, counter.get())
        }

        @Test
        fun `executeImmediate runs immediate tasks`() {
            val counter = AtomicInteger(0)
            scheduler.runAsync { counter.incrementAndGet() }
            scheduler.runSync { counter.incrementAndGet() }

            scheduler.executeImmediate()

            assertEquals(2, counter.get())
            assertEquals(0, scheduler.pendingCount())
        }

        @Test
        fun `cancelled task is not executed`() {
            val counter = AtomicInteger(0)
            val task = scheduler.runAsync { counter.incrementAndGet() }

            task.cancel()
            scheduler.executeAll()

            assertTrue(task.isCancelled)
            assertEquals(0, counter.get())
        }
    }

    @Nested
    @DisplayName("runLater")
    inner class DelayedTaskTests {

        @Test
        fun `runLater schedules for future tick`() {
            val counter = AtomicInteger(0)
            scheduler.runLater(10) { counter.incrementAndGet() }

            scheduler.tick(5)
            assertEquals(0, counter.get())

            scheduler.tick(5)
            assertEquals(1, counter.get())
        }

        @Test
        fun `runLaterAsync works the same`() {
            val counter = AtomicInteger(0)
            scheduler.runLaterAsync(20) { counter.incrementAndGet() }

            scheduler.tick(19)
            assertEquals(0, counter.get())

            scheduler.tick(1)
            assertEquals(1, counter.get())
        }
    }

    @Nested
    @DisplayName("runTimer")
    inner class TimerTaskTests {

        @Test
        fun `runTimer executes at interval`() {
            val counter = AtomicInteger(0)
            scheduler.runTimer(5, 10) { counter.incrementAndGet() }

            scheduler.tick(4)
            assertEquals(0, counter.get())

            scheduler.tick(1) // tick 5
            assertEquals(1, counter.get())

            scheduler.tick(10) // tick 15
            assertEquals(2, counter.get())

            scheduler.tick(10) // tick 25
            assertEquals(3, counter.get())
        }

        @Test
        fun `cancelled timer stops execution`() {
            val counter = AtomicInteger(0)
            val task = scheduler.runTimerAsync(5, 10) { counter.incrementAndGet() }

            scheduler.tick(5)
            assertEquals(1, counter.get())

            task.cancel()

            scheduler.tick(100)
            assertEquals(1, counter.get())
        }

        @Test
        fun `timerCount returns active timers`() {
            scheduler.runTimer(1, 1) {}
            scheduler.runTimer(1, 1) {}

            assertEquals(2, scheduler.timerCount())
        }
    }

    @Nested
    @DisplayName("cancelAll")
    inner class CancelAllTests {

        @Test
        fun `cancelAll clears all tasks`() {
            scheduler.runAsync {}
            scheduler.runLater(10) {}
            scheduler.runTimer(1, 1) {}

            scheduler.cancelAll()

            assertEquals(0, scheduler.pendingCount())
            assertEquals(0, scheduler.timerCount())
        }
    }

    @Nested
    @DisplayName("Task IDs")
    inner class TaskIdTests {

        @Test
        fun `each task has unique id`() {
            val task1 = scheduler.runAsync {}
            val task2 = scheduler.runAsync {}
            val task3 = scheduler.runAsync {}

            assertNotEquals(task1.id, task2.id)
            assertNotEquals(task2.id, task3.id)
            assertNotEquals(task1.id, task3.id)
        }
    }
}


