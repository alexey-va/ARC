package ru.arc.investigation

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.ServicePriority
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.citizens.CitizensHook
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier
import kotlin.random.Random

class InvestigationTargetGlowTest : FreeSpec({
    "bulk clear restores captured old targets in host slices and waits for the old packet owner" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val queue = BudgetedTaskQueue(scope, maximumSteps = 7, nanoTime = { 0L })
            val host = paper.createSimplePlugin("InvestigationCleanup")
            val cleanup = object : ArcNativeCleanupService {
                override fun submit(owner: String, step: BooleanSupplier) =
                    queue.submit({ true }, step::getAsBoolean).thenApply<Void> { null }.minimalCompletionStage()
            }
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, cleanup, host, ServicePriority.Normal)
            val packetDrain = CompletableFuture<Void>()
            var restored = 0
            val packets = mockk<PacketEventsHook>(relaxed = true) {
                every { restoreEntityGlowingFor(any(), any()) } answers {
                    Bukkit.isPrimaryThread() shouldBe true
                    restored++
                }
                every { glowCleanupDrained() } returns packetDrain.copy()
            }
            val citizens = mockk<CitizensHook>()
            HookRegistry.packetEventsHook = packets
            HookRegistry.citizensHook = citizens
            try {
                val world = paper.addSimpleWorld("investigation")
                val case = investigationCaseGeneratorForTest().generate(Random(44))
                val ids = case.witnesses().mapIndexed { index, witness -> witness.commandValue to index + 1 }.toMap()
                val entities = ids.values.associateWith { mockk<LivingEntity>(relaxed = true) }
                every { citizens.livingEntity(any()) } answers { entities[firstArg()] }
                val policy = mockk<InvestigationConfig> {
                    every { this@mockk.world } returns world.name
                    every { point(any()) } answers { InvestigationNpcPoint(ids.getValue(firstArg()), 0.0, 0.0, 0.0, 4.5) }
                }
                repeat(10) { ordinal ->
                    val player = paper.addPlayer("Detective$ordinal")
                    player.teleport(world.spawnLocation)
                    val record = InvestigationJournalRecord(UUID.randomUUID().toString(), player.uniqueId.toString(),
                        case, 10_000, 30_000, InvestigationStatus.ACTIVE, 1)
                    InvestigationTargetGlow.refresh(player, record, policy)
                }
                val replacement = mockk<PacketEventsHook>(relaxed = true)
                HookRegistry.packetEventsHook = replacement
                HookRegistry.citizensHook = mockk(relaxed = true)
                val observer = InvestigationTargetGlow.clearAllAsync()
                observer.cancel(false) shouldBe true
                val actual = InvestigationTargetGlow.clearAllAsync()
                restored shouldBe 0
                scheduler.tick()
                restored shouldBe 7
                repeat(20) {
                    val before = restored
                    scheduler.tick()
                    (restored - before <= 7) shouldBe true
                }
                restored shouldBe 10 * ids.size
                actual.isDone shouldBe false
                packetDrain.complete(null)
                scheduler.tick()
                actual.join() shouldBe null
                verify(exactly = 0) { replacement.restoreEntityGlowingFor(any(), any()) }
            } finally {
                packetDrain.complete(null)
                repeat(20) { scheduler.tick() }
                InvestigationTargetGlow.clearAllAsync()
                repeat(20) { scheduler.tick() }
                HookRegistry.packetEventsHook = null
                HookRegistry.citizensHook = null
                queue.close(); scope.close()
            }
        }
    }
})
