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

    listOf(AbstractMethodError("setSuppressed"), IllegalStateException("provider failed")).forEach { failure ->
        "claim HUD falls back after ${failure.javaClass.simpleName} and accepts a replacement provider" {
            val previous = ARC.plugin
            ARC.plugin = mockk(relaxed = true)
            try {
                val player = mockk<Player>(relaxed = true)
                val id = UUID(0, 43)
                every { player.uniqueId } returns id
                val broken = mockk<QuestGuidanceHudService>(relaxed = true)
                val replacement = mockk<QuestGuidanceHudService>(relaxed = true)
                every { broken.setSuppressed(any(), id, any(), any()) } throws failure
                every { replacement.showClaim(player, any(), any()) } returns true
                var provider: QuestGuidanceHudService = broken
                ClaimGuideHud { provider }.use { hud ->
                    repeat(3) { hud.refresh(player, Component.text("Claim"), 0.0, false) shouldBe false }
                    hud.hasHologram(id) shouldBe false
                    hud.isTarget(player) shouldBe false
                    // Initial failure plus one best-effort release; subsequent ticks skip the failed instance.
                    verify(exactly = 2) { broken.setSuppressed(any(), id, any(), false) }
                    verify(exactly = 1) { broken.clear(id, QuestGuidanceHudService.Source.CLAIM) }
                    verify(exactly = 0) { broken.showClaim(any(), any(), any()) }
                    provider = replacement
                    hud.refresh(player, Component.text("Claim"), 0.0, false) shouldBe true
                    hud.hasHologram(id) shouldBe true
                    provider = broken
                    hud.refresh(player, Component.text("Claim"), 0.0, false) shouldBe false
                    verify(exactly = 1) { replacement.clear(id, QuestGuidanceHudService.Source.CLAIM) }
                    verify(exactly = 4) { broken.setSuppressed(any(), id, any(), false) }
                }
            } finally {
                ARC.plugin = previous
            }
        }
    }

    "a provider clear failure releases every viewer and still releases suppression" {
        val previous = ARC.plugin
        ARC.plugin = mockk(relaxed = true)
        try {
            val first = mockk<Player>(relaxed = true)
            val second = mockk<Player>(relaxed = true)
            val firstId = UUID(0, 44)
            val secondId = UUID(0, 45)
            every { first.uniqueId } returns firstId
            every { second.uniqueId } returns secondId
            val provider = mockk<QuestGuidanceHudService>(relaxed = true)
            every { provider.showClaim(any(), any(), any()) } returns true
            ClaimGuideHud { provider }.use { hud ->
                hud.refresh(first, Component.text("Claim"), 0.0, false) shouldBe true
                hud.refresh(second, Component.text("Claim"), 0.0, false) shouldBe true
                every { provider.clear(firstId, QuestGuidanceHudService.Source.CLAIM) } throws
                    AbstractMethodError("clear")
                hud.clear(firstId)
                hud.hasHologram(firstId) shouldBe false
                hud.hasHologram(secondId) shouldBe false
                verify(exactly = 1) { provider.clear(secondId, QuestGuidanceHudService.Source.CLAIM) }
                verify(exactly = 2) { provider.setSuppressed(any(), firstId, any(), false) }
                verify(exactly = 2) { provider.setSuppressed(any(), secondId, any(), false) }
                hud.refresh(second, Component.text("Claim"), 0.0, false) shouldBe false
            }
        } finally {
            ARC.plugin = previous
        }
    }
})
