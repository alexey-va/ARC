package ru.arc.util

import com.destroystokyo.paper.ParticleBuilder
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler

class ParticleManagerTest {
    private lateinit var scheduler: TestTaskScheduler

    @BeforeEach
    fun setUp() {
        scheduler = TestTaskScheduler()
        Tasks.install(scheduler)
        ParticleManager.stopTasks()
    }

    @AfterEach
    fun tearDown() {
        ParticleManager.stopTasks()
        Tasks.reset()
    }

    @Test
    fun `setup is idempotent and installs one synchronous timer`() {
        ParticleManager.setupParticleManager()
        ParticleManager.setupParticleManager()

        assertEquals(1, scheduler.timerCount())
    }

    @Test
    fun `queued builders are spawned by the timer`() {
        val builder = mockk<ParticleBuilder>(relaxed = true)
        ParticleManager.setupParticleManager()
        ParticleManager.queue(builder)

        scheduler.tick()

        verify(exactly = 1) { builder.spawn() }
    }

    @Test
    fun `queue is limited per tick without dropping remaining builders`() {
        val builder = mockk<ParticleBuilder>(relaxed = true)
        ParticleManager.setupParticleManager()
        repeat(205) { ParticleManager.queue(builder) }

        scheduler.tick()
        verify(exactly = 200) { builder.spawn() }

        clearMocks(builder)
        scheduler.tick()
        verify(exactly = 5) { builder.spawn() }
    }

    @Test
    fun `shutdown cancels timer and discards queued builders`() {
        val builder = mockk<ParticleBuilder>(relaxed = true)
        ParticleManager.setupParticleManager()
        ParticleManager.queue(builder)

        ParticleManager.stopTasks()
        ParticleManager.setupParticleManager()
        scheduler.tick()

        verify(exactly = 0) { builder.spawn() }
        assertEquals(1, scheduler.timerCount())
    }
}
