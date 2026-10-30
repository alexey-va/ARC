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
                "rtp region=vanilla"
        }

        "a transferred player whose saved Nether world denies rtp.use can use the permitted destination" {
            every { world.name } returns "vanilla"
            var apiCalls = 0
            var commandCalls = 0
            val result =
                RegularRtpService.start(
                    RtpProvider.LEAFRTP, player, world, serverTransfer = true,
                    destinationAllowed = { true },
                    startLeafTransfer = { apiCalls++; FirstRtpResult.Started(RtpProvider.LEAFRTP, "api") },
                    dispatch = { commandCalls++; false },
                )

            result shouldBe FirstRtpResult.Started(RtpProvider.LEAFRTP, "api")
            apiCalls shouldBe 1
            commandCalls shouldBe 0
        }

        "does not bypass the direct RTP restriction on the same server" {
            every { world.name } returns "vanilla"
            var apiCalls = 0
            val result =
                RegularRtpService.start(
                    RtpProvider.LEAFRTP, player, world, serverTransfer = false,
                    destinationAllowed = { true },
                    startLeafTransfer = { apiCalls++; FirstRtpResult.Started(RtpProvider.LEAFRTP, "api") },
                    dispatch = { false },
                )

            result shouldBe FirstRtpResult.Rejected("RTP provider отклонил команду")
            apiCalls shouldBe 0
        }

        "does not start a transferred RTP when the destination permission is denied" {
            every { world.name } returns "vanilla"
            var apiCalls = 0
            var commandCalls = 0
            val result =
                RegularRtpService.start(
                    RtpProvider.LEAFRTP, player, world, serverTransfer = true,
                    destinationAllowed = { false },
                    startLeafTransfer = { apiCalls++; FirstRtpResult.Started(RtpProvider.LEAFRTP, "api") },
                    dispatch = { commandCalls++; true },
                )

            result shouldBe FirstRtpResult.Rejected("нет доступа к выбранному направлению")
            apiCalls shouldBe 0
            commandCalls shouldBe 0
        }

        "uses the normal player command when the player already has rtp.use" {
            every { world.name } returns "vanilla"
            every { player.hasPermission("rtp.use") } returns true
            var apiCalls = 0
            val commands = mutableListOf<String>()
            val result =
                RegularRtpService.start(
                    RtpProvider.LEAFRTP, player, world, serverTransfer = true,
                    destinationAllowed = { error("must not look up destination permissions") },
                    startLeafTransfer = { apiCalls++; FirstRtpResult.Started(RtpProvider.LEAFRTP, "api") },
                    dispatch = { commands.add(it); true },
                )

            result shouldBe FirstRtpResult.Started(RtpProvider.LEAFRTP, "rtp region=vanilla")
            apiCalls shouldBe 0
            commands shouldBe listOf("rtp region=vanilla")
        }

        "checks the provider's selector permissions for managed transfers" {
            every { world.name } returns "event_world"
            every { player.hasPermission("rtp.region") } returns true
            every { player.hasPermission("rtp.world") } returns true
            every { player.hasPermission("rtp.worlds.event_world") } returns false
            LeafRtpTransfer.selectorAllowed(player, world, "vanilla") shouldBe true
            LeafRtpTransfer.selectorAllowed(player, world, null) shouldBe false
            every { player.hasPermission("rtp.worlds.event_world") } returns true
            LeafRtpTransfer.selectorAllowed(player, world, null) shouldBe true
        }
    })
