package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.config.TestConfig
import ru.arc.core.TestTaskScheduler
import java.util.UUID

/**
 * Tests orchestration at the Paper time API seam. MockBukkit 4.116.3 swaps the
 * getPlayerTime/getPlayerTimeOffset semantics, so it cannot verify this contract.
 */
class HuntEventAmbienceTest : DescribeSpec({
    it("uses the configured night only in the active world and restores after its last hunt") {
        val h = AmbienceHarness()
        val relative = h.player(h.spawn, offset = 2400L, relative = true)
        val absolute = h.player(h.spawn, offset = 6000L, relative = false)
        val outsider = h.player(h.other, offset = 8000L, relative = true)
        val first = hunt(h.spawn)
        val second = hunt(h.spawn)
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.other)))
            h.scheduler.timerCount() shouldBe 0
            ambience.onActiveHuntsChanged(listOf(first, second, hunt(h.other)))

            relative.time() shouldBe (18000L to false)
            absolute.time() shouldBe (18000L to false)
            outsider.time() shouldBe (8000L to true)
            h.started shouldBe listOf(h.spawn.uid)
            h.scheduler.timerCount() shouldBe 1
            ambience.onActiveHuntsChanged(listOf(second))
            h.scheduler.tick(40)
            relative.time() shouldBe (18000L to false)
            h.started.size shouldBe 1
            h.leases.single().closes shouldBe 0

            ambience.onActiveHuntsChanged(emptyList())

            relative.time() shouldBe (2400L to true)
            absolute.time() shouldBe (6000L to false)
            h.leases.single().closes shouldBe 1
            h.scheduler.timerCount() shouldBe 0
            verify(exactly = 0) { h.spawn.setTime(any()) }
            verify(exactly = 0) { h.spawn.setFullTime(any()) }
        }
    }

    it("applies night to later joins and restores time on world exit without retaining offline sessions") {
        val h = AmbienceHarness()
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
            val player = h.player(h.spawn, offset = 1200L, relative = true)
            h.scheduler.tick(20)
            player.time() shouldBe (18000L to false)

            player.world = h.other
            h.scheduler.tick(20)
            player.time() shouldBe (1200L to true)
            player.world = h.spawn
            h.scheduler.tick(20)
            player.time() shouldBe (18000L to false)

            player.online = false
            h.scheduler.tick(20)
            val rejoined = h.player(h.spawn, offset = 3000L, relative = false, id = player.id)
            h.scheduler.tick(20)
            rejoined.time() shouldBe (18000L to false)
            ambience.onActiveHuntsChanged(emptyList())
            rejoined.time() shouldBe (3000L to false)
        }
    }

    it("captures a new player session even when reconnect happens between timer ticks") {
        val h = AmbienceHarness()
        val original = h.player(h.spawn, offset = 1200L, relative = true)
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
            original.online = false
            val rejoined = h.player(h.spawn, offset = 5000L, relative = false, id = original.id)
            h.scheduler.tick(20)
            rejoined.time() shouldBe (18000L to false)
            ambience.onActiveHuntsChanged(emptyList())
            rejoined.time() shouldBe (5000L to false)
        }
    }

    it("reloads time and world settings while preserving the original personal times") {
        val h = AmbienceHarness()
        val spawnPlayer = h.player(h.spawn, offset = 1700L, relative = true)
        val otherPlayer = h.player(h.other, offset = 6500L, relative = false)
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn), hunt(h.other)))
            h.settings = h.settings.copy(time = 16000L)
            ambience.refresh()
            spawnPlayer.time() shouldBe (16000L to false)
            h.leases.first().closes shouldBe 1
            h.scheduler.timerCount() shouldBe 1

            h.settings = h.settings.copy(world = h.other.name)
            ambience.refresh()
            spawnPlayer.time() shouldBe (1700L to true)
            otherPlayer.time() shouldBe (16000L to false)
            h.started.last() shouldBe h.other.uid

            h.settings = h.settings.copy(enabled = false)
            ambience.refresh()
            otherPlayer.time() shouldBe (6500L to false)
            h.leases.all { it.closes == 1 } shouldBe true
            h.scheduler.timerCount() shouldBe 0
        }
    }

    it("preserves an override made by another owner during the hunt, including across reload and stop") {
        val h = AmbienceHarness()
        val player = h.player(h.spawn, offset = 1500L, relative = true)
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
            player.player.setPlayerTime(4000L, true)
            h.scheduler.tick(20)
            player.time() shouldBe (4000L to true)
            h.settings = h.settings.copy(time = 16000L)
            ambience.refresh()
            player.time() shouldBe (4000L to true)
            ambience.onActiveHuntsChanged(emptyList())
            player.time() shouldBe (4000L to true)
        }
    }

    it("closes its lease and timer once and cannot restart after shutdown") {
        val h = AmbienceHarness()
        val player = h.player(h.spawn, offset = 9000L, relative = false)
        h.ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
        h.ambience.close()
        h.ambience.close()
        h.ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
        h.ambience.refresh()
        h.scheduler.tick(40)

        player.time() shouldBe (9000L to false)
        h.scheduler.timerCount() shouldBe 0
        h.leases.single().closes shouldBe 1
        h.started.size shouldBe 1
    }

    it("quietly retries an absent provider and contains a provider failure until refresh") {
        val h = AmbienceHarness()
        val player = h.player(h.spawn, offset = 0L, relative = true)
        h.providerAvailable = false
        h.ambience.use { ambience ->
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
            h.scheduler.tick(20)
            h.leases.size shouldBe 0
            player.time() shouldBe (18000L to false)

            h.providerAvailable = true
            h.startFailure = IllegalStateException("provider stopped")
            h.scheduler.tick(20)
            val attemptsAfterFailure = h.started.size
            h.scheduler.tick(60)
            ambience.onActiveHuntsChanged(listOf(hunt(h.spawn)))
            h.started.size shouldBe attemptsAfterFailure
            h.startFailure = null
            ambience.refresh()
            h.leases.size shouldBe 1
        }
    }

    it("loads enabled spawn night defaults and normalizes configured time within one day") {
        HuntEventAmbienceSettings.load(TestConfig().section("ambience")) shouldBe HuntEventAmbienceSettings()
        val config = TestConfig(mapOf("ambience.world" to " other ", "ambience.time" to 42000L))
        HuntEventAmbienceSettings.load(config.section("ambience")) shouldBe
            HuntEventAmbienceSettings(world = "other", time = 18000L)
    }
})

