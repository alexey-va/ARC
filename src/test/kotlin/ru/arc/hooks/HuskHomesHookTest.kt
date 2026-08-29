package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.william278.huskhomes.event.RandomTeleportEvent
import net.william278.huskhomes.event.TeleportBackEvent
import net.william278.huskhomes.event.TeleportEvent
import net.william278.huskhomes.event.TeleportWarmupEvent
import net.william278.huskhomes.teleport.Teleport
import net.william278.huskhomes.teleport.TimedTeleport
import net.william278.huskhomes.user.BukkitUser
import org.bukkit.entity.Player
import ru.arc.KotestTestBase
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

private data class HuskTeleportFixture(
    val playerId: UUID,
    val teleport: TimedTeleport,
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
    val teleport =
        mockk<TimedTeleport> {
            every { teleporter } returns user
        }
    return HuskTeleportFixture(playerId, teleport)
}
