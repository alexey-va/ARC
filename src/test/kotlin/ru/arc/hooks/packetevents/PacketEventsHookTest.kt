package ru.arc.hooks.packetevents

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.util.TriState
import org.bukkit.entity.LivingEntity

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
    }
})