private class AmbienceHarness {
    val spawn = ambienceWorld("rc_origin_spawn")
    val other = ambienceWorld("other")
    val scheduler = TestTaskScheduler()
    var settings = HuntEventAmbienceSettings()
    var providerAvailable = true
    var startFailure: Exception? = null
    val started = mutableListOf<UUID>()
    val leases = mutableListOf<AmbienceLease>()
    private val players = mutableListOf<AmbiencePlayer>()
    val ambience = HuntEventAmbience(
        scheduler,
        { settings },
        { players.map { it.player } },
        { worldId ->
            started += worldId
            startFailure?.let { throw it }
            if (providerAvailable) AmbienceLease().also { leases += it } else null
        },
    )

    fun player(world: World, offset: Long, relative: Boolean, id: UUID = UUID.randomUUID()): AmbiencePlayer =
        AmbiencePlayer(id, players.size + 1, world, offset, relative).also { players += it }
}

private class AmbiencePlayer(
    val id: UUID,
    entityId: Int,
    var world: World,
    private var offset: Long,
    private var relative: Boolean,
) {
    var online = true
    val player = mockk<Player>().also { player ->
        every { player.uniqueId } returns id
        every { player.entityId } returns entityId
        every { player.world } answers { world }
        every { player.isOnline } answers { online }
        every { player.playerTimeOffset } answers { offset }
        every { player.isPlayerTimeRelative } answers { relative }
        every { player.setPlayerTime(any(), any()) } answers {
            offset = firstArg()
            relative = secondArg()
        }
    }

    fun time(): Pair<Long, Boolean> = offset to relative
}

private class AmbienceLease : AutoCloseable {
    var closes = 0
    override fun close() {
        closes++
    }
}

private fun ambienceWorld(name: String): World = mockk<World>().also { world ->
    every { world.name } returns name
    every { world.uid } returns UUID.randomUUID()
}

private fun hunt(world: World): ActiveHunt = mockk<ActiveHunt>().also { hunt ->
    every { hunt.world } returns world
}
