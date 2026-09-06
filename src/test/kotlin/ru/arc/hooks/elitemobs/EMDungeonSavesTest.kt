package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.config.Config
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.audience.PaperAudienceEffects
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.testing.MockBukkitTestRuntime

class EMDungeonSavesTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "read-only preflight agrees with save for unsafe flight combat cooldown and pending travel" {
        withScheduler {
            val player = paper.addPlayer("preflight")
            val world = paper.addSimpleWorld("preflight-world")
            var now = 100_000L
            var safe = false
            val service = qol(world, player, safe = { safe }, clock = { now })
            val expected = service.view(player)!!
            fun denied() {
                val reason = service.saveBlockReason(player, expected)
                (reason != null) shouldBe true
                service.save(player, "test", expected).message shouldBe reason
            }
            denied()
            safe = true
            service.saveBlockReason(player, expected) shouldBe null
            player.allowFlight = true
            player.isFlying = true
            denied()
            player.isFlying = false
            service.combat(mockk<EntityDamageByEntityEvent> { every { entity } returns player; every { damager } returns player })
            denied()
            now += 15_001
            service.save(player, "test", expected).success shouldBe true
            denied()
            now += 5_001
            service.saveBlockReason(player, expected) shouldBe null
            service.travel(player, expected, "entry")
            denied()
            service.close()
        }
    }

    "a root action cannot act on a replacement run" {
        withScheduler {
            val player = paper.addPlayer("old-panel")
            val world = paper.addSimpleWorld("panel-world")
            var run = "before"
            val audience = RecordingSavesAudience()
            val service = qol(world, player, audience = audience, runProvider = { run })
            val expected = service.panelView(player)!!
            run = "after"
            service.panelAction(player, expected, "quit")
            audience.messages.size shouldBe 1
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(audience.messages.single()).contains("изменились") shouldBe true
            player.world shouldBe world
            service.close()
        }
    }

    "manual save persists, overwrites position, enforces cooldown and full limit" {
        withScheduler { _ ->
            val player = paper.addPlayer("saver")
            val world = paper.addSimpleWorld("save-world")
            var now = 1_000L
            var persisted = 0
            val qol = qol(world, player, clock = { now }, persistence = { persisted++ })
            val expected = qol.view(player)!!
            qol.save(player, "alpha", expected).success shouldBe true
            qol.save(player, "alpha", expected).success shouldBe false
            now += 5_000L
            player.teleport(Location(world, 9.0, 70.0, 9.0))
            qol.save(player, "alpha", qol.view(player)!!).success shouldBe true
            qol.view(player)!!.points.single { it.name == "alpha" }.location.x shouldBe 9.0
            persisted shouldBe 2
            now += 5_000L
            repeat(4) { index ->
                now += 5_000L
                qol.save(player, "p$index", qol.view(player)!!).success shouldBe true
            }
            now += 5_000L
            qol.save(player, "overflow", qol.view(player)!!).success shouldBe false
            qol.close()
        }
    }

    "failed manual save rolls back the point and does not consume cooldown" {
        withScheduler {
            val player = paper.addPlayer("failed-save")
            val world = paper.addSimpleWorld("failed-save-world")
            var fail = true
            val qol = qol(world, player, persistence = { if (fail) error("disk") })
            val expected = qol.view(player)!!

            qol.save(player, "phantom", expected).success shouldBe false
            qol.view(player)!!.points shouldBe emptyList()
            fail = false
            qol.save(player, "real", qol.view(player)!!).success shouldBe true
            qol.close()
        }
    }

    "failed remove restores the point" {
        withScheduler {
            val player = paper.addPlayer("failed-remove")
            val world = paper.addSimpleWorld("failed-remove-world")
            var fail = false
            val qol = qol(world, player, persistence = { if (fail) error("disk") })
            val expected = qol.view(player)!!
            qol.save(player, "keep", expected).success shouldBe true
            val point = qol.view(player)!!.points.single()
            fail = true

            qol.remove(player, point, qol.view(player)!!).success shouldBe false
            qol.view(player)!!.points.single().name shouldBe "keep"
            qol.close()
        }
    }

    "failed autosave preserves previous slots" {
        withScheduler {
            val player = paper.addPlayer("failed-auto")
            val world = paper.addSimpleWorld("failed-auto-world")
            var now = 100_000L
            var fail = false
            val qol = qol(world, player, clock = { now }, persistence = { if (fail) error("disk") })
            qol.autoSave(player)
            val before = qol.view(player)!!.points
            player.teleport(Location(world, 20.0, 70.0, 0.0))
            now += 120_001L
            fail = true

            qol.autoSave(player)
            qol.view(player)!!.points shouldBe before
            qol.close()
        }
    }

    "save rejects stale run, membership, spectator, and unsafe state" {
        withScheduler {
            val player = paper.addPlayer("guards")
            val world = paper.addSimpleWorld("guard-world")
            val other = paper.addPlayer("other")
            var run = "run"
            val qol = qol(world, player, members = setOf(other.uniqueId), safe = { false }, runProvider = { run })
            val expected = DungeonSaveView(world.uid, "run", emptyList(), null, null)
            qol.save(player, "x", expected).success shouldBe false
            run = "changed"
            qol.save(player, "x", expected).success shouldBe false
            val memberQol = qol(world, player, members = setOf(other.uniqueId), runProvider = { run })
            memberQol.view(player) shouldBe null
            val unsafeQol = qol(world, player, safe = { false }, runProvider = { run })
            unsafeQol.save(player, "x", unsafeQol.view(player)!!).success shouldBe false
            player.gameMode = GameMode.SPECTATOR
            val spectatorQol = qol(world, player, runProvider = { run })
            spectatorQol.view(player) shouldBe null
            qol.close()
            memberQol.close()
            unsafeQol.close()
            spectatorQol.close()
        }
    }

    "autosave creates first point and respects 120 seconds and eight blocks" {
        withScheduler {
            val player = paper.addPlayer("auto")
            val world = paper.addSimpleWorld("auto-world")
            var now = 100_000L
            val audience = RecordingSavesAudience()
            val qol = qol(world, player, clock = { now }, audience = audience)
            qol.autoSave(player)
            qol.view(player)!!.points.count { it.kind == DungeonSaveKind.AUTO } shouldBe 1
            now += 60_000L
            qol.autoSave(player)
            qol.view(player)!!.points.size shouldBe 1
            player.teleport(Location(world, 20.0, 70.0, 0.0))
            now += 60_001L
            qol.autoSave(player)
            qol.view(player)!!.points.count { it.kind == DungeonSaveKind.AUTO } shouldBe 2
            qol.close()
        }
    }

    "portal requires entry, permits walking and consumes its action only once" {
        withScheduler { scheduler ->
            val player = paper.addPlayer("traveler")
            val world = paper.addSimpleWorld("travel-world")
            val destination = Location(world, 4.0, 70.0, 4.0)
            val moved = mutableListOf<Location>()
            val portals = mutableListOf<() -> Unit>()
            val audience = RecordingSavesAudience()
            val service = qol(world, player, entry = destination, portals = portals, audience = audience,
                move = { _, location, _ -> moved += location; true })
            service.travel(player, service.view(player)!!, "entry")
            scheduler.advanceMs(3_000)
            moved shouldBe emptyList()
            audience.messages shouldBe emptyList()
            player.teleport(Location(world, 1.0, 70.0, 0.0))
            portals.single().invoke()
            moved shouldBe listOf(destination)
            portals.single().invoke()
            moved.size shouldBe 1
            service.close()
        }
    }

    "portal rechecks terrain and native move rejection on entry" {
        withScheduler {
            val player = paper.addPlayer("travel-guards")
            val world = paper.addSimpleWorld("travel-guard-world")
            var safe = true
            val audience = RecordingSavesAudience()
            val portals = mutableListOf<() -> Unit>()
            var moves = 0
            val service = qol(world, player, entry = Location(world, 3.0, 70.0, 3.0), safe = { safe }, audience = audience,
                portals = portals, move = { _, _, _ -> moves++; false })
            service.travel(player, service.view(player)!!, "entry")
            safe = false
            portals.last().invoke()
            moves shouldBe 0
            audience.messages.size shouldBe 1
            safe = true
            service.travel(player, service.view(player)!!, "entry")
            portals.last().invoke()
            moves shouldBe 1
            audience.messages.last().toString().contains("отменено") shouldBe true
            service.close()
        }
    }

    "portal rejects a replacement run, removed point and closed service" {
        withScheduler {
            val player = paper.addPlayer("stale-travel")
            val world = paper.addSimpleWorld("stale-travel-world")
            var run = "run"
            val moved = mutableListOf<Location>()
            val portals = mutableListOf<() -> Unit>()
            val service = qol(world, player, entry = Location(world, 4.0, 70.0, 4.0), runProvider = { run }, portals = portals,
                move = { _, location, _ -> moved += location; true })
            service.travel(player, service.view(player)!!, "entry")
            run = "new-run"
            portals.last().invoke()
            run = "run"
            service.save(player, "spot", service.view(player)!!).success shouldBe true
            val expected = service.view(player)!!
            val point = expected.points.single()
            service.travel(player, expected, point.id)
            service.remove(player, point, expected).success shouldBe true
            portals.last().invoke()
            service.travel(player, service.view(player)!!, "entry")
            service.close()
            portals.last().invoke()
            moved shouldBe emptyList()
        }
    }

    "death, expiry and quit invalidate old portals without consuming later requests" {
        withScheduler { scheduler ->
            val player = paper.addPlayer("pending")
            val world = paper.addSimpleWorld("pending-world")
            val moved = mutableListOf<Location>()
            val portals = mutableListOf<() -> Unit>()
            val service = qol(world, player, entry = Location(world, 4.0, 70.0, 4.0), portals = portals,
                move = { _, location, _ -> moved += location; true })
            val expected = service.view(player)!!
            service.travel(player, expected, "entry")
            service.cancelTravelOnDeath(mockk<PlayerDeathEvent> { every { entity } returns player })
            service.travel(player, expected, "entry")
            portals.first().invoke()
            moved shouldBe emptyList()
            scheduler.advanceMs(21_000)
            portals.last().invoke()
            moved shouldBe emptyList()
            service.travel(player, expected, "entry")
            val beforeQuit = portals.last()
            service.quit(player)
            service.travel(player, expected, "entry")
            beforeQuit()
            moved shouldBe emptyList()
            portals.last().invoke()
            moved.size shouldBe 1
            service.close()
        }
    }

    "entering combat after opening the portal prevents travel" {
        withScheduler {
            val player = paper.addPlayer("portal-combat")
            val world = paper.addSimpleWorld("portal-combat-world")
            val portals = mutableListOf<() -> Unit>()
            var moves = 0
            val service = qol(world, player, entry = Location(world, 4.0, 70.0, 4.0), portals = portals,
                move = { _, _, _ -> moves++; true })
            service.travel(player, service.view(player)!!, "entry")
            service.combat(mockk<EntityDamageByEntityEvent> { every { entity } returns player; every { damager } returns player })
            portals.single().invoke()
            moves shouldBe 0
            service.close()
        }
    }

    "combat blocks save and travel, then expires" {
        withScheduler {
            val player = paper.addPlayer("combat")
            val world = paper.addSimpleWorld("combat-world")
            var now = 100L
            val qol = qol(world, player, clock = { now })
            qol.combat(mockk<EntityDamageByEntityEvent> { every { entity } returns player; every { damager } returns player })
            qol.save(player, "fight", qol.view(player)!!).success shouldBe false
            now += 15_001L
            qol.save(player, "fight", qol.view(player)!!).success shouldBe true
            qol.close()
        }
    }

    "cancelled instanced command and plugin teleports are hinted with a throttle" {
        withScheduler {
            val player = paper.addPlayer("hint")
            val world = paper.addSimpleWorld("hint-world")
            val audience = RecordingSavesAudience()
            var now = 1_000L
            val qol = qol(world, player, audience = audience, clock = { now }, instanced = true)
            val first = teleport(player, Location(world, 0.0, 70.0, 0.0), Location(world, 1.0, 70.0, 1.0), PlayerTeleportEvent.TeleportCause.COMMAND).apply { isCancelled = true }
            qol.explainCancelledTeleport(first)
            val second = teleport(player, first.from, first.to, PlayerTeleportEvent.TeleportCause.PLUGIN).apply { isCancelled = true }
            qol.explainCancelledTeleport(second)
            audience.messages.size shouldBe 1
            now += 3_001L
            qol.explainCancelledTeleport(second)
            audience.messages.size shouldBe 2
            qol.close()
        }
    }
})

