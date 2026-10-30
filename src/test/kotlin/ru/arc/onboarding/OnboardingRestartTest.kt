package ru.arc.onboarding

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import ru.arc.ARC
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.gui.ArcMenus
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class OnboardingRestartTest : FunSpec({
    test("restart waits for optional reset and tracking before replacing the home run and opens no dialog") {
        RestartFixture().use { f ->
            val before = f.store.enroll(f.player.uniqueId).join()
            f.store.accept(f.player.uniqueId, before.runId).join()
            f.seed(f.store.load(f.player.uniqueId).join()!!)

            OnboardingService.restartSelf(f.player)
            f.scheduler.executeImmediate()
            f.store.load(f.player.uniqueId).join()!!.runId shouldBe before.runId
            verify(exactly = 0) { StoryQuestJournal.track(any(), any()) }

            f.optionalReset.complete(Unit)
            f.scheduler.executeImmediate()
            verify(exactly = 1) { StoryQuestJournal.track(f.player.uniqueId, null) }
            f.store.load(f.player.uniqueId).join()!!.runId shouldBe before.runId

            f.trackingReset.complete(null)
            f.awaitReplacement()
            val after = f.store.load(f.player.uniqueId).join()!!
            (after.runId == before.runId) shouldBe false
            after.status shouldBe OnboardingStatus.OFFERED
            after.milestones shouldBe emptySet()
            f.replacements shouldBe 1
            f.assertNoDialog()
        }
    }

    test("failed optional reset preserves the home run and inventory and reports failure without a dialog") {
        RestartFixture().use { f ->
            val before = f.store.enroll(f.player.uniqueId).join()
            f.seed(before)
            OnboardingService.restartSelf(f.player)
            f.optionalReset.completeExceptionally(IllegalStateException("storage unavailable"))
            f.scheduler.executeImmediate()
            f.store.load(f.player.uniqueId).join() shouldBe before
            f.replacements shouldBe 0
            verify(exactly = 0) { StoryQuestJournal.track(any(), any()) }
            f.assertNoDialog()
        }
    }
})

/** Installs the real command controller with its private runtime; persistence stays real in memory. */
private class RestartFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val player = paper.addPlayer("RestartAdmin").also { it.isOp = true }
    val store = OnboardingStore(InMemoryRedis(), Gson())
    val scheduler = TestTaskScheduler()
    private val previousScheduler = runCatching { Tasks.scheduler }.getOrNull()
    private val previousPlugin = ARC.plugin
    private val root = Files.createTempDirectory("onboarding-restart-")
    private val worker = BoundedAsyncExecutor("onboarding-restart-test", 1, 4)
    private val menu = mockk<OnboardingQuestDialog>(relaxed = true)
    private val inventory = mockk<OnboardingStarterInventory>()
    private val runtimeField = OnboardingService.javaClass.getDeclaredField("runtime").apply { isAccessible = true }
    private lateinit var runtime: Any
    val optionalReset = CompletableFuture<Unit>()
    val trackingReset = CompletableFuture<Void>()
    var replacements = 0

    init {
      try {
        check(runtimeField.get(OnboardingService) == null)
        Tasks.install(scheduler, cancelPrevious = false)
        val plugin = mockk<ARC>(relaxed = true)
        every { plugin.dataPath } returns root
        every { plugin.backgroundOwner() } returns worker
        ARC.plugin = plugin
        val type = OnboardingService.javaClass.declaredClasses.single { it.simpleName == "Runtime" }
        runtime = type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(mockk<OnboardingConfig>(relaxed = true), store)
        fun field(name: String, value: Any) = type.getDeclaredField(name).apply { isAccessible = true }.set(runtime, value)
        field("menu", menu)
        field("starterInventory", inventory)
        field("hud", mockk<OnboardingHud>(relaxed = true))
        runtimeField.set(OnboardingService, runtime)
        mockkObject(OptionalOnboardingService, StoryQuestJournal, ArcMenus)
        every { OptionalOnboardingService.reset(player) } returns optionalReset
        every { OptionalOnboardingService.dismissPending(player.uniqueId) } returns Unit
        every { StoryQuestJournal.track(player.uniqueId, null) } returns trackingReset
        every { StoryQuestJournal.isTracked(any(), any()) } returns false
        every { inventory.isPending(any()) } returns false
        every { inventory.replace(player, any(), any()) } answers {
            thirdArg<() -> CompletableFuture<Unit>>().invoke().thenApply { replacements++; root.resolve("backup.json") }
        }
      } catch (failure: Throwable) {
        close()
        throw failure
      }
    }

    fun seed(progress: OnboardingProgress) {
        @Suppress("UNCHECKED_CAST")
        val players = runtime.javaClass.getDeclaredField("players").apply { isAccessible = true }.get(runtime)
            as MutableMap<java.util.UUID, OnboardingProgress>
        players[player.uniqueId] = progress
    }

    fun assertNoDialog() {
        verify(exactly = 1) { OptionalOnboardingService.dismissPending(player.uniqueId) }
        verify(exactly = 0) { menu.beginPending(any()); menu.finish(any(), any(), any()) }
        verify(exactly = 0) { ArcMenus.beginDialogFlow(any()) }
    }

    fun awaitReplacement() {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (replacements == 0 && System.nanoTime() < deadline) {
            scheduler.executeImmediate()
            Thread.sleep(1)
        }
        replacements shouldBe 1
        scheduler.executeImmediate()
    }

    override fun close() {
        runtimeField.set(OnboardingService, null)
        if (this::runtime.isInitialized)
            (runtime.javaClass.getDeclaredField("tasks").apply { isAccessible = true }.get(runtime) as LifecycleTaskScope).close()
        unmockkObject(OptionalOnboardingService, StoryQuestJournal, ArcMenus)
        ARC.plugin = previousPlugin
        if (previousScheduler != null) Tasks.install(previousScheduler) else Tasks.reset()
        worker.closeAsync().join()
        paper.close()
        root.toFile().deleteRecursively()
    }
}
