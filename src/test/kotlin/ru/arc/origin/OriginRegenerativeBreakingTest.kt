package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.core.ScheduledTask
import java.util.UUID

class OriginRegenerativeBreakingTest :
    FreeSpec({
        "a protected Origin break becomes a temporary client illusion" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))

            service.handle(target()) shouldBe true
            runtime.delays shouldContainExactly listOf(1L)
            runtime.shown shouldBe 0

            runtime.runNext()
            runtime.shown shouldBe 1
            runtime.delays shouldContainExactly listOf(100L)
            runtime.restored shouldBe 0

            runtime.runNext()
            runtime.restored shouldBe 1
        }

        "plain protection cancels Origin breaks without creating an illusion" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)

            service.handle(target()) shouldBe false
            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))
            service.handle(target()) shouldBe true
            service.handle(target(worldName = "world")) shouldBe false
            runtime.delays shouldContainExactly emptyList()
        }

        "a block changed after the event is never hidden" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))
            service.handle(target())
            runtime.blockData = "minecraft:dirt"

            runtime.runNext()

            runtime.shown shouldBe 0
            runtime.restored shouldBe 0
            runtime.delays shouldContainExactly emptyList()
        }

        "reload disabling the feature restores visible illusions immediately" {
            val runtime = FakeRuntime()
            val service = OriginBreakProtection(runtime)
            service.apply(OriginBreakProtectionSettings(true, true, "rc_origin_spawn", 100L))
            service.handle(target())
            runtime.runNext()

            service.apply(OriginBreakProtectionSettings(true, false, "rc_origin_spawn", 100L))

            runtime.restored shouldBe 1
            runtime.runAll()
            runtime.restored shouldBe 1
        }
    })

private fun target(worldName: String = "rc_origin_spawn") =
    OriginBreakIllusionTarget(
        playerId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        worldId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
        worldName = worldName,
        x = 10,
        y = 70,
        z = -4,
        originalBlockData = "minecraft:stone",
    )

private class FakeRuntime : OriginBreakIllusionRuntime {
    private val tasks = mutableListOf<FakeTask>()
    var blockData: String? = "minecraft:stone"
    var shown = 0
    var restored = 0
    val delays: List<Long>
        get() = tasks.filterNot(FakeTask::isCancelled).map(FakeTask::delayTicks)

    override fun schedule(delayTicks: Long, action: () -> Unit): ScheduledTask =
        FakeTask(delayTicks, action).also(tasks::add)

    override fun currentBlockData(target: OriginBreakIllusionTarget): String? = blockData

    override fun showBroken(target: OriginBreakIllusionTarget): Boolean {
        shown++
        return true
    }

    override fun restore(target: OriginBreakIllusionTarget) {
        restored++
    }

    fun runNext() {
        val task = tasks.firstOrNull { !it.isCancelled } ?: return
        tasks.remove(task)
        task.run()
    }

    fun runAll() {
        while (tasks.any { !it.isCancelled }) runNext()
    }
}

private class FakeTask(
    val delayTicks: Long,
    private val action: () -> Unit,
) : ScheduledTask {
    override val id: Int = 0
    override var isCancelled: Boolean = false
        private set

    fun run() {
        if (!isCancelled) action()
        isCancelled = true
    }

    override fun cancel() {
        isCancelled = true
    }
}
