package ru.arc.hooks.elitemobs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.config.ConfigManager
import ru.arc.core.ScheduledTask
import java.nio.file.Path

class EMWormholesLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    @AfterEach
    fun clearConfigCache() {
        ConfigManager.clear()
    }

    @Test
    fun `init replaces only this instance task and close is idempotent`() {
        val first = FakeScheduledTask()
        val second = FakeScheduledTask()
        val tasks = ArrayDeque(listOf(first, second))
        val wormholes =
            EMWormholes(
                config = config(),
                scheduleWormholes = { _, _ -> tasks.removeFirst() },
            )

        wormholes.init()
        wormholes.init()

        first.isCancelled.shouldBeTrue()
        second.isCancelled.shouldBeFalse()

        wormholes.close()
        wormholes.close()

        second.isCancelled.shouldBeTrue()
        shouldThrow<IllegalStateException> { wormholes.init() }
    }

    @Test
    fun `different instances do not cancel each others tasks`() {
        val firstTask = FakeScheduledTask()
        val secondTask = FakeScheduledTask()
        val first =
            EMWormholes(
                config = config(),
                scheduleWormholes = { _, _ -> firstTask },
            )
        val second =
            EMWormholes(
                config = config(),
                scheduleWormholes = { _, _ -> secondTask },
            )

        first.init()
        second.init()
        first.close()

        firstTask.isCancelled.shouldBeTrue()
        secondTask.isCancelled.shouldBeFalse()
        second.close()
    }

    @Test
    fun `dedicated wormhole config owns the render period`() {
        val mainConfig = config().also { it.setInt("wormholes.period-ticks", 2) }
        val visualConfig = ConfigManager.of(tempDir, "elitemobs-wormholes.yml").also {
            it.setInt("wormholes.period-ticks", 7)
        }
        var scheduledPeriod = 0L
        val wormholes =
            EMWormholes(
                config = mainConfig,
                wormholeConfig = visualConfig,
                scheduleWormholes = { period, _ ->
                    scheduledPeriod = period
                    FakeScheduledTask()
                },
            )

        wormholes.init()

        scheduledPeriod shouldBe 7L
        wormholes.close()
    }

    private fun config() =
        ConfigManager.of(tempDir, "elitemobs.yml").also {
            it.setInt("wormholes.period-ticks", 2)
        }

    private class FakeScheduledTask : ScheduledTask {
        override val id: Int = 1
        override var isCancelled: Boolean = false
            private set

        override fun cancel() {
            isCancelled = true
        }
    }
}
