package ru.arc.scheduled

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScheduledCommandsOwnerTest : FunSpec({
    test("maintenance startup returns with the durable configuration and native timer attached") {
        MockBukkitTestRuntime.open().use {
            ScheduleOwnerFixture().use { fixture ->
                val owner = fixture.owner(load = {
                    org.bukkit.Bukkit.isPrimaryThread() shouldBe false
                    ScheduledCommandsConfig.load(fixture.root)
                })
                owner.ready().isDone shouldBe true
                owner.ready().join()
                owner.commandIds("e") shouldBe listOf("existing")
                owner.close().isDone shouldBe true
            }
        }
    }

    test("save copies the submitted draft and acknowledges the exact durable published entry") {
        MockBukkitTestRuntime.open().use {
            ScheduleOwnerFixture().use { fixture ->
                val owner = fixture.owner()
                fixture.await(owner.ready())
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                fixture.worker.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                check(entered.await(5, TimeUnit.SECONDS))
                try {
                    val draft = scheduleDraft("new_entry", "say original")
                    val saved = owner.saveSnapshotAsync(draft)
                    draft.command = "say later edit"
                    saved.isDone shouldBe false
                    owner.snapshot().entry("new_entry") shouldBe null
                    val previousIds = owner.commandIds("")
                    previousIds shouldBe listOf("existing")
                    release.countDown()
                    fixture.await(saved)
                    saved.join().created shouldBe true
                    saved.join().entry!!.command shouldBe "say original"
                    owner.snapshot().entry("new_entry")!!.command shouldBe "say original"
                    owner.commandIds("NEW_") shouldBe listOf("new_entry")
                    owner.commandIds("absent") shouldBe emptyList()
                    owner.commandIds("") shouldBe listOf("existing", "new_entry")
                    previousIds shouldBe listOf("existing")
                    Files.readString(fixture.file).contains("say original") shouldBe true
                    Files.readString(fixture.file).contains("say later edit") shouldBe false
                } finally { release.countDown() }
            }
        }
    }

    test("invalid reload and strict persistence failure retain the previous immutable view") {
        MockBukkitTestRuntime.open().use {
            ScheduleOwnerFixture().use { fixture ->
                var failSave = false
                val owner = fixture.owner(load = {
                    ScheduledCommandsConfig.load(fixture.root).also {
                        if (failSave) {
                            Files.delete(fixture.file)
                            Files.createDirectory(fixture.file)
                        }
                    }
                })
                fixture.await(owner.ready())
                val before = owner.snapshot()
                failSave = true
                val save = owner.saveEntryAsync(scheduleDraft("existing", "say changed"))
                fixture.await(save.handle { _, _ -> Unit })
                save.isCompletedExceptionally shouldBe true
                (owner.snapshot() === before) shouldBe true
                owner.snapshot().entry("existing")!!.command shouldBe "say existing"
                failSave = false
                Files.delete(fixture.file)
                Files.writeString(fixture.file, "enabled: true\ncommands:\n  broken:\n    schedule:\n      type: cron\n      expression: invalid\n")
                val reload = owner.reloadAsync()
                fixture.await(reload.handle { _, _ -> Unit })
                reload.isCompletedExceptionally shouldBe true
                (owner.snapshot() === before) shouldBe true
                owner.snapshot().entry("existing")!!.command shouldBe "say existing"
            }
        }
    }

    test("large due set dispatches in native budget and preserves mark-before-dispatch once-only keys") {
        MockBukkitTestRuntime.open().use {
            ScheduleOwnerFixture(entries = 150).use { fixture ->
                val owner = fixture.owner()
                fixture.await(owner.ready())
                fixture.scheduler.tick(20)
                // Enqueue after the due scan: completion proves all 150 native admissions were made.
                fixture.worker.submit {}.get(5, TimeUnit.SECONDS)
                fixture.scheduler.executeImmediate()
                fixture.dispatched.size shouldBe 0
                fixture.scheduler.tick()
                fixture.dispatched.size shouldBe 7
                while (fixture.dispatched.size < 150) fixture.scheduler.tick()
                fixture.dispatched.distinct().size shouldBe 150
                val expected = fixture.dispatched.toList()
                // The injected clock fixes the slot while the native scheduler advances.
                fixture.scheduler.tick(20)
                fixture.worker.submit {}.get(5, TimeUnit.SECONDS)
                fixture.scheduler.executeImmediate()
                fixture.scheduler.tick(30)
                fixture.dispatched shouldBe expected
            }
        }
    }
})

private class ScheduleOwnerFixture(entries: Int = 1) : AutoCloseable {
    val root: Path = Files.createTempDirectory("arc-schedule-owner-")
    val file: Path = Files.createDirectories(root.resolve("modules")).resolve("scheduled-commands.yml")
    val worker = BoundedAsyncExecutor("schedule-owner-test", 1, 256)
    val scheduler = TestTaskScheduler()
    private val nativeScope = LifecycleTaskScope(scheduler)
    private val native = BudgetedTaskQueue(nativeScope, maximumSteps = 7, maximumNanos = Long.MAX_VALUE)
    val dispatched = mutableListOf<String>()
    private var active: ScheduledCommandsOwner? = null

    init {
        Files.writeString(file, buildString {
            append("enabled: true\ncheck-interval: 1s\ncommands:\n")
            repeat(entries) { index ->
                val id = if (entries == 1) "existing" else "entry_${index.toString().padStart(3, '0')}"
                append("  $id:\n    enabled: true\n    command: say $id\n    servers: [all]\n    schedule:\n      type: interval\n      every: 1s\n      run-on-start: true\n")
            }
        })
    }

    fun owner(load: () -> ScheduledCommandsConfig = { ScheduledCommandsConfig.load(root) }): ScheduledCommandsOwner =
        ScheduledCommandsOwner(root, worker, native, scheduler, { "spawn" }, CommandDispatcher {
            org.bukkit.Bukkit.isPrimaryThread() shouldBe true
            dispatched += it
        }, load, { throw AssertionError("Unexpected scheduled-operation failure", it) }, object : ScheduleClock {
            override fun now(zone: java.time.ZoneId) = java.time.ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone)
        }).also { active = it }

    fun await(future: CompletableFuture<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!future.isDone && System.nanoTime() < deadline) {
            scheduler.executeImmediate()
            Thread.sleep(1)
        }
        check(future.isDone) { "Actual schedule completion timed out" }
    }

    override fun close() {
        val retired = active?.close()
        native.close()
        nativeScope.close()
        worker.closeAsync().get(10, TimeUnit.SECONDS)
        if (retired != null) await(retired)
        root.toFile().deleteRecursively()
    }
}

private fun scheduleDraft(id: String, command: String) = ScheduledCommandDraft(
    id, id, true, command, ServerEditorMode.ALL, ScheduleEditorType.INTERVAL, "1s", "MONDAY", false,
)
