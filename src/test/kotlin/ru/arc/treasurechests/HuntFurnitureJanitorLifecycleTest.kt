package ru.arc.treasurechests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ru.arc.TestBase
import ru.arc.core.TestTaskScheduler

class HuntFurnitureJanitorLifecycleTest : TestBase() {
    @Test
    fun `shutdown cancels pending startup cleanup`() {
        val scheduler = TestTaskScheduler()

        HuntFurnitureJanitor.init(scheduler)
        assertEquals(1, scheduler.pendingCount())

        HuntFurnitureJanitor.shutdown()

        assertEquals(0, scheduler.pendingCount())
        assertEquals(0, scheduler.timerCount())
    }
}
