@file:Suppress("OVERLOAD_RESOLUTION_AMBIGUITY")

package ru.arc.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RepositoryTest {

    data class TestEntity(val id: String, val value: Int)

    private lateinit var repository: InMemoryRepository<TestEntity, String>

    @BeforeEach
    fun setUp() {
        repository = InMemoryRepository { it.id }
    }

    @Nested
    @DisplayName("Basic CRUD")
    inner class CrudTests {

        @Test
        fun `save and get entity`() {
            val entity = TestEntity("1", 100)
            repository.save(entity)

            val result = repository.get("1").join()

            assertEquals(entity, result)
        }

        @Test
        fun `get returns null for unknown id`() {
            val result = repository.get("unknown").join()

            assertNull(result)
        }

        @Test
        fun `getOrCreate creates when missing`() {
            val result = repository.getOrCreate("1") { TestEntity("1", 42) }.join()

            assertEquals(42, result.value)
            assertTrue(repository.exists("1"))
        }

        @Test
        fun `getOrCreate returns existing`() {
            repository.save(TestEntity("1", 100))

            val result = repository.getOrCreate("1") { TestEntity("1", 42) }.join()

            assertEquals(100, result.value)
        }

        @Test
        fun `delete removes entity`() {
            repository.save(TestEntity("1", 100))

            repository.delete("1")

            assertFalse(repository.exists("1"))
        }

        @Test
        fun `all returns all entities`() {
            repository.save(TestEntity("1", 1))
            repository.save(TestEntity("2", 2))
            repository.save(TestEntity("3", 3))

            val all = repository.all()

            assertEquals(3, all.size)
        }

        @Test
        fun `count returns entity count`() {
            repository.save(TestEntity("1", 1))
            repository.save(TestEntity("2", 2))

            assertEquals(2, repository.count())
        }

        @Test
        fun `clear removes all entities`() {
            repository.save(TestEntity("1", 1))
            repository.save(TestEntity("2", 2))

            repository.clear()

            assertEquals(0, repository.count())
        }
    }

    @Nested
    @DisplayName("Context Tracking")
    inner class ContextTests {

        @Test
        fun `addContext adds id to context`() {
            repository.addContext("player1")

            assertTrue(repository.getContext().contains("player1"))
        }

        @Test
        fun `removeContext removes id from context`() {
            repository.addContext("player1")
            repository.removeContext("player1")

            assertFalse(repository.getContext().contains("player1"))
        }

        @Test
        fun `clear removes context`() {
            repository.addContext("player1")
            repository.addContext("player2")

            repository.clear()

            assertTrue(repository.getContext().isEmpty())
        }
    }

    @Nested
    @DisplayName("Exists")
    inner class ExistsTests {

        @Test
        fun `exists returns false for missing`() {
            assertFalse(repository.exists("missing"))
        }

        @Test
        fun `exists returns true after save`() {
            repository.save(TestEntity("1", 100))

            assertTrue(repository.exists("1"))
        }
    }
}

