package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.paper.api.ArcSqlLease
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.api.ArcSqlSettings
import ru.arc.paper.api.ArcSqlSslMode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

class SharedLostLootRepositoryTest : FreeSpec({
    fun settings() = ArcSqlSettings("localhost", 3306, "loot", "user", "password", ArcSqlSslMode.DISABLED,
        0, 1, 1_000, 1_000, 500, 30_000, false)

    "payload hash is stable UTF-8 SHA-256" {
        MySqlSharedLostLootRepository.itemHash("лут") shouldBe
            "8a746cf2c0136d0e8e2609bd16ed16b92c3e09a3e958547dbfa9e0470039e90e"
    }

    "local stored records map to one available shared tombstone identity" {
        val id = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString()
        val record = LostLootRecord(id, owner, "payload", 1_000L)
        record.validate()
        SharedLootState.entries.toSet() shouldBe
            setOf(SharedLootState.AVAILABLE, SharedLootState.CLAIMING, SharedLootState.CLAIMED, SharedLootState.SOLD, SharedLootState.CREDITING, SharedLootState.PAID)
    }

    "direct lease is retained before accepted migration and released only after its actual drain" {
        val accepted = LinkedBlockingQueue<Runnable>()
        val retained = AtomicBoolean()
        val released = AtomicBoolean()
        val dataSource = mockk<DataSource> { every { connection } throws IllegalStateException("unavailable database") }
        val executor = Executor { task ->
            check(retained.get()) { "SQL task admitted before lease retention" }
            accepted.add(task)
        }
        val lease = mockk<ArcSqlLease> {
            every { this@mockk.dataSource } returns dataSource
            every { this@mockk.executor } returns executor
            every { completionExecutor } returns executor
            every { retainDuringShutdown() } answers { retained.set(true) }
            every { close() } answers { released.set(true) }
        }
        val service = mockk<ArcSqlService> { every { acquire(any(), any()) } returns lease }
        val repository = MySqlSharedLostLootRepository.open(service, settings())
        retained.get() shouldBe true
        val migration = requireNotNull(accepted.poll(1, TimeUnit.SECONDS))
        val closing = CompletableFuture.runAsync { repository.close() }
        try {
            released.get() shouldBe false
            closing.isDone shouldBe false
        } finally { migration.run() }
        closing.get(5, TimeUnit.SECONDS)
        released.get() shouldBe true
        verify(exactly = 1) { lease.close() }
    }

    "failed retention returns the newly acquired lease without admitting SQL work" {
        val lease = mockk<ArcSqlLease> {
            every { retainDuringShutdown() } throws IllegalStateException("provider already stopped")
            every { close() } returns Unit
        }
        val service = mockk<ArcSqlService> { every { acquire(any(), any()) } returns lease }
        shouldThrow<IllegalStateException> { MySqlSharedLostLootRepository.open(service, settings()) }
        verify(exactly = 1) { lease.close() }
        verify(exactly = 0) { lease.executor; lease.dataSource }
    }
})
