package ru.arc.xserver

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType.BLINDNESS
import ru.arc.rtp.NetworkRtpRequest

class PluginMessengerWorldResolutionTest :
    FreeSpec({
        "resolves a bare proxy request to the carrier current world" {
            resolveNetworkRtpWorld(NetworkRtpRequest.CURRENT_WORLD, "Vanilla") shouldBe "vanilla"
        }

        "preserves an explicitly requested world" {
            resolveNetworkRtpWorld("mining", "vanilla") shouldBe "mining"
        }

        "reapplies blindness on the target backend after a server transfer" {
            val player = mockk<Player>()
            val effect = slot<PotionEffect>()
            every { player.addPotionEffect(capture(effect)) } returns true

            applyNetworkTransferBlindness(player, enabled = true, durationTicks = 40) shouldBe true

            effect.captured.type shouldBe BLINDNESS
            effect.captured.duration shouldBe 40
            effect.captured.amplifier shouldBe 0
        }

        "does not add a target effect when transfer blindness is disabled" {
            val player = mockk<Player>(relaxed = true)

            applyNetworkTransferBlindness(player, enabled = false, durationTicks = 40) shouldBe false

            verify(exactly = 0) { player.addPotionEffect(any()) }
        }
    })
