package ru.arc.rtp

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

class FirstRtpCoordinatorTest :
    FreeSpec({
        val player = mockk<Player>()
        val currentWorld = mockk<World>()
        val targetWorld = mockk<World>()

        every { player.world } returns currentWorld
        every { currentWorld.uid } returns UUID.randomUUID()
        every { targetWorld.uid } returns UUID.randomUUID()

        "returns an existing visitor to the world spawn without starting RTP" {
            var started = false
            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = true, hasTeleportedToWorld = true),
                start = {
                    started = true
                    FirstRtpResult.Rejected("must not run")
                },
                teleport = { true },
            ) shouldBe FirstRtpRouteResult.ReturnedToWorldSpawn
            started shouldBe false
        }

        "sets respawn only for the first network RTP" {
            var setRespawn: Boolean? = null
            val providerResult =
                FirstRtpResult.Started(
                    provider = RtpProvider.BETTERRTP,
                    command = "betterrtp player Test survival",
                )
            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = false, hasTeleportedToWorld = false),
                start = {
                    setRespawn = it
                    providerResult
                },
                teleport = { error("must not teleport") },
            ) shouldBe FirstRtpRouteResult.Started(providerResult)
            setRespawn shouldBe true
        }

        "does not replace respawn when first visiting another world" {
            var setRespawn: Boolean? = null
            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = true, hasTeleportedToWorld = false),
                start = {
                    setRespawn = it
                    FirstRtpResult.Rejected("provider unavailable")
                },
                teleport = { error("must not teleport") },
            ) shouldBe FirstRtpRouteResult.Rejected("provider unavailable")
            setRespawn shouldBe false
        }
    })
