package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.instanced.MatchInstance
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.magmacore.match.Match
import com.magmaguy.elitemobs.magmacore.match.MatchCore
import com.magmaguy.elitemobs.magmacore.match.MatchPhase
import com.magmaguy.elitemobs.magmacore.match.MatchPlayer
import com.magmaguy.elitemobs.magmacore.match.MatchSettings
import com.magmaguy.elitemobs.magmacore.match.MatchSpace
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime

class EMCheckpointTeleporterTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var plugin: Plugin
    lateinit var player: Player
    lateinit var world: org.bukkit.World
    lateinit var match: DungeonInstance
    lateinit var teleporter: EMCheckpointTeleporter
    lateinit var nativeGuard: Listener
    val nativeParticipants = mutableListOf<MatchPlayer>()
    var inside: (Location) -> Boolean = { true }

    @Suppress("UNCHECKED_CAST")
    fun instances() = MatchInstance::class.java.getDeclaredField("instances").apply {
        isAccessible = true
    }.get(null) as MutableSet<MatchInstance>

    fun trackNative(target: Player) {
        val participant = object : MatchPlayer(target, match) {}
        nativeParticipants += participant
        MatchCore::class.java.getDeclaredMethod("track", MatchPlayer::class.java).apply {
            isAccessible = true
        }.invoke(null, participant)
    }

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("CheckpointTeleporter")
        player = paper.addPlayer("checkpoint")
        world = player.world
        match = mockk<DungeonInstance>()
        every { match.isCancelled } returns false
        every { match.isDefunct } returns false
        every { match.isDestroyingMatch } returns false
        every { match.state } returns MatchInstance.InstancedRegionState.ONGOING
        every { match.world } returns world
        every { match.players } returns linkedSetOf(player)
        // EliteMobs retains its facade roster; current native movement is owned by MagmaCore.
        for ((name, value) in mapOf(
            "world" to world,
            "state" to MatchInstance.InstancedRegionState.ONGOING,
            "players" to hashSetOf(player),
            "spectators" to hashSetOf<Player>(),
        )) MatchInstance::class.java.getDeclaredField(name).apply { isAccessible = true }.set(match, value)
        instances().add(match)
        inside = { true }
        nativeParticipants.clear()
        val settings = MatchSettings.builder().space(MatchSpace.region(world) { inside(it) }).build()
        for ((name, value) in mapOf(
            "settings" to settings,
            "phase" to MatchPhase.ONGOING,
            "participants" to nativeParticipants,
            "open" to true,
        )) Match::class.java.getDeclaredField(name).apply { isAccessible = true }.set(match, value)
        every { match.settings } answers { callOriginal() }
        every { match.isOpen } answers { callOriginal() }
        every { match.contains(any()) } answers { callOriginal() }
        every { match.getMatchPlayer(any()) } answers { callOriginal() }
        trackNative(player)
        nativeGuard = Class.forName("com.magmaguy.elitemobs.magmacore.match.TeleportGuard")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Listener
        paper.server.pluginManager.registerEvents(nativeGuard, plugin)
    }

    fun installTeleporter() {
        teleporter = EMCheckpointTeleporter()
        paper.server.pluginManager.registerEvents(teleporter, plugin)
    }

    afterEach {
        nativeParticipants.forEach { participant ->
            MatchCore::class.java.getDeclaredMethod("untrack", MatchPlayer::class.java).apply {
                isAccessible = true
            }.invoke(null, participant)
        }
        instances().remove(match)
        paper.close()
    }

    fun nativeAuthorizationActive(target: Player = player): Boolean =
        Class.forName("com.magmaguy.elitemobs.magmacore.match.MatchMovement")
        .getDeclaredMethod("hasAuthorization", Player::class.java).apply { isAccessible = true }
        .invoke(null, target) as Boolean

    "native authorization exists only during the owned synchronous teleport" {
        installTeleporter()
        val destination = world.location(12.0, 70.0, 12.0)
        var authorizedAtLow = false
        val observer = object : Listener {
            @EventHandler(priority = EventPriority.LOW)
            fun observeNative(event: PlayerTeleportEvent) {
                if (event.player === player) authorizedAtLow = nativeAuthorizationActive()
            }
        }
        paper.server.pluginManager.registerEvents(observer, plugin)

        teleporter.teleport(player, destination, instance = true) shouldBe true
        authorizedAtLow shouldBe true
        nativeAuthorizationActive() shouldBe false
    }

    "current native movement preserves a foreign cancellation" {
        val canceller = object : Listener {
            @EventHandler(priority = EventPriority.HIGH)
            fun cancel(event: PlayerTeleportEvent) { event.isCancelled = true }
        }
        paper.server.pluginManager.registerEvents(canceller, plugin)
        installTeleporter()

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        nativeAuthorizationActive() shouldBe false
    }

    listOf(false, true).forEach { nativeRegisteredLast ->
        "real native membership is respected with nativeRegisteredLast=$nativeRegisteredLast" {
            MatchInstance.getPlayerInstance(player) shouldBe match
            MatchCore.matchOf(player) shouldBe match
            if (nativeRegisteredLast) HandlerList.unregisterAll(nativeGuard)
            installTeleporter()
            if (nativeRegisteredLast) paper.server.pluginManager.registerEvents(nativeGuard, plugin)
            val destination = world.location(12.0, 70.0, 12.0)
            val ordinary = PlayerTeleportEvent(player, player.location, destination, PlayerTeleportEvent.TeleportCause.COMMAND)
            paper.callEvent(ordinary)
            ordinary.isCancelled shouldBe true
            teleporter.teleport(player, destination, instance = true) shouldBe true
            nativeAuthorizationActive() shouldBe false
            val subsequent = PlayerTeleportEvent(player, player.location, world.location(15.0, 70.0, 15.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
            paper.callEvent(subsequent)
            subsequent.isCancelled shouldBe true
        }
    }

    "pre-cancelled event stays cancelled and clears native authorization" {
        val canceller = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun cancel(event: PlayerTeleportEvent) { event.isCancelled = true }
        }
        paper.server.pluginManager.registerEvents(canceller, plugin)
        installTeleporter()

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        nativeAuthorizationActive() shouldBe false
    }

    "redirect at HIGHEST is preserved as cancellation" {
        val redirect = object : Listener {
            @EventHandler(priority = EventPriority.HIGH)
            fun redirect(event: PlayerTeleportEvent) { event.to = world.location(99.0, 70.0, 99.0) }
        }
        paper.server.pluginManager.registerEvents(redirect, plugin)
        installTeleporter()

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        nativeAuthorizationActive() shouldBe false
    }

    "native movement rejects a destination outside the instance" {
        installTeleporter()
        inside = { it.blockX != 12 }

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        nativeAuthorizationActive() shouldBe false
    }

    "foreign player and invalid instance state are rejected" {
        installTeleporter()
        val foreign = paper.addPlayer("foreign")
        every { match.players } returns linkedSetOf(player)
        teleporter.teleport(foreign, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false

        every { match.players } returns linkedSetOf(player)
        every { match.state } returns MatchInstance.InstancedRegionState.WAITING
        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false

        every { match.state } returns MatchInstance.InstancedRegionState.ONGOING
        every { match.isCancelled } returns true
        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
    }

    "wrong world and non-instance travel preserve native authorization scope" {
        installTeleporter()
        val other = paper.addSimpleWorld("other")
        teleporter.teleport(player, other.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = false) shouldBe true
        nativeAuthorizationActive() shouldBe false
    }

    "nested foreign teleport is cancelled while the owned transaction remains scoped" {
        var nestedCancelled = false
        val nested = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun nest(event: PlayerTeleportEvent) {
                if (event.to == world.location(12.0, 70.0, 12.0)) {
                    val nestedEvent = PlayerTeleportEvent(player, event.from, world.location(13.0, 70.0, 13.0), PlayerTeleportEvent.TeleportCause.COMMAND)
                    paper.callEvent(nestedEvent)
                    nestedCancelled = nestedEvent.isCancelled
                }
            }
        }
        installTeleporter()
        paper.server.pluginManager.registerEvents(nested, plugin)

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe true
        nestedCancelled shouldBe true
        nativeAuthorizationActive() shouldBe false
    }

    "finally clears scope when player teleport throws" {
        installTeleporter()
        val throwing = mockk<Player>()
        every { throwing.uniqueId } returns player.uniqueId
        every { throwing.world } returns world
        every { throwing.location } returns player.location
        every { throwing.gameMode } returns player.gameMode
        every { throwing.isOnline } returns true
        every { throwing.isValid } returns true
        every { throwing.teleport(any<Location>(), PlayerTeleportEvent.TeleportCause.PLUGIN) } throws IllegalStateException("boom")
        every { match.players } returns linkedSetOf(player, throwing)
        MatchInstance::class.java.getDeclaredField("players").apply { isAccessible = true }
            .set(match, hashSetOf(player, throwing))
        trackNative(throwing)

        runCatching { teleporter.teleport(throwing, world.location(12.0, 70.0, 12.0), instance = true) }.isFailure shouldBe true
        nativeAuthorizationActive(throwing) shouldBe false
    }
})

private fun org.bukkit.World.location(x: Double, y: Double, z: Double) = Location(this, x, y, z)
