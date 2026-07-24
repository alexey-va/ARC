package ru.arc.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.bukkit.Bukkit
import ru.arc.ARC
import java.util.concurrent.atomic.AtomicReference

/**
 * Cached gauge values — updated on a slow repeating task, not on Prometheus scrape.
 */
class MetricsSampler(
    registry: MeterRegistry,
    private val configProvider: () -> MetricsConfig = { MetricsConfig.current() },
) {
    private val tps1m = AtomicReference(20.0)
    private val tps5m = AtomicReference(20.0)
    private val tps15m = AtomicReference(20.0)
    private val playersOnline = AtomicReference(0.0)
    private val playersMax = AtomicReference(0.0)
    private val heapUsed = AtomicReference(0.0)
    private val heapMax = AtomicReference(0.0)
    private val nonHeapUsed = AtomicReference(0.0)
    private val redisConnected = AtomicReference(0.0)
    private val worldsCount = AtomicReference(0.0)
    private val loadedChunks = AtomicReference(0.0)

    init {
        val serverTag = Tags.of("server_name", ARC.serverName ?: "unknown")
        Gauge.builder("arc_tps", tps1m) { it.get() }.tags(serverTag.and("window", "1m")).register(registry)
        Gauge.builder("arc_tps", tps5m) { it.get() }.tags(serverTag.and("window", "5m")).register(registry)
        Gauge.builder("arc_tps", tps15m) { it.get() }.tags(serverTag.and("window", "15m")).register(registry)
        Gauge.builder("arc_players_online", playersOnline) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_players_max", playersMax) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_jvm_heap_used_bytes", heapUsed) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_jvm_heap_max_bytes", heapMax) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_jvm_nonheap_used_bytes", nonHeapUsed) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_redis_connected", redisConnected) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_worlds_loaded", worldsCount) { it.get() }.tags(serverTag).register(registry)
        Gauge.builder("arc_loaded_chunks_total", loadedChunks) { it.get() }.tags(serverTag).register(registry)
    }

    fun sampleCheap() {
        val server = Bukkit.getServer()
        val tps = server.tps
        tps1m.set(tps.getOrElse(0) { 20.0 }.coerceAtLeast(0.0))
        tps5m.set(tps.getOrElse(1) { 20.0 }.coerceAtLeast(0.0))
        tps15m.set(tps.getOrElse(2) { 20.0 }.coerceAtLeast(0.0))
        playersOnline.set(Bukkit.getOnlinePlayers().size.toDouble())
        playersMax.set(server.maxPlayers.toDouble())
        worldsCount.set(Bukkit.getWorlds().size.toDouble())
        redisConnected.set(if (ARC.redisManager?.isConnected() == true) 1.0 else 0.0)

        val runtime = Runtime.getRuntime()
        heapUsed.set((runtime.totalMemory() - runtime.freeMemory()).toDouble())
        heapMax.set(runtime.maxMemory().toDouble())
        val bean = java.lang.management.ManagementFactory.getMemoryMXBean()
        nonHeapUsed.set(bean.nonHeapMemoryUsage.used.toDouble())
    }

    fun sampleHeavy() {
        if (!configProvider().includeLoadedChunks) {
            loadedChunks.set(0.0)
            return
        }
        var total = 0
        for (world in Bukkit.getWorlds()) {
            total += world.loadedChunks.size
        }
        loadedChunks.set(total.toDouble())
    }
}