private fun withScheduler(block: (TestTaskScheduler) -> Unit) {
    val scheduler = TestTaskScheduler()
    Tasks.withScheduler(scheduler) { block(scheduler) }
}

private fun qol(
    world: org.bukkit.World,
    player: Player,
    members: Set<java.util.UUID>? = null,
    safe: (Location) -> Boolean = { true },
    audience: PaperAudienceEffects = RecordingSavesAudience(),
    clock: () -> Long = { 100_000L },
    move: (Player, Location, Boolean) -> Boolean = { _, _, _ -> true },
    instanced: Boolean = false,
    entry: Location? = null,
    entryProvider: () -> Location? = { entry },
    runProvider: () -> String = { "run" },
    persistence: () -> Unit = {},
    portals: MutableList<() -> Unit> = mutableListOf(),
) = EMDungeonQol(
    config(),
    { candidate -> if (candidate == world) DungeonVisit(runProvider(), entry = entryProvider(), members = members, instanced = instanced) else null },
    safe,
    audience,
    clock,
    move = move,
    persistence = PaperPlayerDataPersistence { persistence() },
    openPortal = { _, action -> portals += action },
).also { player.teleport(Location(world, 0.0, 70.0, 0.0)) }

private fun config(): Config = mockk<Config>(relaxed = true).also {
    every { it.bool("dungeon-qol.enabled", true) } returns true
    every { it.bool("dungeon-qol.resume-enabled", true) } returns true
    every { it.bool("dungeon-qol.saves.autosave-enabled", true) } returns true
    every { it.integer("dungeon-qol.resume-hours", 72) } returns 72
    every { it.integer("dungeon-qol.saves.autosave-seconds", 120) } returns 120
    every { it.component(any(), any<String>(), any()) } answers { net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>()) }
}

private fun teleport(player: Player, from: Location, to: Location, cause: PlayerTeleportEvent.TeleportCause) = PlayerTeleportEvent(player, from, to, cause)

private class RecordingSavesAudience : PaperAudienceEffects {
    val messages = mutableListOf<Component>()
    override fun sendMessage(player: Player, message: Component) { messages += message }
    override fun sendActionBar(player: Player, message: Component) { messages += message }
    override fun showTitle(player: Player, title: Title) = Unit
    override fun showBossBar(player: Player, bossBar: BossBar) = Unit
    override fun hideBossBar(player: Player, bossBar: BossBar) = Unit
}
