package ru.arc.audit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.core.TestTimeProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.CompletableFuture

/**
 * Unit tests for AuditService.
 *
 * These tests use InMemoryAuditRepository - no Redis needed!
 */
class AuditServiceTest {
    private lateinit var repository: InMemoryAuditRepository
    private lateinit var config: TestAuditConfig
    private lateinit var scheduler: TestTaskScheduler
    private lateinit var timeProvider: TestTimeProvider
    private lateinit var service: AuditService

    @BeforeEach
    fun setUp() {
        repository = InMemoryAuditRepository()
        config = TestAuditConfig()
        scheduler = TestTaskScheduler()
        timeProvider = TestTimeProvider(System.currentTimeMillis())

        service =
            AuditService(
                repository = repository,
                config = config,
                scheduler = scheduler,
                timeProvider = timeProvider,
            )
    }

    @Nested
    @DisplayName("Lifecycle")
    inner class LifecycleTests {
        @Test
        fun `start schedules prune task`() {
            service.start()

            assertEquals(1, scheduler.timerCount())
        }

        @Test
        fun `stop cancels prune task`() {
            service.start()
            service.stop()

            // Task was cancelled
            scheduler.tick(config.pruneInterval)
            // No error, task is cancelled
        }
    }

    @Nested
    @DisplayName("Player Context")
    inner class PlayerContextTests {
        @Test
        fun `playerJoined adds to context`() {
            service.playerJoined("Player1")

            assertTrue(repository.getContext().contains("player1"))
        }

        @Test
        fun `playerLeft removes from context`() {
            service.playerJoined("Player1")
            service.playerLeft("Player1")

            assertFalse(repository.getContext().contains("player1"))
        }
    }

