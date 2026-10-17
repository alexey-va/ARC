package ru.arc.dungeons

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.Test
import ru.arc.core.Tasks
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArcDungeonsGenerationLifecycleTest {
    @Test
    fun `waiting replacement cannot install globals before accepted predecessor exits`() {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ArcDungeonsGenerationHost")
            val hostReady = CompletableFuture<Void>()
            val configuration = mockk<ArcDungeonConfiguration> { every { ready() } returns hostReady }
            paper.server.servicesManager.register(ArcDungeonConfiguration::class.java, configuration, host, ServicePriority.Normal)
            val plugin = paper.loadSimplePlugin(ArcDungeonsPlugin::class.java)
            paper.performTicks(3)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            plugin.prepareBackground { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            try {
                paper.server.pluginManager.disablePlugin(plugin)
                val original = plugin.closeCompletion()
                original.isDone shouldBe false
                val retiredScheduler = runCatching { Tasks.scheduler }.getOrNull()
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                runCatching { Tasks.scheduler }.getOrNull() shouldBe retiredScheduler
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.onDisable()
                val waiting = plugin.closeCompletion()
                waiting.isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                runCatching { Tasks.scheduler }.getOrNull() shouldBe retiredScheduler
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ((!original.isDone || !waiting.isDone) && System.nanoTime() < deadline) {
                    paper.performTicks(1); Thread.sleep(1)
                }
                original.get(1, TimeUnit.SECONDS)
                waiting.get(1, TimeUnit.SECONDS)
                paper.performTicks(3)
                plugin.isEnabled shouldBe true
            } finally {
                release.countDown()
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.closeCompletion().get(5, TimeUnit.SECONDS)
            }
        }
    }
}
