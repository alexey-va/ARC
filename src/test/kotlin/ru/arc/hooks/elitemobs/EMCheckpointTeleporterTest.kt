package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.instanced.MatchInstance
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
    lateinit var nativeGuard: MatchInstance.MatchInstanceEvents

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        plugin = paper.createSimplePlugin("CheckpointTeleporter")
        player = paper.addPlayer("checkpoint")
        world = player.world
        match = mockk<DungeonInstance>()
        every { match.isCancelled } returns false
        every { match.state } returns MatchInstance.InstancedRegionState.ONGOING
        every { match.world } returns world
        every { match.players } returns linkedSetOf(player)
        mockkStatic(MatchInstance::class)
        every { MatchInstance.getPlayerInstance(player) } returns match
        nativeGuard = MatchInstance.MatchInstanceEvents()
        paper.server.pluginManager.registerEvents(nativeGuard, plugin)
    }

    fun installTeleporter() {
        teleporter = EMCheckpointTeleporter()
        paper.server.pluginManager.registerEvents(teleporter, plugin)
    }

    afterEach {
        MatchInstance.MatchInstanceEvents.teleportBypass = false
        unmockkStatic(MatchInstance::class)
        paper.close()
    }

    "native LOW consumes scoped bypass and cleanup restores global state" {
        installTeleporter()
        val destination = world.location(12.0, 70.0, 12.0)
        var armedAtLowest = false
        var clearedAfterNative = false
        val observer = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun observeArm(event: PlayerTeleportEvent) {
                if (event.player === player) armedAtLowest = MatchInstance.MatchInstanceEvents.teleportBypass
            }

            @EventHandler(priority = EventPriority.LOW)
            fun observeNative(event: PlayerTeleportEvent) {
                if (event.player === player) clearedAfterNative = !MatchInstance.MatchInstanceEvents.teleportBypass
            }
        }
        paper.server.pluginManager.registerEvents(observer, plugin)

        teleporter.teleport(player, destination, instance = true) shouldBe true
        armedAtLowest shouldBe true
        clearedAfterNative shouldBe true
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
    }

    listOf(false, true).forEach { nativeRegisteredLast ->
        "real native membership is respected with nativeRegisteredLast=$nativeRegisteredLast" {
            // Native guards read fields and the static instance set directly, not mocked getters.
            fun setField(name: String, value: Any) = MatchInstance::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.set(match, value)
            setField("world", world)
            setField("players", hashSetOf(player))
            setField("spectators", hashSetOf<Player>())
            @Suppress("UNCHECKED_CAST")
            val instances = MatchInstance::class.java.getDeclaredField("instances").apply {
                isAccessible = true
            }.get(null) as MutableSet<MatchInstance>
            instances.add(match)
            try {
                if (nativeRegisteredLast) HandlerList.unregisterAll(nativeGuard)
                installTeleporter()
                if (nativeRegisteredLast) paper.server.pluginManager.registerEvents(nativeGuard, plugin)
                val destination = world.location(12.0, 70.0, 12.0)
                val ordinary = PlayerTeleportEvent(player, player.location, destination, PlayerTeleportEvent.TeleportCause.COMMAND)
                paper.callEvent(ordinary)
                ordinary.isCancelled shouldBe true
                teleporter.teleport(player, destination, instance = true) shouldBe true
                MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
                val subsequent = PlayerTeleportEvent(player, player.location, world.location(15.0, 70.0, 15.0), PlayerTeleportEvent.TeleportCause.PLUGIN)
                paper.callEvent(subsequent)
                subsequent.isCancelled shouldBe true
            } finally { instances.remove(match) }
        }
    }

    "pre-cancelled event stays cancelled and never arms native bypass" {
        val canceller = object : Listener {
            @EventHandler(priority = EventPriority.LOWEST)
            fun cancel(event: PlayerTeleportEvent) { event.isCancelled = true }
        }
        paper.server.pluginManager.registerEvents(canceller, plugin)
        installTeleporter()

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
    }

    "redirect at HIGHEST is preserved as cancellation" {
        val redirect = object : Listener {
            @EventHandler(priority = EventPriority.HIGH)
            fun redirect(event: PlayerTeleportEvent) { event.to = world.location(99.0, 70.0, 99.0) }
        }
        paper.server.pluginManager.registerEvents(redirect, plugin)
        installTeleporter()

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
    }

    "existing native global flag fails closed without clearing it" {
        installTeleporter()
        MatchInstance.MatchInstanceEvents.teleportBypass = true

        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe true
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

    "wrong world and non-instance travel are handled without bypass" {
        installTeleporter()
        val other = paper.addSimpleWorld("other")
        teleporter.teleport(player, other.location(12.0, 70.0, 12.0), instance = true) shouldBe false
        teleporter.teleport(player, world.location(12.0, 70.0, 12.0), instance = false) shouldBe true
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
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
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
    }

    "finally clears scope when player teleport throws" {
        installTeleporter()
        val throwing = mockk<Player>()
        every { throwing.uniqueId } returns player.uniqueId
        every { throwing.world } returns world
        every { throwing.teleport(any<Location>(), PlayerTeleportEvent.TeleportCause.PLUGIN) } throws IllegalStateException("boom")
        every { MatchInstance.getPlayerInstance(throwing) } returns match
        every { match.players } returns linkedSetOf(player, throwing)

        runCatching { teleporter.teleport(throwing, world.location(12.0, 70.0, 12.0), instance = true) }.isFailure shouldBe true
        MatchInstance.MatchInstanceEvents.teleportBypass shouldBe false
    }
})

private fun org.bukkit.World.location(x: Double, y: Double, z: Double) = Location(this, x, y, z)