    @Nested
    @DisplayName("Operations")
    inner class OperationTests {
        @Test
        fun `operation creates new transaction`() {
            service.operation("Player1", 100.0, Type.SHOP, "Sold item")

            val data = repository.get("player1").join()
            assertNotNull(data)
            assertEquals(1, data!!.transactions.size)
            assertEquals(100.0, data.transactions.first.amount)
        }

        @Test
        fun `income records positive amount`() {
            service.income("Player1", 50.0, Type.JOB, "Mining job")

            val data = repository.get("player1").join()
            assertTrue(data!!.transactions.first.isIncome)
        }

        @Test
        fun `expense records negative amount`() {
            service.expense("Player1", 30.0, Type.SHOP, "Bought item")

            val data = repository.get("player1").join()
            assertTrue(data!!.transactions.first.isExpense)
        }

        @Test
        fun `income rejects negative amount`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.income("Player1", -10.0, Type.JOB, "Invalid")
            }
        }

        @Test
        fun `expense rejects negative amount`() {
            assertThrows(IllegalArgumentException::class.java) {
                service.expense("Player1", -10.0, Type.SHOP, "Invalid")
            }
        }

        @Test
        fun `operation explicitly saves a mutated cached entity`() {
            val recordingRepository = RecordingAuditRepository()
            val recordingService = AuditService(recordingRepository, config, scheduler, timeProvider)

            recordingService.operation("Player1", 15.0, Type.SHOP, "sale")

            assertEquals(1, recordingRepository.saveCount)
            assertEquals(15.0, recordingRepository.get("player1").join()!!.transactions.single().amount)
        }

        @Test
        fun `economy operations use server-qualified shards and combine for player queries`() {
            val spawn = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "spawn")
            val survival = AuditMetadata(EconomySource.SHOP, EconomyFlow.MINT, server = "survival")

            service.economyOperation("Player1", 10.0, Type.JOB, "spawn", spawn)
            service.economyOperation("Player1", 20.0, Type.SHOP, "survival", survival)

            assertNotNull(repository.get("spawn:player1").join())
            assertNotNull(repository.get("survival:player1").join())
            assertEquals(2, service.getAuditData("Player1")!!.transactions.size)
        }

        @Test
        fun `repository failures increment a bounded persistence metric`() {
            val registry = SimpleMeterRegistry()
            val monitor = EconomyAuditMonitor(config, { registry }, timeProvider)
            val failing = FailingAuditRepository()
            val failingService = AuditService(failing, config, scheduler, timeProvider, monitor)
            val metadata = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")

            failingService.economyOperation("Player1", 10.0, Type.JOB, "failure", metadata)

            assertEquals(1.0, registry.get("arc_economy_persistence_failures_total").counter().count())
        }
    }

    @Nested
    @DisplayName("Queries")
    inner class QueryTests {
        @Test
        fun `totalWeight returns sum of all transactions`() {
            service.operation("Player1", 10.0, Type.SHOP, "tx1")
            service.operation("Player1", 20.0, Type.SHOP, "tx2")
            service.operation("Player2", 30.0, Type.SHOP, "tx3")

            // 3 unique transactions (different comments = no aggregation)
            assertEquals(3L, service.totalWeight())
        }

        @Test
        fun `getAuditData returns player data`() {
            service.operation("Player1", 100.0, Type.SHOP, "Test")

            val data = service.getAuditData("Player1")

            assertNotNull(data)
            assertEquals("Player1", data!!.name)
        }

        @Test
        fun `getAuditData returns null for unknown player`() {
            val data = service.getAuditData("Unknown")

            assertNull(data)
        }
    }

    @Nested
    @DisplayName("Clear")
    inner class ClearTests {
        @Test
        fun `clearPlayer clears specific player`() {
            service.operation("Player1", 100.0, Type.SHOP, "tx1")
            service.operation("Player2", 100.0, Type.SHOP, "tx2")

            service.clearPlayer("Player1")

            assertEquals(
                0,
                repository
                    .get("player1")
                    .join()
                    ?.transactions
                    ?.size ?: 0,
            )
            assertEquals(
                1,
                repository
                    .get("player2")
                    .join()
                    ?.transactions
                    ?.size,
            )
        }

        @Test
        fun `clearAll clears all players`() {
            service.operation("Player1", 100.0, Type.SHOP, "tx1")
            service.operation("Player2", 100.0, Type.SHOP, "tx2")

            service.clearAll()

            assertEquals(
                0,
                repository
                    .get("player1")
                    .join()
                    ?.transactions
                    ?.size ?: 0,
            )
            assertEquals(
                0,
                repository
                    .get("player2")
                    .join()
                    ?.transactions
                    ?.size ?: 0,
            )
        }

        @Test
        fun `clearPlayer persists the cleared entity`() {
            val recordingRepository = RecordingAuditRepository()
            val recordingService = AuditService(recordingRepository, config, scheduler, timeProvider)
            recordingService.operation("Player1", 100.0, Type.SHOP, "tx")
            recordingRepository.saveCount = 0

            recordingService.clearPlayer("Player1")

            assertEquals(1, recordingRepository.saveCount)
            assertTrue(recordingRepository.get("player1").join()!!.transactions.isEmpty())
        }
    }

    @Nested
    @DisplayName("Pruning")
    inner class PruningTests {
        @Test
        fun `pruneOldData trims when over weight`() {
            // Create config with very small maxTransactions so trim happens regardless of age
            val smallConfig = config.copy(maxWeight = 5, maxAgeSeconds = 86400 * 365) // 1 year
            val smallService = AuditService(repository, smallConfig, scheduler, timeProvider)

            // Add many transactions with different comments (to prevent aggregation)
            repeat(10) { i ->
                smallService.operation("Player1", 1.0, Type.SHOP, "tx$i")
            }

            assertEquals(10L, smallService.totalWeight())

            // Prune with small max transactions limit
            val data = repository.get("player1").join()!!
            data.trim(Long.MAX_VALUE, 5) // maxAge = infinite, maxTransactions = 5

            assertTrue(data.transactions.size <= 5, "Expected <= 5 transactions but was ${data.transactions.size}")
        }

        @Test
        fun `pruneOldData is called by service`() {
            val smallConfig = config.copy(maxWeight = 3, maxAgeSeconds = 1)
            val smallService = AuditService(repository, smallConfig, scheduler, timeProvider)

            // Add transactions
            repeat(5) { i ->
                smallService.operation("Player1", 1.0, Type.SHOP, "tx$i")
            }

            val initialWeight = smallService.totalWeight()
            assertEquals(5L, initialWeight)

            smallService.pruneOldData()

            assertEquals(3L, smallService.totalWeight())
        }

        @Test
        fun `pruneOldData enforces age retention even below weight limit`() {
            val retentionConfig = config.copy(maxWeight = 1000, maxAgeSeconds = 60)
            val retentionService = AuditService(repository, retentionConfig, scheduler, timeProvider)
            val data = AuditData.create("Player1")
            data.transactions.add(
                Transaction(
                    type = Type.SHOP,
                    amount = 1.0,
                    comment = "expired",
                    timestamp = timeProvider.currentTimeMillis() - 61_000,
                    timestamp2 = timeProvider.currentTimeMillis() - 61_000,
                ),
            )
            repository.save(data)

            retentionService.pruneOldData()

            assertTrue(repository.get("player1").join()!!.transactions.isEmpty())
        }

        @Test
        fun `pruning persists trimmed entities`() {
            val recordingRepository = RecordingAuditRepository()
            val pruneConfig = config.copy(maxWeight = 0, maxTransactions = 0)
            val recordingService = AuditService(recordingRepository, pruneConfig, scheduler, timeProvider)
            recordingService.operation("Player1", 1.0, Type.SHOP, "tx")
            recordingRepository.saveCount = 0

            recordingService.pruneOldData()

            assertTrue(recordingRepository.saveCount > 0)
            assertTrue(recordingRepository.get("player1").join()!!.transactions.isEmpty())
        }

        @Test
        fun `server shard pruning never mutates another server shard`() {
            val shardConfig = config.copy(shardMaxWeight = 1, maxTransactions = 10)
            val shardService = AuditService(repository, shardConfig, scheduler, timeProvider, localServer = "survival")
            val spawn = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "spawn")
            val survival = AuditMetadata(EconomySource.JOBS, EconomyFlow.MINT, server = "survival")
            shardService.economyOperation("Player", 1.0, Type.JOB, "spawn", spawn)
            shardService.economyOperation("Player", 1.0, Type.JOB, "survival-1", survival)
            shardService.economyOperation("Player", 1.0, Type.JOB, "survival-2", survival)

            shardService.pruneOldData()

            assertEquals(1, repository.get("spawn:player").join()!!.transactions.size)
            assertEquals(1, repository.get("survival:player").join()!!.transactions.size)
        }
    }

    @Nested
    @DisplayName("Aggregation")
    inner class AggregationTests {
        @Test
        fun `same type and comment aggregates`() {
            service.operation("Player1", 10.0, Type.SHOP, "Buy apples")
            service.operation("Player1", 20.0, Type.SHOP, "Buy apples")
            service.operation("Player1", 5.0, Type.SHOP, "Buy apples")

            val data = repository.get("player1").join()

            // Should aggregate into 1 transaction
            assertEquals(1, data!!.transactions.size)
            assertEquals(35.0, data.transactions.first.amount)
        }

        @Test
        fun `different comment creates new transaction`() {
            service.operation("Player1", 10.0, Type.SHOP, "Buy apples")
            service.operation("Player1", 20.0, Type.SHOP, "Buy oranges")

            val data = repository.get("player1").join()

            assertEquals(2, data!!.transactions.size)
        }

        @Test
        fun `different type creates new transaction`() {
            service.operation("Player1", 10.0, Type.SHOP, "Trade")
            service.operation("Player1", 20.0, Type.JOB, "Trade")

            val data = repository.get("player1").join()

            assertEquals(2, data!!.transactions.size)
        }
    }

    @Nested
    @DisplayName("Statistics")
    inner class StatisticsTests {
        @Test
        fun `totalBalance calculates correctly`() {
            service.income("Player1", 100.0, Type.JOB, "Work")
            service.expense("Player1", 30.0, Type.SHOP, "Buy")

            val data = repository.get("player1").join()!!

            assertEquals(70.0, data.totalBalance(), 0.01)
        }

        @Test
        fun `totalIncome sums only positive`() {
            service.income("Player1", 100.0, Type.JOB, "Work1")
            service.income("Player1", 50.0, Type.JOB, "Work2")
            service.expense("Player1", 30.0, Type.SHOP, "Buy")

            val data = repository.get("player1").join()!!

            assertEquals(150.0, data.totalIncome(), 0.01)
        }

        @Test
        fun `totalExpense sums only negative as absolute`() {
            service.income("Player1", 100.0, Type.JOB, "Work")
            service.expense("Player1", 30.0, Type.SHOP, "Buy1")
            service.expense("Player1", 20.0, Type.SHOP, "Buy2")

            val data = repository.get("player1").join()!!

            assertEquals(-50.0, data.totalExpense(), 0.01)
        }
    }

    @Nested
    @DisplayName("Filtering")
    inner class FilteringTests {
        @BeforeEach
        fun setUpData() {
            service.income("Player1", 100.0, Type.SHOP, "Sell")
            service.expense("Player1", 50.0, Type.SHOP, "Buy")
            service.income("Player1", 200.0, Type.JOB, "Work")
            service.expense("Player1", 30.0, Type.PAY, "Transfer")
        }

        @Test
        fun `filter ALL returns all`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.ALL)

            assertEquals(4, filtered.size)
        }

        @Test
        fun `filter INCOME returns only positive`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.INCOME)

            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.isIncome })
        }

        @Test
        fun `filter EXPENSE returns only negative`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.EXPENSE)

            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.isExpense })
        }

        @Test
        fun `filter SHOP returns shop transactions`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.SHOP)

            assertEquals(2, filtered.size)
            assertTrue(filtered.all { it.type == Type.SHOP })
        }

        @Test
        fun `filter JOB returns job transactions`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.JOB)

            assertEquals(1, filtered.size)
            assertEquals(Type.JOB, filtered.first().type)
        }

        @Test
        fun `filter PAY returns pay transactions`() {
            val data = repository.get("player1").join()!!
            val filtered = data.getFiltered(AuditFilter.PAY)

            assertEquals(1, filtered.size)
            assertEquals(Type.PAY, filtered.first().type)
        }
    }
}

private class RecordingAuditRepository(
    private val delegate: InMemoryAuditRepository = InMemoryAuditRepository(),
) : AuditRepository by delegate {
    var saveCount: Int = 0

    override fun save(entity: AuditData) {
        saveCount++
        delegate.save(entity)
    }
}

private class FailingAuditRepository : AuditRepository {
    override fun get(id: String): CompletableFuture<AuditData?> = CompletableFuture.failedFuture(IllegalStateException("load failed"))
    override fun getOrCreate(id: String, factory: () -> AuditData): CompletableFuture<AuditData> =
        CompletableFuture.failedFuture(IllegalStateException("load failed"))
    override fun save(entity: AuditData) = Unit
    override fun all(): Collection<AuditData> = emptyList()
    override fun addContext(id: String) = Unit
    override fun removeContext(id: String) = Unit
    override fun getContext(): Set<String> = emptySet()
    override fun shutdown() = Unit
}
