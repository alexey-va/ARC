package ru.arc.rtp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.bukkit.World
import org.bukkit.entity.Player
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class FirstRtpServiceTest :
    FunSpec({
        lateinit var player: Player
        lateinit var world: World

        beforeTest {
            RtpRespawnTracker.pending.invalidateAll()
            player = mock()
            world = mock()
            whenever(player.name).thenReturn("Steve")
            whenever(player.uniqueId).thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            whenever(world.name).thenReturn("survival")
        }

        test("builds the legacy BetterRTP command exactly") {
            FirstRtpService.buildCommand(RtpProvider.BETTERRTP, player, world) shouldBe
                "betterrtp player Steve survival NODELAY NOCOOLDOWN IGNORECOOLDOWN"
        }

        test("selects VIP LeafRTP region before newbie") {
            whenever(player.hasPermission("rtp.regions.vip")).thenReturn(true)
            whenever(player.hasPermission("rtp.regions.newbie")).thenReturn(true)

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve region=vip"
        }

        test("selects newbie LeafRTP region") {
            whenever(player.hasPermission("rtp.regions.vip")).thenReturn(false)
            whenever(player.hasPermission("rtp.regions.newbie")).thenReturn(true)

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve region=newbie"
        }

        test("selects the base survival region without creating a world override") {
            whenever(player.hasPermission("rtp.regions.vip")).thenReturn(false)
            whenever(player.hasPermission("rtp.regions.newbie")).thenReturn(false)

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve region=survival"
        }

        test("selects the explicit mining region") {
            whenever(world.name).thenReturn("mining")

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve region=mining"
        }

        test("selects the explicit vanilla region") {
            whenever(world.name).thenReturn("vanilla")

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve region=vanilla"
        }

        test("falls back to a world selector for an unconfigured world") {
            whenever(world.name).thenReturn("event_world")

            FirstRtpService.buildCommand(RtpProvider.LEAFRTP, player, world) shouldBe
                "rtp player=Steve world=event_world"
        }

        test("rejects a disabled provider without creating a pending respawn") {
            val result =
                FirstRtpService.start(
                    provider = RtpProvider.LEAFRTP,
                    player = player,
                    world = world,
                    setRespawn = true,
                    isPluginEnabled = { false },
                    dispatch = { error("must not dispatch") },
                )

            result shouldBe FirstRtpResult.Rejected("плагин RTP не включён")
            RtpRespawnTracker.consume("Steve", RtpProvider.LEAFRTP).shouldBeFalse()
        }

        test("cancels pending respawn when provider rejects the command") {
            val result =
                FirstRtpService.start(
                    provider = RtpProvider.BETTERRTP,
                    player = player,
                    world = world,
                    setRespawn = true,
                    isPluginEnabled = { true },
                    dispatch = { false },
                )

            result shouldBe FirstRtpResult.Rejected("RTP provider отклонил команду")
            RtpRespawnTracker.consume("Steve", RtpProvider.BETTERRTP).shouldBeFalse()
        }

        test("keeps one pending respawn after successful dispatch") {
            val result =
                FirstRtpService.start(
                    provider = RtpProvider.BETTERRTP,
                    player = player,
                    world = world,
                    setRespawn = true,
                    isPluginEnabled = { true },
                    dispatch = {
                        it shouldBe "betterrtp player Steve survival NODELAY NOCOOLDOWN IGNORECOOLDOWN"
                        true
                    },
                )

            result shouldBe
                FirstRtpResult.Started(
                    RtpProvider.BETTERRTP,
                    "betterrtp player Steve survival NODELAY NOCOOLDOWN IGNORECOOLDOWN",
                )
            RtpRespawnTracker.consume("Steve", RtpProvider.LEAFRTP).shouldBeFalse()
            RtpRespawnTracker.consume("Steve", RtpProvider.BETTERRTP).shouldBeTrue()
            RtpRespawnTracker.consume("Steve", RtpProvider.BETTERRTP).shouldBeFalse()
        }

        test("rejects a duplicate first RTP while provider completion is pending") {
            RtpRespawnTracker.mark(
                playerName = "Steve",
                provider = RtpProvider.BETTERRTP,
                setRespawn = true,
                persistPlayerId = player.uniqueId,
                persistWorldName = "survival",
            )
            var dispatched = false

            val result =
                FirstRtpService.start(
                    provider = RtpProvider.BETTERRTP,
                    player = player,
                    world = world,
                    setRespawn = true,
                    persist = true,
                    isPluginEnabled = { true },
                    dispatch = {
                        dispatched = true
                        true
                    },
                )

            result shouldBe FirstRtpResult.Rejected("предыдущий запрос RTP ещё выполняется")
            dispatched.shouldBeFalse()
            RtpRespawnTracker.hasPending("Steve").shouldBeTrue()
        }

        test("does not mark respawn for a first visit to an additional world") {
            FirstRtpService.start(
                provider = RtpProvider.BETTERRTP,
                player = player,
                world = world,
                setRespawn = false,
                isPluginEnabled = { true },
                dispatch = { true },
            )

            RtpRespawnTracker.consume("Steve", RtpProvider.BETTERRTP).shouldBeFalse()
        }

        test("keeps persistence pending until a successful provider completion") {
            FirstRtpService.start(
                provider = RtpProvider.BETTERRTP,
                player = player,
                world = world,
                setRespawn = false,
                persist = true,
                isPluginEnabled = { true },
                dispatch = { true },
            )

            val request = RtpRespawnTracker.take("Steve", RtpProvider.BETTERRTP)
            request?.setRespawn shouldBe false
            request?.persistPlayerId shouldBe player.uniqueId
            request?.persistWorldName shouldBe "survival"
        }
    })
