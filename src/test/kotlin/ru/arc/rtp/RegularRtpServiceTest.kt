package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.entity.Player

class RegularRtpServiceTest :
    FreeSpec({
        val player = mockk<Player>()
        val world = mockk<World>()
        every { player.hasPermission(any<String>()) } returns false

        "preserves the BetterRTP player command with provider cooldowns" {
            every { world.name } returns "mining"
            RegularRtpService.buildCommand(RtpProvider.BETTERRTP, player, world) shouldBe
                "betterrtp world mining"
        }

        "uses the permanent LeafRTP region for a regular player request" {
            every { world.name } returns "vanilla"
            RegularRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp region:vanilla"
        }
    })
