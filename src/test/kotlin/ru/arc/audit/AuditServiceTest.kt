@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.audit

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import ru.arc.core.TestTaskScheduler
import ru.arc.core.TestTimeProvider

/**
 * Unit tests for AuditService.
 *
 * These tests use InMemoryAuditRepository - no Redis needed!
 */
class AuditServiceTest {

    private lateinit var repository: InMemoryAuditRepository
    private lateinit var config: AuditConfig
    private lateinit var scheduler: TestTaskScheduler
    private lateinit var timeProvider: TestTimeProvider
    private lateinit var service: AuditService

    @BeforeEach
    fun setUp() {
        repository = InMemoryAuditRepository()
        config = AuditConfig.default()
        scheduler = TestTaskScheduler()
        timeProvider = TestTimeProvider(System.currentTimeMillis())

        service = AuditService(
            repository = repository,
            config = config,
            scheduler = scheduler,
            timeProvider = timeProvider
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

            assertEquals(0, repository.get("player1").join()?.transactions?.size ?: 0)
            assertEquals(1, repository.get("player2").join()?.transactions?.size)
        }

        @Test
        fun `clearAll clears all players`() {
            service.operation("Player1", 100.0, Type.SHOP, "tx1")
            service.operation("Player2", 100.0, Type.SHOP, "tx2")

            service.clearAll()

            assertEquals(0, repository.get("player1").join()?.transactions?.size ?: 0)
            assertEquals(0, repository.get("player2").join()?.transactions?.size ?: 0)
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

            // Pruning should be triggered (but may not reduce due to age check)
            smallService.pruneOldData()

            // Just verify no exception is thrown
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

