package ru.arc.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.staffspells.api.QuestGuidanceHudService
import java.util.UUID

class ClaimGuideHudTest : StringSpec({
    "claim HUD shares the mounted provider and releases its own menu suppression on replacement and close" {
        val previous = ARC.plugin
        ARC.plugin = mockk(relaxed = true)
        try {
            val player = mockk<Player>(relaxed = true)
            val id = UUID(0, 42)
            every { player.uniqueId } returns id
            val first = mockk<QuestGuidanceHudService>(relaxed = true)
            val second = mockk<QuestGuidanceHudService>(relaxed = true)
            every { first.showClaim(player, any(), any()) } returns true
            every { second.showClaim(player, any(), any()) } returns true
            var provider: QuestGuidanceHudService? = first
            val text = Component.text("Поставь блок — создай приват")
            ClaimGuideHud { provider }.use { hud ->
                hud.refresh(player, text, 0.5, false) shouldBe true
                verify { first.showClaim(player, text, 0.5) }
                hud.hasHologram(id) shouldBe true
                every { first.isClaimTarget(player) } returns true
                hud.isTarget(player) shouldBe true
                hud.refresh(player, text, 0.5, true) shouldBe true
                verify { first.setSuppressed(ARC.instance, id, "lands-claim-menu", true) }
                verify { first.clear(id, QuestGuidanceHudService.Source.CLAIM) }
                provider = second
                hud.refresh(player, text, -0.5, false) shouldBe true
                verify { first.setSuppressed(ARC.instance, id, "lands-claim-menu", false) }
                verify { second.showClaim(player, text, -0.5) }
                provider = null
                hud.refresh(player, text, 0.0, false) shouldBe false
                hud.hasHologram(id) shouldBe false
                hud.isTarget(player) shouldBe false
                verify(exactly = 1) { second.clear(id, QuestGuidanceHudService.Source.CLAIM) }
                verify { second.setSuppressed(ARC.instance, id, "lands-claim-menu", false) }
            }
        } finally {
            ARC.plugin = previous
        }
    }
})
