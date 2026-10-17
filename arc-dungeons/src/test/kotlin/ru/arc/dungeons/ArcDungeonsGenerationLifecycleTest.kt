package ru.arc.dungeons

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime

class ArcDungeonsGenerationLifecycleTest {
    @Test
    fun `missing host fails before storage or native initialization`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ArcDungeonsPlugin::class.java)
            plugin.isEnabled shouldBe false
            plugin.prepareBackground { error("Disabled plugin must not admit storage") }.isCompletedExceptionally shouldBe true
            plugin.closeCompletion().isDone shouldBe true
            plugin.onDisable()
            plugin.closeCompletion().isDone shouldBe true
        }
    }
}
