package ru.arc.hooks.packetevents

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.util.TriState
import org.bukkit.entity.LivingEntity
import ru.arc.paper.entity.PaperViewerEntityGlow
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class PacketEventsHookTest : StringSpec({
    "rider-only invisibility preserves common flags and suppresses the glow outline" {
        val entity = mockk<LivingEntity> {
            every { fireTicks } returns 0
            every { visualFire } returns TriState.FALSE
            every { isSneaking } returns true
            every { isSwimming } returns true
            every { isInvisible } returns false
            every { isGlowing } returns true
            every { isGliding } returns false
        }

        commonEntityFlags(entity, invisibleForViewer = false) shouldBe 0x52.toByte()
        commonEntityFlags(entity, invisibleForViewer = true) shouldBe 0x32.toByte()
        commonEntityFlags(entity, glowingForViewer = false) shouldBe 0x12.toByte()
        commonEntityFlags(entity, glowingForViewer = true) shouldBe 0x52.toByte()
    }

    "a retiring hook delegates restoration to the captured Core owner and observers cannot cancel it" {
        MockBukkitTestRuntime.open().use { paper ->
            val viewer = paper.addPlayer("Viewer")
            val target = paper.addPlayer("Target")
            val drain = CompletableFuture<Void>()
            val glow = mockk<PaperViewerEntityGlow>(relaxed = true) {
                every { closeAsync() } returns drain.copy()
            }
            val hook = PacketEventsHook(glow)
            val observer = hook.closeAsync()
            observer.cancel(false) shouldBe true
            hook.restoreEntityGlowingFor(target, viewer)
            val actual = hook.glowCleanupDrained()
            actual.isDone shouldBe false
            drain.complete(null)
            actual.join() shouldBe null
            verify(exactly = 1) { glow.closeAsync() }
            verify(exactly = 0) { glow.set(viewer, target, false) }
        }
    }
})
