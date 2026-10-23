package ru.arc.dungeons

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler

class EliteInventoryRefreshSlotsTest : FunSpec({
    test("slot bursts merge without preparing unrelated slots") {
        val slots = EliteInventoryRefreshSlots(41)
        listOf(2, 2, 40, 5, 40, -1, 41).forEach(slots::dirty)
        slots.drain() shouldBe listOf(2, 40, 5)
        slots.hasWork shouldBe false
        slots.next() shouldBe null
    }

    test("a full reconciliation supersedes queued slots and can refresh the same inventory again") {
        val slots = EliteInventoryRefreshSlots(41)
        slots.dirty(8)
        slots.dirty(40)
        slots.all()
        slots.drain() shouldBe (0..40).toList()
        slots.all()
        slots.drain() shouldBe (0..40).toList()
    }

    test("changes behind a full cursor are retained and unvisited changes join the full pass") {
        val slots = EliteInventoryRefreshSlots(5)
        slots.all()
        slots.next() shouldBe 0
        slots.next() shouldBe 1
        slots.dirty(0)
        slots.dirty(3)
        slots.dirty(0)
        slots.drain() shouldBe listOf(2, 3, 4, 0)
    }

    test("a new full request during a partial pass refreshes every slot") {
        val slots = EliteInventoryRefreshSlots(5)
        slots.all()
        slots.next() shouldBe 0
        slots.next() shouldBe 1
        slots.dirty(0)
        slots.all()
        slots.drain() shouldBe (0..4).toList()
    }

    test("shared native budget retains full and newly dirty slots between ticks") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(tasks, maximumSteps = 3, maximumNanos = Long.MAX_VALUE)
        try {
            val slots = EliteInventoryRefreshSlots(41).apply { all() }
            val visited = mutableListOf<Int>()
            val completed = queue.submit({ true }) {
                slots.next()?.let(visited::add)
                !slots.hasWork
            }
            visited shouldBe emptyList()
            scheduler.tick()
            visited shouldBe listOf(0, 1, 2)
            completed.isDone shouldBe false
            slots.dirty(0)
            slots.dirty(30)
            repeat(13) { scheduler.tick() }
            visited shouldBe (0..40).toList() + 0
            completed.isDone shouldBe true
            completed.isCompletedExceptionally shouldBe false
        } finally {
            queue.close()
            tasks.close()
        }
    }

    test("a retired lifecycle cannot consume pending slots") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(tasks, maximumSteps = 1, maximumNanos = Long.MAX_VALUE)
        try {
            val token = tasks.token()
            var connected = true
            val slots = EliteInventoryRefreshSlots(41).apply { all() }
            val visited = mutableListOf<Int>()
            val completed = queue.submit({ tasks.isCurrent(token) && connected }) {
                slots.next()?.let(visited::add)
                !slots.hasWork
            }
            scheduler.tick()
            connected = false
            scheduler.tick()
            completed.isCompletedExceptionally shouldBe true
            visited shouldBe listOf(0)
            slots.next() shouldBe 1
            queue.close()
            tasks.restart()
            scheduler.tick()
            visited shouldBe listOf(0)
        } finally {
            queue.close()
            tasks.close()
        }
    }
})

private fun EliteInventoryRefreshSlots.drain(): List<Int> = buildList {
    while (hasWork) add(requireNotNull(next()))
}
