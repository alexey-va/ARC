package ru.arc.sync

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.sync.base.Sync

class SyncManagerLifecycleTest {
    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
        SyncManager.shutdown(save = false)
    }

    @AfterEach
    fun tearDown() {
        SyncManager.shutdown(save = false)
        Tasks.reset()
    }

    @Test
    fun `shutdown cancels periodic save and clears registered syncs`() {
        val sync = FakeSync()
        SyncManager.registerSync(FakeSync::class.java, sync)
        SyncManager.startSaveAllTasks()

        assertEquals(1, scheduler.timerCount())
        assertEquals(1, SyncManager.getSyncs().size)

        SyncManager.shutdown(save = false)

        assertEquals(0, scheduler.timerCount())
        assertEquals(0, SyncManager.getSyncs().size)
        assertEquals(1, sync.shutdownCount)
    }

    @Test
    fun `starting periodic save twice remains idempotent`() {
        SyncManager.startSaveAllTasks()
        SyncManager.startSaveAllTasks()

        assertEquals(1, scheduler.timerCount())
    }

    private class FakeSync : Sync {
        var shutdownCount = 0

        override fun shutdown() {
            shutdownCount++
        }
    }
}
