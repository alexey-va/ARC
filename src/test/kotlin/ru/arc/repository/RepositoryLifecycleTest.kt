package ru.arc.repository

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.core.BoundedAsyncExecutor
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

class RepositoryLifecycleTest {
    private data class Value(val key: String, val revision: Int) : Entity {
        override fun id(): String = key
    }

    @Test
    fun `detach signal does not wait for accepted work and terminal flush follows its mutation`() {
        val operations = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val accepted = CountDownLatch(1)
        val releaseOperation = CountDownLatch(1)
        val flushing = CountDownLatch(1)
        val releaseFlush = CountDownLatch(1)
        val values = mutableListOf<Value>()
        val caller = Thread.currentThread()
        val storage = mockk<Storage<Value>>()
        coEvery { storage.saveMany(any()) } coAnswers {
            check(Thread.currentThread() !== caller)
            values.addAll(firstArg<Collection<Value>>())
            flushing.countDown()
            check(releaseFlush.await(5, TimeUnit.SECONDS))
            RepoResult.success(Unit)
        }
        val repository = CachedRepository(
            RepoConfig.builder<Value>("arc-lifecycle-test").saveInterval(1.hours).enableCleanup(false).build(),
            storage,
        )
        runBlocking { repository.init().getOrThrow() }
        val worker = BoundedAsyncExecutor("arc-lifecycle-test")
        try {
            val operation = operations.future {
                accepted.countDown()
                check(releaseOperation.await(5, TimeUnit.SECONDS))
                repository.markDirty(Value("player", 2))
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            assertTimeout(Duration.ofSeconds(1)) { sealRepositoryOperations(operations) }
            val closed = worker.closeAsync { drainRepository(repository, operations) }
            assertFalse(operation.isDone)
            assertFalse(closed.isDone)
            releaseOperation.countDown()
            operation.get(5, TimeUnit.SECONDS)
            assertTrue(flushing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.isDone)
            releaseFlush.countDown()
            closed.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(Value("player", 2)), values)
            assertFalse(CachedRepository.allStats().any { it.repoId == "arc-lifecycle-test" })
        } finally {
            releaseOperation.countDown()
            releaseFlush.countDown()
            worker.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `closed operation scope yields terminal future instead of a pending acknowledgement`() {
        val operations = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sealRepositoryOperations(operations)
        var invoked = false
        val result = operations.future { invoked = true }
        assertThrows(java.util.concurrent.CancellationException::class.java) { result.get(5, TimeUnit.SECONDS) }
        assertFalse(invoked)
    }

    @Test
    fun `repository close failure is visible in the terminal drain future`() {
        val operations = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repository = mockk<CachedRepository<Value>>()
        coEvery { repository.shutdown() } throws IllegalStateException("final persistence failed")
        sealRepositoryOperations(operations)
        val worker = BoundedAsyncExecutor("arc-failed-drain-test")
        val closed = worker.closeAsync { drainRepository(repository, operations) }
        val failure = assertThrows(ExecutionException::class.java) { closed.get(5, TimeUnit.SECONDS) }
        assertEquals("final persistence failed", failure.cause?.message)
    }
}
