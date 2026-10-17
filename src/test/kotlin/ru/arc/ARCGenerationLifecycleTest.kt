package ru.arc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.mounts.api.ArcMountConfiguration
import ru.arc.origin.api.ArcOriginConfiguration
import ru.arc.contracts.api.ArcContractsHostConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ARCGenerationLifecycleTest : TestBase() {
    @Test
    fun `sequential startup publishes ready providers before dependent enable`() {
        server.pluginManager.disablePlugin(plugin)
        awaitMainThreadFuture(server, plugin.closeCompletion())
        assertNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))

        server.pluginManager.enablePlugin(plugin)

        val dungeons = requireNotNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))
        val mounts = requireNotNull(server.servicesManager.load(ArcMountConfiguration::class.java))
        val origin = requireNotNull(server.servicesManager.load(ArcOriginConfiguration::class.java))
        val contracts = requireNotNull(server.servicesManager.load(ArcContractsHostConfiguration::class.java))
        assertTrue(plugin.readiness().isDone)
        assertTrue(dungeons.ready().isDone)
        assertTrue(mounts.ready().isDone)
        assertTrue(origin.ready().isDone)
        assertTrue(contracts.ready().isDone)
        awaitMainThreadFuture(server, plugin.readiness())
    }

    @Test
    fun `stop waits accepted storage before a replacement can start`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        plugin.submitBackground { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val releaseWorker = java.util.concurrent.CompletableFuture.runAsync {
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!plugin.readiness().isCompletedExceptionally && System.nanoTime() < deadline) Thread.yield()
                assertTrue(plugin.readiness().isCompletedExceptionally)
                assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
            } finally { release.countDown() }
        }
        try {
            server.pluginManager.disablePlugin(plugin)
            returned.countDown()
            releaseWorker.get(5, TimeUnit.SECONDS)
            awaitMainThreadFuture(server, plugin.closeCompletion())
            assertNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))
            server.pluginManager.enablePlugin(plugin)
            assertTrue(plugin.readiness().isDone)
            plugin.readiness().join()
            assertTrue(plugin.isEnabled)
        } finally { release.countDown() }
    }

    @Test
    fun `failed predecessor cannot publish replacement bindings`() {
        plugin.retireInBackground { error("injected terminal cleanup failure") }
        server.pluginManager.disablePlugin(plugin)
        val retired = plugin.closeCompletion()
        awaitMainThreadFuture(server, retired.handle { _, _ -> Unit })
        assertTrue(retired.isCompletedExceptionally)
        val scheduler = runCatching { Tasks.scheduler }.getOrNull()
        server.pluginManager.enablePlugin(plugin)
        val result = plugin.readiness()
        awaitMainThreadFuture(server, result.handle { _, _ -> Unit })
        assertTrue(result.isCompletedExceptionally)
        assertFalse(plugin.isEnabled)
        assertSame(scheduler, runCatching { Tasks.scheduler }.getOrNull())
        assertNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))
    }
}
