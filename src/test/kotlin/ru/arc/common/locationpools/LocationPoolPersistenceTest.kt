package ru.arc.common.locationpools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.common.ServerLocation
import ru.arc.config.LocationPoolConfig
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class LocationPoolPersistenceTest {
    @TempDir lateinit var root: Path
    private fun point(x: Double) = ServerLocation("survival", "world", x, 64.0, 0.0)

    @Test
    fun `blocked save cannot block edit or clear a newer revision`() {
        val pool = LocationPool("event_pool").apply { addLocation(point(1.0)); markClean() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<List<ServerLocation>>()
        val threads = CopyOnWriteArrayList<String>()
        val store = LocationPoolConfig.forTest(root, listOf(pool)) { _, mirror ->
            threads += Thread.currentThread().name
            writes += mirror.getAllLocations().toList()
            if (writes.size == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        store.bindInitialPools()
        try {
            val saving = store.saveLocationPools(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.addLocation(point(2.0))
            assertEquals(2, pool.size)
            assertTrue(pool.isDirty)
            assertFalse(saving.isDone)
            release.countDown()
            saving.get(5, TimeUnit.SECONDS)
            // The first write only acknowledged its own revision, not the later native edit.
            assertTrue(pool.isDirty)
            store.saveLocationPools(true).get(5, TimeUnit.SECONDS)
            assertFalse(pool.isDirty)
            assertEquals(listOf(point(1.0)), writes.first())
            assertEquals(listOf(point(1.0), point(2.0)), writes.last())
            assertTrue(threads.all { it.startsWith("arc-location-pools-test-") })
        } finally { release.countDown(); store.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `saturated edits reject before mutation and terminal slot flushes accepted deltas`() {
        val pool = LocationPool("event_pool").apply { addLocation(point(1.0)); markClean() }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<List<ServerLocation>>()
        val store = LocationPoolConfig.forTest(root, listOf(pool), capacity = 1) { _, mirror ->
            writes += mirror.getAllLocations().toList()
            if (writes.size == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        store.bindInitialPools()
        try {
            store.saveLocationPools(false)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pool.addLocation(point(2.0))
            assertThrows(RejectedExecutionException::class.java) { pool.addLocation(point(3.0)) }
            assertEquals(2, pool.size)
            val closing = store.closeAsync()
            assertFalse(closing.isDone)
            assertThrows(RejectedExecutionException::class.java) { pool.removeLocation(point(1.0)) }
            release.countDown()
            closing.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(point(1.0), point(2.0)), writes.last())
            assertFalse(pool.isDirty)
        } finally { release.countDown(); store.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `reload drains edits and rejects old generation references after publication`() {
        val store = LocationPoolConfig.open(root)
        val old = LocationPool("event_pool")
        store.register(old)
        old.addLocation(point(1.0))
        try {
            val reloading = store.prepareReload()
            assertThrows(IllegalStateException::class.java) { old.addLocation(point(2.0)) }
            val pools = reloading.get(5, TimeUnit.SECONDS)
            store.bindReload(pools)
            val current = pools.single()
            assertEquals(listOf(point(1.0)), current.getAllLocations().toList())
            assertThrows(IllegalStateException::class.java) { old.addLocation(point(3.0)) }
            current.addLocation(point(4.0))
            store.saveLocationPools(true).get(5, TimeUnit.SECONDS)
            assertFalse(current.isDirty)
            assertEquals(listOf(point(1.0), point(4.0)), store.snapshots(listOf(current)).get(5, TimeUnit.SECONDS)
                .single().getAllLocations().toList())
        } finally { store.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `failed replacement retains old mirror and reports terminal save failure`() {
        val old = LocationPool("event_pool").apply { addLocation(point(1.0)); markClean() }
        val store = LocationPoolConfig.forTest(root, listOf(old)) { _, _ -> throw java.io.IOException("disk unavailable") }
        store.bindInitialPools()
        val replacement = LocationPool("event_pool").apply { addLocation(point(2.0)); setWritable(false) }
        try {
            assertThrows(java.util.concurrent.ExecutionException::class.java) { store.replace(replacement).get(5, TimeUnit.SECONDS) }
            val snapshot = store.snapshots(listOf(old)).get(5, TimeUnit.SECONDS).single()
            assertEquals(listOf(point(1.0)), snapshot.getAllLocations().toList())
            old.addLocation(point(3.0))
            assertThrows(java.util.concurrent.ExecutionException::class.java) { store.closeAsync().get(5, TimeUnit.SECONDS) }
            assertTrue(old.isDirty)
        } finally { runCatching { store.closeAsync().get(5, TimeUnit.SECONDS) } }
    }
}
