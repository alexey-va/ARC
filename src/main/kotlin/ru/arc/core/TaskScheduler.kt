package ru.arc.core

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Abstraction over task scheduling for testability.
 *
 * In production, uses Bukkit scheduler. In tests, can execute
 * tasks immediately or store them for manual triggering.
 */
interface TaskScheduler {
    /**
     * Run a task asynchronously.
     */
    fun runAsync(task: Runnable): ScheduledTask

    /**
     * Run a task on the main thread.
     */
    fun runSync(task: Runnable): ScheduledTask

    /**
     * Run a task later (delay in ticks).
     */
    fun runLater(delay: Long, task: Runnable): ScheduledTask

    /**
     * Run a task later asynchronously (delay in ticks).
     */
    fun runLaterAsync(delay: Long, task: Runnable): ScheduledTask

    /**
     * Run a repeating task (delay and period in ticks).
     */
    fun runTimer(delay: Long, period: Long, task: Runnable): ScheduledTask

    /**
     * Run a repeating async task (delay and period in ticks).
     */
    fun runTimerAsync(delay: Long, period: Long, task: Runnable): ScheduledTask

    /**
     * Cancel all scheduled tasks.
     */
    fun cancelAll()
}

/**
 * Handle to a scheduled task that can be cancelled.
 */
interface ScheduledTask {
    val id: Int
    val isCancelled: Boolean
    fun cancel()
}

/**
 * Production implementation using Bukkit scheduler.
 */
class BukkitTaskScheduler(private val plugin: Plugin) : TaskScheduler {

    private val tasks = CopyOnWriteArrayList<BukkitScheduledTask>()

    override fun runAsync(task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, task)
        return track(bukkitTask)
    }

    override fun runSync(task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTask(plugin, task)
        return track(bukkitTask)
    }

    override fun runLater(delay: Long, task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTaskLater(plugin, task, delay)
        return track(bukkitTask)
    }

    override fun runLaterAsync(delay: Long, task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay)
        return track(bukkitTask)
    }

    override fun runTimer(delay: Long, period: Long, task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period)
        return track(bukkitTask)
    }

    override fun runTimerAsync(delay: Long, period: Long, task: Runnable): ScheduledTask {
        val bukkitTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period)
        return track(bukkitTask)
    }

    override fun cancelAll() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun track(bukkitTask: BukkitTask): ScheduledTask {
        val scheduled = BukkitScheduledTask(bukkitTask)
        tasks.add(scheduled)
        return scheduled
    }

    private class BukkitScheduledTask(private val task: BukkitTask) : ScheduledTask {
        override val id: Int get() = task.taskId
        override val isCancelled: Boolean get() = task.isCancelled
        override fun cancel() = task.cancel()
    }
}

/**
 * Test implementation that stores tasks for manual execution.
 *
 * Usage:
 * ```kotlin
 * val scheduler = TestTaskScheduler()
 * myService.scheduleTask(scheduler)
 *
 * // Execute all pending tasks
 * scheduler.executeAll()
 *
 * // Or advance time and execute timers
 * scheduler.tick(100) // 100 ticks
 * ```
 */
class TestTaskScheduler(
    private val executor: Executor = Executor { it.run() }
) : TaskScheduler {

    private val idCounter = AtomicInteger(0)
    private val pendingTasks = CopyOnWriteArrayList<TestScheduledTask>()
    private val timerTasks = CopyOnWriteArrayList<TimerTask>()
    private var currentTick = 0L

    override fun runAsync(task: Runnable): ScheduledTask {
        return scheduleImmediate(task)
    }

    override fun runSync(task: Runnable): ScheduledTask {
        return scheduleImmediate(task)
    }

    override fun runLater(delay: Long, task: Runnable): ScheduledTask {
        return scheduleDelayed(delay, task)
    }

    override fun runLaterAsync(delay: Long, task: Runnable): ScheduledTask {
        return scheduleDelayed(delay, task)
    }

    override fun runTimer(delay: Long, period: Long, task: Runnable): ScheduledTask {
        return scheduleTimer(delay, period, task)
    }

    override fun runTimerAsync(delay: Long, period: Long, task: Runnable): ScheduledTask {
        return scheduleTimer(delay, period, task)
    }

    override fun cancelAll() {
        pendingTasks.forEach { it.cancel() }
        timerTasks.forEach { it.scheduledTask.cancel() }
        pendingTasks.clear()
        timerTasks.clear()
    }

    /**
     * Execute all pending one-time tasks.
     */
    fun executeAll() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAt <= currentTick }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    /**
     * Execute all immediate tasks (no delay).
     */
    fun executeImmediate() {
        val toExecute = pendingTasks.filter { !it.isCancelled && it.executeAt == 0L }
        toExecute.forEach { task ->
            executor.execute(task.runnable)
            pendingTasks.remove(task)
        }
    }

    /**
     * Advance time and execute tasks that should run.
     */
    fun tick(ticks: Long = 1) {
        repeat(ticks.toInt()) {
            currentTick++
            executeAll()
            executeTimers()
        }
    }

    /**
     * Get number of pending tasks.
     */
    fun pendingCount(): Int = pendingTasks.count { !it.isCancelled }

    /**
     * Get number of active timer tasks.
     */
    fun timerCount(): Int = timerTasks.count { !it.scheduledTask.isCancelled }

    private fun scheduleImmediate(task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, 0L)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleDelayed(delay: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTick + delay)
        pendingTasks.add(scheduled)
        return scheduled
    }

    private fun scheduleTimer(delay: Long, period: Long, task: Runnable): ScheduledTask {
        val scheduled = TestScheduledTask(idCounter.incrementAndGet(), task, currentTick + delay)
        val timer = TimerTask(scheduled, period, currentTick + delay)
        timerTasks.add(timer)
        return scheduled
    }

    private fun executeTimers() {
        timerTasks.filter { !it.scheduledTask.isCancelled && it.nextExecution <= currentTick }
            .forEach { timer ->
                executor.execute(timer.scheduledTask.runnable)
                timer.nextExecution = currentTick + timer.period
            }
    }

    private data class TimerTask(
        val scheduledTask: TestScheduledTask,
        val period: Long,
        var nextExecution: Long
    )

    private class TestScheduledTask(
        override val id: Int,
        val runnable: Runnable,
        val executeAt: Long
    ) : ScheduledTask {
        private var cancelled = false
        override val isCancelled: Boolean get() = cancelled
        override fun cancel() {
            cancelled = true
        }
    }
}

