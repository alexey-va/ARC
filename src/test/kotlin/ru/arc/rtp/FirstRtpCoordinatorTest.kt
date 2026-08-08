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

        "returns an existing visitor to the requested world without starting RTP" {
            var started = false
            var returned = false
            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = true, hasTeleportedToWorld = true),
                start = {
                    started = true
                    FirstRtpResult.Rejected("must not run")
                },
                returnToWorld = {
                    returned = true
                    true
                },
            ) shouldBe FirstRtpRouteResult.ReturnedToWorld
            started shouldBe false
            returned shouldBe true
        }

        "keeps an existing visitor at the current location in the requested world" {
            every { player.world } returns targetWorld
            var returned = false

            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = true, hasTeleportedToWorld = true),
                start = { error("must not start") },
                returnToWorld = {
                    returned = true
                    true
                },
            ) shouldBe FirstRtpRouteResult.ReturnedToWorld
            returned shouldBe false
        }

        "rejects an existing visitor when returning to the requested world fails" {
            every { player.world } returns currentWorld

            FirstRtpCoordinator.route(
                player = player,
                world = targetWorld,
                state = PlayerRtpState(hasTeleported = true, hasTeleportedToWorld = true),
                start = { error("must not start") },
                returnToWorld = { false },
            ) shouldBe FirstRtpRouteResult.Rejected("не удалось вернуть игрока в мир")
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
                returnToWorld = { error("must not return") },
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
                returnToWorld = { error("must not return") },
            ) shouldBe FirstRtpRouteResult.Rejected("provider unavailable")
            setRespawn shouldBe false
        }
    })
