package ru.arc.metrics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ProductInterestAsyncTest {
    @TempDir lateinit var directory: Path
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()
    private val id = "00000000-0000-0000-0000-000000000042"
    private val config = ProductInterestConfig(networkEnabled = false, retentionDays = 8, zoneId = ZoneId.of("UTC"))
    private fun sample() = ProductPlayerSample(id, ProductWorldType.SURVIVAL, "vanilla", 0.0, 64.0, 0.0)
    private fun processor(path: Path, dispatch: (() -> Unit) -> Boolean, gson: Gson = Gson(), redis: RedisOperations? = null,
                          clock: () -> Long = { now }) = ProductInterestProcessor(SimpleMeterRegistry(),
        config.copy(networkEnabled = redis != null), "survival", path, true, redis, gson, clock, dispatch)

    @Test
    fun `captures admitted while store opens survive ordered shutdown and reopen`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = MetricsWorkQueue()
        val path = directory.resolve("state.json")
        val product = ProductInterestTelemetry(queue, { dispatch ->
            entered.countDown(); release.await(); processor(path, dispatch)
        }, { now })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(product.join(id, true, sample(), now = now))
            assertTrue(CompletableFuture.supplyAsync { product.action(id, ProductAction.BLOCK_BREAK, now + 1) }.get(2, TimeUnit.SECONDS))
            assertTrue(product.action(id, ProductAction.BLOCK_BREAK, now + 2))
            val closed = queue.closeAsync { product.shutdownOnWorker(now + 3) }
            assertFalse(closed.isDone)
            release.countDown()
            closed.get(5, TimeUnit.SECONDS)
            val restored = ProductInterestStore.open(path, config, now + 4)
            assertEquals(2L, restored.actionCount(ProductPseudonym.of(id), ProductAction.BLOCK_BREAK, now))
            assertEquals(1, restored.report(now + 4, 1, 10)["players"])
        } finally { release.countDown(); queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `blocked persistence leaves main admission free and saves later accepted mutation on drain`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        val gson = GsonBuilder().registerTypeAdapterFactory(object : TypeAdapterFactory {
            override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
                if (type.rawType.simpleName != "StoreFile") return null
                val delegate = gson.getDelegateAdapter(this, type)
                return object : TypeAdapter<T>() {
                    override fun read(input: JsonReader): T = delegate.read(input)
                    override fun write(out: JsonWriter, value: T) {
                        if (block.compareAndSet(true, false)) { entered.countDown(); release.await() }
                        delegate.write(out, value)
                    }
                }
            }
        }).create()
        val queue = MetricsWorkQueue()
        val path = directory.resolve("state.json")
        val product = ProductInterestTelemetry(queue, { processor(path, it, gson) }, { now })
        try {
            product.ready.get(5, TimeUnit.SECONDS)
            assertTrue(product.join(id, false, sample()))
            val flush = product.flush(now + 31_000)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(CompletableFuture.supplyAsync { product.action(id, ProductAction.BLOCK_BREAK, now + 32_000) }.get(2, TimeUnit.SECONDS))
            val close = queue.closeAsync { product.shutdownOnWorker(now + 33_000) }
            assertFalse(flush.isDone)
            assertFalse(close.isDone)
            release.countDown()
            close.get(5, TimeUnit.SECONDS)
            val restored = ProductInterestStore.open(path, config, now + 34_000)
            assertEquals(1L, restored.actionCount(ProductPseudonym.of(id), ProductAction.BLOCK_BREAK, now))
        } finally { release.countDown(); queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `blocked Redis publish neither blocks captures nor prevents final local durability`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val network = InMemoryRedis()
        val redis = object : RedisOperations by network {
            override fun publish(channel: String, message: String) {
                entered.countDown(); release.await(); throw IllegalStateException("network unavailable")
            }
        }
        val queue = MetricsWorkQueue()
        val path = directory.resolve("state.json")
        val product = ProductInterestTelemetry(queue, { processor(path, it, redis = redis) }, { now })
        try {
            product.ready.get(5, TimeUnit.SECONDS)
            assertTrue(product.join(id, false, sample()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(CompletableFuture.supplyAsync { product.action(id, ProductAction.BLOCK_BREAK, now + 1) }.get(2, TimeUnit.SECONDS))
            val close = queue.closeAsync { product.shutdownOnWorker(now + 2) }
            assertFalse(close.isDone)
            release.countDown()
            close.get(5, TimeUnit.SECONDS)
            assertEquals(1L, ProductInterestStore.open(path, config, now + 3)
                .actionCount(ProductPseudonym.of(id), ProductAction.BLOCK_BREAK, now))
        } finally { release.countDown(); queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `failed product recovery exposes failed status rejects followups and fences attach`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = MetricsWorkQueue()
        val product = ProductInterestTelemetry(queue, { entered.countDown(); release.await(); error("store unavailable") }, { now })
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(product.action(id, ProductAction.BLOCK_BREAK))
            release.countDown()
            assertThrows(ExecutionException::class.java) { product.ready.get(5, TimeUnit.SECONDS) }
            val attached = AtomicBoolean()
            val barrier = awaitTelemetryInitialization(queue, product.ready, CompletableFuture.completedFuture(Unit))
                .thenRun { attached.set(true) }
            assertThrows(ExecutionException::class.java) { barrier.get(5, TimeUnit.SECONDS) }
            assertFalse(attached.get())
            assertFalse(product.action(id, ProductAction.BLOCK_BREAK))
            assertEquals("failed", product.measurementResetStatus()["state"])
            assertFalse(queue.coverageComplete())
            assertThrows(ExecutionException::class.java) { queue.closeAsync { product.shutdownOnWorker(now) }.get(5, TimeUnit.SECONDS) }
        } finally { release.countDown(); queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `worker replay rejection preserves exactly one action`() {
        val queue = MetricsWorkQueue()
        val path = directory.resolve("state.json")
        val product = ProductInterestTelemetry(queue, { processor(path, it) }, { now })
        try {
            product.join(id, false, sample())
            repeat(2) {
                assertTrue(product.onWorker { processor ->
                    if (processor.claimExternal("signal|$id|arcranks|operation-1", now)) processor.action(id, ProductAction.BLOCK_BREAK, now)
                })
            }
            queue.closeAsync { product.shutdownOnWorker(now + 1) }.get(5, TimeUnit.SECONDS)
            assertEquals(1L, ProductInterestStore.open(path, config, now + 2)
                .actionCount(ProductPseudonym.of(id), ProductAction.BLOCK_BREAK, now))
        } finally { queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `queued reset archives prior commands and keeps subsequent commands in the new period`() {
        val clock = AtomicLong(now)
        val queue = MetricsWorkQueue()
        val path = directory.resolve("state.json")
        val product = ProductInterestTelemetry(queue, { processor(path, it, redis = InMemoryRedis(), clock = clock::get) }, clock::get)
        try {
            product.ready.get(5, TimeUnit.SECONDS)
            product.join(id, true, sample(), now = now)
            product.command(id, "/before_reset", now + 1)
            clock.set(now + 10_000)
            val reset = product.requestMeasurementReset(0)
            product.command(id, "/after_reset", now + 10_001)
            val state = reset.get(5, TimeUnit.SECONDS)
            val report = product.report(1, 20, true, now + 10_002).get(5, TimeUnit.SECONDS).toString()
            assertTrue(report.contains("after_reset"))
            assertFalse(report.contains("before_reset"))
            queue.closeAsync { product.shutdownOnWorker(now + 11_000) }.get(5, TimeUnit.SECONDS)
            assertTrue(Files.readString(path).contains("before_reset"))
            assertTrue(Files.readString(path.resolveSibling("product-interest-period-${state.boundaryAt}.json")).contains("after_reset"))
        } finally { queue.closeAsync().handle { _, _ -> }.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun `daily prune retains boundary and removes expired day reintroduced by a late event`() {
        val path = directory.resolve("state.json")
        val store = ProductInterestStore.open(path, config, now)
        val player = ProductPseudonym.of(id)
        val expired = now - 9 * 86_400_000L
        val boundary = now - 7 * 86_400_000L
        store.recordAction(player, ProductAction.BLOCK_BREAK, boundary)
        store.recordAction(player, ProductAction.BLOCK_BREAK, now)
        store.recordAction(player, ProductAction.BLOCK_BREAK, expired)
        store.recordAction(player, ProductAction.BLOCK_BREAK, now + 1)
        store.flush(now + 2, true)
        val restored = ProductInterestStore.open(path, config, now + 3)
        assertEquals(0L, restored.actionCount(player, ProductAction.BLOCK_BREAK, expired))
        assertEquals(1L, restored.actionCount(player, ProductAction.BLOCK_BREAK, boundary))
        assertEquals(2L, restored.actionCount(player, ProductAction.BLOCK_BREAK, now))
    }
}
