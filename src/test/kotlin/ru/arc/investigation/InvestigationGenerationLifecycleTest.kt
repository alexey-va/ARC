package ru.arc.investigation

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class InvestigationGenerationLifecycleTest : StringSpec({
    "duplicate shutdown preserves the old native tail before binding a new investigation module" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val root = Files.createTempDirectory("arc-investigation-generation-")
                val fake = mockk<ARC>(relaxed = true)
                every { fake.dataPath } returns root
                every { fake.server } returns paper.server
                every { fake.isEnabled } returns true
                every { fake.name } returns "InvestigationHost"
                every { fake.logger } returns java.util.logging.Logger.getLogger("InvestigationHost")
                val previousPlugin = ARC.plugin
                ARC.plugin = fake
                val scheduler = TestTaskScheduler().also(Tasks::install)
                val nativeDrain = CompletableFuture<Void>()
                mockkObject(InvestigationTargetGlow)
                every { InvestigationTargetGlow.clearAllAsync() } returns nativeDrain
                try {
                    InvestigationModule.init()
                    scheduler.tick(2)
                    InvestigationModule.readiness().isDone shouldBe false
                    Files.exists(root.resolve("modules/investigations.yml")) shouldBe false
                    InvestigationModule.shutdown()
                    InvestigationModule.shutdown()
                    InvestigationModule.drained().isDone shouldBe false
                    InvestigationModule.init()
                    scheduler.tick(2)
                    Files.exists(root.resolve("modules/investigations.yml")) shouldBe false
                    nativeDrain.complete(null)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!InvestigationModule.readiness().isDone && System.nanoTime() < deadline) { scheduler.tick(); Thread.sleep(1) }
                    InvestigationModule.readiness().get(1, TimeUnit.SECONDS)
                    Files.isRegularFile(root.resolve("modules/investigations.yml")) shouldBe true
                } finally {
                    nativeDrain.complete(null)
                    try {
                        InvestigationModule.shutdown()
                        InvestigationModule.drained().get(5, TimeUnit.SECONDS)
                    } finally {
                        unmockkObject(InvestigationTargetGlow)
                        ARC.plugin = previousPlugin
                        ConfigManager.clear()
                    }
                }
            }
        }
    }
})
