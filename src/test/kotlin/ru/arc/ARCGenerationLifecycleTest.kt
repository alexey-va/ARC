package ru.arc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.dungeons.api.ArcDungeonConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ARCGenerationLifecycleTest : TestBase() {
    @Test
    fun `replacement and superseded waiting generation retain actual accepted worker fence`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        plugin.submitBackground { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            server.pluginManager.disablePlugin(plugin)
            val original = plugin.closeCompletion()
            assertFalse(original.isDone)
            val retiredScheduler = runCatching { Tasks.scheduler }.getOrNull()
            server.pluginManager.enablePlugin(plugin)
            repeat(3) { server.scheduler.performOneTick() }
            assertFalse(plugin.readiness().isDone)
            assertSame(retiredScheduler, runCatching { Tasks.scheduler }.getOrNull())
            assertNull(server.servicesManager.load(ArcDungeonConfiguration::class.java))
            server.pluginManager.disablePlugin(plugin)
            plugin.onDisable()
            val waiting = plugin.closeCompletion()
            assertFalse(waiting.isDone)
            server.pluginManager.enablePlugin(plugin)
            repeat(3) { server.scheduler.performOneTick() }
            assertFalse(plugin.readiness().isDone)
            release.countDown()
            awaitMainThreadFuture(server, plugin.readiness())
            original.get(5, TimeUnit.SECONDS)
            waiting.get(5, TimeUnit.SECONDS)
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
