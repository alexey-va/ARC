package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.DungeonCompleteEvent
import com.magmaguy.elitemobs.api.DungeonStartEvent
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.config.Config
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.paper.testing.MockBukkitTestRuntime

class EMDungeonQolTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "only completed exits from open dungeons release the native wormhole arrival lock" {
        withScheduler {
            val player = paper.addPlayer("exit-lock")
            val dungeon = paper.addSimpleWorld("open-lock")
            val instance = paper.addSimpleWorld("instance-lock")
            val hub = paper.addSimpleWorld("guild-lock")
            val departures = mutableListOf<org.bukkit.World>()
            val settings = config()
            val qol = EMDungeonQol(settings, { world -> when (world) {
                dungeon -> DungeonVisit("open")
                instance -> DungeonVisit("run", instanced = true)
                else -> null
            } }, leaveWormholeWorld = { who, from ->
                who shouldBe player
                departures += from
            })
            player.teleport(hub.spawnLocation)
            qol.entered(PlayerChangedWorldEvent(player, dungeon))
            qol.entered(PlayerChangedWorldEvent(player, instance))
            player.teleport(dungeon.spawnLocation)
            qol.entered(PlayerChangedWorldEvent(player, hub))
            every { settings.bool("dungeon-qol.enabled", true) } returns false
            player.teleport(hub.spawnLocation)
            qol.entered(PlayerChangedWorldEvent(player, dungeon))
            departures shouldBe listOf(dungeon)
            qol.close()
        }
    }

    "wormhole plugin entry returns to the latest open dungeon exit even after recent combat" {
        withScheduler {
            val player = paper.addPlayer("wormhole-return")
            val dungeon = paper.addSimpleWorld("open-dungeon")
            val hub = paper.addSimpleWorld("guild")
            var now = 100L
            val qol = EMDungeonQol(config(), { if (it == dungeon) DungeonVisit("open") else null }, { true }, clock = { now })
            val first = Location(dungeon, 12.5, 70.0, 4.5, 90f, 5f)
            player.teleport(first)
            qol.rememberDeparture(teleport(player, first, hub.spawnLocation))
            qol.combat(mockk<org.bukkit.event.entity.EntityDamageByEntityEvent> {
                every { damager } returns player
                every { entity } returns player
            })
            now = 200L
            val latest = Location(dungeon, 28.25, 72.0, -7.75, 125f, -15f)
            qol.rememberDeparture(teleport(player, latest, hub.spawnLocation))
            player.teleport(hub.spawnLocation)
            val entry = PlayerTeleportEvent(player, hub.spawnLocation, dungeon.spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN)
            qol.resumeOnEntry(entry)
            entry.to shouldBe latest
            qol.close()
        }
    }

    "personal return uses the exact latest open departure and consumes the portal once" {
        withScheduler {
            val player = paper.addPlayer("return-button")
            val dungeon = paper.addSimpleWorld("return-open")
            val hub = paper.addSimpleWorld("return-hub")
            player.teleport(hub.spawnLocation)
            var portal: (() -> Unit)? = null
            val moved = mutableListOf<Location>()
            val qol = EMDungeonQol(config(), { if (it == dungeon) DungeonVisit("open") else null }, { true },
                clock = { 100L }, openPortal = { _, action -> portal = action }, returnMove = { _, location -> moved += location })
            val exit = Location(dungeon, 14.25, 73.0, -9.5, 110f, -20f)
            qol.rememberDeparture(teleport(player, exit, hub.spawnLocation))
            val expected = qol.lastReturn(player)!!
            qol.returnToLast(player, expected)
            moved shouldBe emptyList()
            portal!!()
            portal!!()
            moved shouldBe listOf(exit)
            qol.close()
        }
    }

    "return rejects a replaced departure and does not admit players to instances" {
        withScheduler {
            val player = paper.addPlayer("stale-return")
            val dungeon = paper.addSimpleWorld("stale-open")
            val hub = paper.addSimpleWorld("stale-hub")
            player.teleport(hub.spawnLocation)
            var instance = false
            var now = 100L
            var portal: (() -> Unit)? = null
            val moved = mutableListOf<Location>()
            val qol = EMDungeonQol(config(), { if (it == dungeon) DungeonVisit("run", instanced = instance) else null }, { true },
                clock = { now }, openPortal = { _, action -> portal = action }, returnMove = { _, location -> moved += location })
            qol.rememberDeparture(teleport(player, Location(dungeon, 1.0, 70.0, 1.0), hub.spawnLocation))
            qol.returnToLast(player)
            now++
            qol.rememberDeparture(teleport(player, Location(dungeon, 9.0, 70.0, 9.0), hub.spawnLocation))
            portal!!()
            moved shouldBe emptyList()
            instance = true
            qol.lastReturn(player) shouldBe null
            qol.returnToLast(player)
            moved shouldBe emptyList()
            qol.close()
        }
    }

    "retargets a cross-world entry to a safe checkpoint for the current run" {
        withScheduler {
            val player = paper.addPlayer("resume")
            val dungeon = paper.addSimpleWorld("dungeon")
            val hub = paper.addSimpleWorld("hub")
            val checkpoint = Location(dungeon, 12.0, 70.0, 4.0)
            val qol = EMDungeonQol(config(), { world -> if (world == dungeon) DungeonVisit("run") else null }, { true }, clock = { 100L })
            qol.rememberDeparture(teleport(player, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            val entry = teleport(player, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 0.0, 70.0, 0.0))
            qol.resumeOnEntry(entry)
            entry.to shouldBe checkpoint
            qol.close()
        }
    }

    "native quit departure still records after membership was removed" {
        withScheduler {
            val player = paper.addPlayer("native-quit")
            val dungeon = paper.addSimpleWorld("native-quit-world")
            val hub = paper.addSimpleWorld("quit-hub")
            var members = emptySet<java.util.UUID>()
            val qol = EMDungeonQol(config(), { world -> if (world == dungeon) DungeonVisit("run", members = members) else null }, { true }, clock = { 100L })
            val point = Location(dungeon, 10.0, 70.0, 10.0)
            qol.rememberDeparture(teleport(player, point, Location(hub, 0.0, 70.0, 0.0)))
            val denied = teleport(player, Location(hub, 0.0, 70.0, 0.0), Location(dungeon, 0.0, 70.0, 0.0))
            qol.resumeOnEntry(denied)
            denied.to.x shouldBe 0.0
            members = setOf(player.uniqueId)
            val admitted = teleport(player, denied.from, denied.to)
            qol.resumeOnEntry(admitted)
            admitted.to shouldBe point
            qol.close()
        }
    }

    "does not resume same-world, unsafe, dead, or cancelled teleports" {
        withScheduler {
            val dungeon = paper.addSimpleWorld("dungeon")
            val hub = paper.addSimpleWorld("hub")
            val checkpoint = Location(dungeon, 12.0, 70.0, 4.0)
            val resolve = { world: org.bukkit.World -> if (world == dungeon) DungeonVisit("run") else null }
            val safeQol = EMDungeonQol(config(), resolve, { true }, clock = { 100L })
            val player = paper.addPlayer("guard")
            safeQol.rememberDeparture(teleport(player, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            val sameWorld = teleport(player, Location(dungeon, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0))
            safeQol.resumeOnEntry(sameWorld)
            sameWorld.to shouldBe Location(dungeon, 2.0, 70.0, 2.0)

            val unsafeQol = EMDungeonQol(config(), resolve, { false }, clock = { 100L })
            val unsafe = teleport(player, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0))
            unsafeQol.resumeOnEntry(unsafe)
            unsafe.to shouldBe Location(dungeon, 2.0, 70.0, 2.0)

            val dead = paper.addPlayer("dead")
            safeQol.rememberDeparture(teleport(dead, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            dead.teleport(checkpoint)
            dead.health = 0.0
            safeQol.cancelTravelOnDeath(mockk<PlayerDeathEvent> { every { entity } returns dead })
            val deadEntry = teleport(dead, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0))
            safeQol.resumeOnEntry(deadEntry)
            deadEntry.to shouldBe Location(dungeon, 2.0, 70.0, 2.0)

            val live = paper.addPlayer("cancelled")
            safeQol.rememberDeparture(teleport(live, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            val cancelled = teleport(live, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0)).apply { isCancelled = true }
            val plugin = paper.createSimplePlugin("DungeonQolTest")
            paper.server.pluginManager.registerEvents(safeQol, plugin)
            paper.callEvent(cancelled)
            cancelled.to shouldBe Location(dungeon, 2.0, 70.0, 2.0)
            safeQol.close()
            unsafeQol.close()
        }
    }

    "shows entry and completion UI through the core scheduler" {
        withScheduler { scheduler ->
            val player = paper.addPlayer("ui")
            val dungeon = paper.addSimpleWorld("dungeon")
            val hub = paper.addSimpleWorld("hub")
            val audience = RecordingAudience()
            val instance = mockk<DungeonInstance>()
            every { instance.world } returns dungeon
            every { instance.participants } returns linkedSetOf(player)
            every { instance.players } returns linkedSetOf(player)
            val qol = EMDungeonQol(config(titles = true), { world -> if (world == dungeon) DungeonVisit("run", waiting = true) else null }, { true }, audience, { 100L })

            player.teleport(Location(dungeon, 0.0, 70.0, 0.0))
            qol.entered(PlayerChangedWorldEvent(player, hub))
            scheduler.tick(30)
            audience.titles.size shouldBe 1
            audience.messages.size shouldBe 0

            qol.started(mockk<DungeonStartEvent> { every { dungeonInstance } returns instance })
            audience.titles.size shouldBe 2
            qol.completed(mockk<DungeonCompleteEvent> { every { dungeonInstance } returns instance })
            scheduler.tick(60)
            audience.titles.size shouldBe 3
            audience.messages.size shouldBe 1
            qol.close()
        }
    }

    "death preserves resume manual and auto points while completion clears the old run" {
        withScheduler {
            val player = paper.addPlayer("clear")
            val dungeon = paper.addSimpleWorld("dungeon")
            val hub = paper.addSimpleWorld("hub")
            val qol = EMDungeonQol(config(), { world -> if (world == dungeon) DungeonVisit("run") else null }, { true }, clock = { 100L })
            val checkpoint = Location(dungeon, 12.0, 70.0, 4.0)

            qol.rememberDeparture(teleport(player, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            player.teleport(checkpoint)
            val store = DungeonCheckpointStore()
            store.save(player.persistentDataContainer, checkpoint, "run", "Ручная", DungeonSaveKind.MANUAL, 100L, 100_000L)
            store.save(player.persistentDataContainer, checkpoint, "run", "Авто", DungeonSaveKind.AUTO, 100L, 100_000L)
            qol.cancelTravelOnDeath(mockk<PlayerDeathEvent> { every { entity } returns player })
            qol.view(player)!!.points.map { it.kind }.toSet() shouldBe setOf(DungeonSaveKind.MANUAL, DungeonSaveKind.AUTO)
            val afterDeath = teleport(player, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0))
            qol.resumeOnEntry(afterDeath)
            afterDeath.to shouldBe checkpoint

            qol.rememberDeparture(teleport(player, checkpoint, Location(hub, 1.0, 70.0, 1.0)))
            val instance = mockk<DungeonInstance>()
            every { instance.participants } returns linkedSetOf(player)
            every { instance.world } returns dungeon
            qol.completed(mockk<DungeonCompleteEvent> { every { dungeonInstance } returns instance })
            val afterComplete = teleport(player, Location(hub, 1.0, 70.0, 1.0), Location(dungeon, 2.0, 70.0, 2.0))
            qol.resumeOnEntry(afterComplete)
            afterComplete.to shouldBe Location(dungeon, 2.0, 70.0, 2.0)
            qol.close()
        }
    }
})

private fun withScheduler(block: (TestTaskScheduler) -> Unit = {}) {
    val scheduler = TestTaskScheduler()
    Tasks.withScheduler(scheduler) { block(scheduler) }
}

private fun config(titles: Boolean = false): Config = mockk<Config>(relaxed = true).also { config ->
    every { config.bool("dungeon-qol.enabled", true) } returns true
    every { config.bool("dungeon-qol.resume-enabled", true) } returns true
    every { config.bool("dungeon-qol.titles-enabled", true) } returns titles
    every { config.integer("dungeon-qol.resume-hours", 72) } returns 72
    every { config.component(any(), any<String>(), any()) } answers {
        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
    }
}

private fun teleport(player: Player, from: Location, to: Location) =
    PlayerTeleportEvent(player, from, to, PlayerTeleportEvent.TeleportCause.COMMAND)

private class RecordingAudience : PaperAudienceEffects {
    val messages = mutableListOf<Pair<Player, Component>>()
    val titles = mutableListOf<Pair<Player, Title>>()
    override fun sendMessage(player: Player, message: Component) { messages += player to message }
    override fun sendActionBar(player: Player, message: Component) = Unit
    override fun showTitle(player: Player, title: Title) { titles += player to title }
    override fun showBossBar(player: Player, bossBar: BossBar) = Unit
    override fun hideBossBar(player: Player, bossBar: BossBar) = Unit
}
