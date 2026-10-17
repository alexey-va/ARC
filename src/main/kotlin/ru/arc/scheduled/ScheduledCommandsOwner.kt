package ru.arc.scheduled

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TaskScheduler
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * One module generation over ARC's existing worker and native budget. YAML, validation and
 * due-slot evaluation run serially on the worker; each console command is one native step.
 * A mutation completes only after strict persistence and immutable publication. Shutdown
 * observes accepted worker/native futures without joining them on the owning thread.
 */
internal class ScheduledCommandsOwner(
    private val root: Path,
    private val worker: BoundedAsyncExecutor,
    private val native: BudgetedTaskQueue,
    scheduler: TaskScheduler,
    private val serverName: () -> String?,
    private val dispatcher: CommandDispatcher,
    private val load: () -> ScheduledCommandsConfig = { ScheduledCommandsConfig.load(root) },
    private val reportFailure: (Throwable) -> Unit,
    private val clock: ScheduleClock = SystemScheduleClock,
) {
    private val scope = LifecycleTaskScope(scheduler)
    private val monitor = Any()
    @Volatile private var closed = false
    @Volatile private var published: ScheduledCommandsSnapshot? = null
    private var pending = 0
    private val terminal = CompletableFuture<Void>()
    private var timer: ru.arc.core.ScheduledTask? = null
    private val service = ScheduledCommandsService(
        settingsProvider = { checkNotNull(published) { "Scheduled commands are preparing" } },
        dispatcher = CommandDispatcher { command ->
            admit { native.call({ !closed }) { dispatcher.dispatch(command) } }.whenComplete { _, failure ->
                if (failure != null && !closed) reportFailure(failure)
            }
        },
        serverNameProvider = serverName,
        clock = clock,
        taskScheduler = scheduler,
    )
    private val started: CompletableFuture<Void> = try {
        worker.submit { publish(load()) }.join()
        restartTimer()
        CompletableFuture.completedFuture(null)
    } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }

    @Volatile private var preparation: CompletableFuture<Void> = started

    fun ready(): CompletableFuture<Void> = preparation.copy()
    fun snapshot(): ScheduledCommandsSettings = checkNotNull(published) { "Scheduled commands are preparing" }
    fun commandIds(prefix: String): List<String> = published?.commandIds(prefix) ?: emptyList()

    /** Manual reload owns the native thread; only its IO phase enters the worker. */
    fun reloadMaintenance() {
        afterReady { publish(load()) }.join()
        restartTimer()
        preparation = CompletableFuture.completedFuture(null)
    }

    fun reloadAsync(): CompletableFuture<Void> {
        val result: CompletableFuture<Void> = admit {
            afterReady { publish(load()) } // Invalid candidates retain the previous view and timer.
                .thenCompose { scope.callSync { restartTimer() }.thenApply<Void> { null } }
        }
        preparation = result
        return result.copy()
    }

    fun saveEntryAsync(input: ScheduledCommandDraft): CompletableFuture<ValidationResult> =
        saveSnapshotAsync(input).thenApply { it.validation }

    fun saveSnapshotAsync(input: ScheduledCommandDraft): CompletableFuture<ScheduledEntrySaveResult> {
        val draft = input.copy() // The GUI may keep editing its own draft while persistence waits.
        return afterReady {
            val candidate = load().also { it.snapshot() }
            val validation = ScheduledCommandInputValidator.validateDraft(draft, candidate.commandIds().toSet())
            if (validation is ValidationResult.Error) ScheduledEntrySaveResult(validation, false, null) else {
                val created = candidate.entry(draft.id.trim().lowercase()) == null
                candidate.saveEntry(draft)
                publish(candidate)
                service.clearEntryState(draft.originalId)
                service.clearEntryState(draft.id)
                ScheduledEntrySaveResult(ValidationResult.Ok, created, published!!.entry(draft.id))
            }
        }
    }

    fun deleteEntryAsync(id: String): CompletableFuture<Boolean> = afterReady {
        val normalized = id.trim().lowercase()
        val candidate = load().also { it.snapshot() }
        if (!candidate.deleteEntry(normalized)) false else {
            publish(candidate)
            service.clearEntryState(normalized)
            true
        }
    }

    fun toggleEnabledAsync(id: String): CompletableFuture<Boolean?> = afterReady {
        val normalized = id.trim().lowercase()
        val candidate = load().also { it.snapshot() }
        val entry = candidate.entry(normalized)
        if (entry == null) null else (!entry.enabled).also { enabled ->
            candidate.setEnabled(normalized, enabled)
            publish(candidate)
        }
    }

    fun runNowAsync(id: String): CompletableFuture<Boolean> = admit {
        afterReady { snapshot().entry(id.trim().lowercase()) }.thenCompose { entry ->
            if (entry == null) CompletableFuture.completedFuture(false)
            else native.call({ !closed }) { dispatcher.dispatch(entry.command); true }
        }
    }

    /** Native owner only. Repeated close retains the same accepted-work barrier. */
    fun close(): CompletableFuture<Void> {
        synchronized(monitor) { closed = true }
        timer?.cancel()
        timer = null
        scope.close()
        synchronized(monitor) { if (pending == 0) terminal.complete(null) }
        return terminal.copy()
    }

    private fun publish(candidate: ScheduledCommandsConfig) { published = candidate.snapshot() }

    private fun restartTimer() {
        timer?.cancel()
        timer = null
        if (closed) return
        val settings = snapshot()
        if (!settings.enabled) return
        val period = settings.checkIntervalTicks.coerceAtLeast(20L)
        timer = scope.runTimer(period, period) {
            // The worker owns both due-slot/fire-key updates and configuration mutations.
            val now = clock.now(snapshot().timezone)
            afterReady { service.tick(now) }.whenComplete { _, failure ->
                if (failure != null && !closed) reportFailure(failure)
            }
        }
    }

    private fun <T> afterReady(action: () -> T): CompletableFuture<T> = admit {
        started.thenCompose {
            synchronized(monitor) {
                if (closed) CompletableFuture.failedFuture(RejectedExecutionException("Scheduled commands are stopping"))
                else worker.submit(action)
            }
        }
    }

    /** O(1) accounting; cancellation of an observer cannot retire accepted operations early. */
    private fun <T> admit(operation: () -> CompletableFuture<T>): CompletableFuture<T> = synchronized(monitor) {
        if (closed) return@synchronized CompletableFuture.failedFuture(RejectedExecutionException("Scheduled commands are stopping"))
        pending++
        val future = try { operation() } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        future.whenComplete { _, _ ->
            synchronized(monitor) {
                pending--
                if (closed && pending == 0) terminal.complete(null)
            }
        }
        future.copy()
    }
}

/** Exact committed entry, so a later writer cannot change an HTTP save acknowledgement. */
data class ScheduledEntrySaveResult(
    val validation: ValidationResult,
    val created: Boolean,
    val entry: ScheduledCommandEntry?,
)
