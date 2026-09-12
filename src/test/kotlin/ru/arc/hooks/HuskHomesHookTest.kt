package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.william278.huskhomes.api.HuskHomesAPI
import net.william278.huskhomes.event.RandomTeleportEvent
import net.william278.huskhomes.event.TeleportBackEvent
import net.william278.huskhomes.event.TeleportEvent
import net.william278.huskhomes.event.TeleportWarmupEvent
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.teleport.Target
import net.william278.huskhomes.teleport.Teleport
import net.william278.huskhomes.teleport.TeleportBuilder
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import net.william278.huskhomes.util.TransactionResolver
import org.bukkit.entity.Player
import ru.arc.KotestTestBase
import ru.arc.common.ServerLocation
import java.util.UUID

class HuskHomesHookTest :
    FreeSpec({
        "HuskHomes portal interception" - {
            "opens the ARC portal when HuskHomes skips its warmup" {
                val fixture = teleportFixture(hasPortalBypass = false)
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                val event = TeleportEvent(fixture.teleport)

                hook.husk(event)

                event.isCancelled shouldBe true
                opened.single().first shouldBe fixture.playerId
                opened.single().second.teleport shouldBe fixture.teleport
                opened.single().second.departure shouldBe fixture.departure
            }

            "keeps opening the portal at warmup start" {
                val fixture = teleportFixture(hasPortalBypass = false)
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                val event = TeleportWarmupEvent(fixture.teleport, 1)

                hook.husk(event)

                event.isCancelled shouldBe true
                opened.single().first shouldBe fixture.playerId
                opened.single().second.teleport shouldBe fixture.teleport
            }

            "uses only the ARC portal bypass for both HuskHomes paths" {
                val fixture = teleportFixture(hasPortalBypass = true)
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                val warmupEvent = TeleportWarmupEvent(fixture.teleport, 1)
                val directEvent = TeleportEvent(fixture.teleport)

                hook.husk(warmupEvent)
                hook.husk(directEvent)

                warmupEvent.isCancelled shouldBe false
                directEvent.isCancelled shouldBe false
                opened.shouldBeEmpty()
            }

            "allows ARC's immediate replay and cross-server completion through" {
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                val event = TeleportEvent(mockk<Teleport>())

                hook.husk(event)

                event.isCancelled shouldBe false
                opened.shouldBeEmpty()
            }
        }
    })

class HuskHomesHookRegistrationTest :
    KotestTestBase({
        describe("HuskHomes specialized teleport events") {
            it("intercepts a warmup-bypassed back teleport through Bukkit dispatch") {
                val fixture = teleportFixture(hasPortalBypass = false)
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                server.pluginManager.registerEvents(hook, plugin)
                val event = TeleportBackEvent(fixture.teleport)

                server.pluginManager.callEvent(event)

                event.isCancelled shouldBe true
                opened.single().second.teleport shouldBe fixture.teleport
            }

            it("intercepts a warmup-bypassed random teleport through Bukkit dispatch") {
                val fixture = teleportFixture(hasPortalBypass = false)
                val opened = mutableListOf<Pair<UUID, HuskHomesHook.HuskTeleport>>()
                val hook = HuskHomesHook { playerId, teleport -> opened += playerId to teleport }
                server.pluginManager.registerEvents(hook, plugin)
                val event = RandomTeleportEvent(fixture.teleport)

                server.pluginManager.callEvent(event)

                event.isCancelled shouldBe true
                opened.single().second.teleport shouldBe fixture.teleport
            }
        }
    })

class HuskHomesHookDestinationTest :
    FreeSpec({
        "rejects an invalid configured destination before touching HuskHomes" {
            val hook = HuskHomesHook { _, _ -> }
            val player = mockk<Player>()

            hook.teleport(player, ServerLocation(server = "", world = "em_adventurers_guild", x = 292.5, y = 78.0, z = 267.5)) shouldBe false
            hook.teleport(player, ServerLocation(server = "spawn", world = "em_adventurers_guild", x = Double.NaN, y = 78.0, z = 267.5)) shouldBe false
        }
    })

class HuskHomesReplayTest :
    FreeSpec({
        "replays the original HuskHomes semantics and records the command departure" {
            mockkStatic(HuskHomesAPI::class)
            try {
                val api = mockk<HuskHomesAPI>()
                val playerId = UUID.randomUUID()
                val player =
                    mockk<Player> {
                        every { uniqueId } returns playerId
                    }
                val user =
                    mockk<BukkitUser> {
                        every { uuid } returns playerId
                    }
                val departure = mockk<Position>()
                val target = mockk<Target>()
                val original =
                    mockk<TimedTeleport> {
                        every { teleporter } returns user
                        every { this@mockk.target } returns target
                        every { type } returns Teleport.Type.BACK
                        every { actions } returns listOf(TransactionResolver.Action.BACK_COMMAND)
                        every { isUpdateLastPosition } returns true
                    }
                val builder = mockk<TeleportBuilder>()
                val replay = mockk<Teleport>()
                every { HuskHomesAPI.getInstance() } returns api
                every { api.adaptUser(player) } returns user
                every { api.teleportBuilder(user) } returns builder
                every { builder.target(target) } returns builder
                every { builder.type(Teleport.Type.BACK) } returns builder
                every { builder.actions(*anyVararg()) } returns builder
                every { builder.updateLastPosition(false) } returns builder
                every { builder.toTeleport() } returns replay
                justRun { replay.execute() }
                justRun { api.setUserLastPosition(user, departure) }

                HuskHomesHook { _, _ -> }.teleport(
                    HuskHomesHook.HuskTeleport(original, departure),
                    player,
                )

                verify(exactly = 1) { builder.target(target) }
                verify(exactly = 1) { builder.type(Teleport.Type.BACK) }
                verify(exactly = 1) { builder.actions(TransactionResolver.Action.BACK_COMMAND) }
                verify(exactly = 1) { builder.updateLastPosition(false) }
                verify(exactly = 1) { replay.execute() }
                verify(exactly = 1) { api.setUserLastPosition(user, departure) }
            } finally {
                unmockkStatic(HuskHomesAPI::class)
            }
        }
    })

private data class HuskTeleportFixture(
    val playerId: UUID,
    val teleport: TimedTeleport,
    val departure: Position,
)

private fun teleportFixture(hasPortalBypass: Boolean): HuskTeleportFixture {
    val playerId = UUID.randomUUID()
    val player =
        mockk<Player> {
            every { hasPermission("arc.portal.bypass") } returns hasPortalBypass
        }
    val user =
        mockk<BukkitUser> {
            every { uuid } returns playerId
            every { this@mockk.player } returns player
        }
    val departure = mockk<Position>()
    every { user.position } returns departure
    val teleport =
        mockk<TimedTeleport> {
            every { teleporter } returns user
        }
    return HuskTeleportFixture(playerId, teleport, departure)
}
