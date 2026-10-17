package ru.arc.hooks

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.william278.huskhomes.BukkitHuskHomes
import net.william278.huskhomes.HuskHomes
import net.william278.huskhomes.config.Settings
import net.william278.huskhomes.event.TeleportEvent
import net.william278.huskhomes.libraries.minedown.adventure.MineDown
import net.william278.huskhomes.position.Position
import net.william278.huskhomes.teleport.Target
import net.william278.huskhomes.teleport.Teleport
import net.william278.huskhomes.user.BukkitUser
import org.bukkit.Location
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import java.util.Optional
import java.util.UUID

class HuskHomesLocalTeleportObserverTest : FreeSpec({
    "native handoff alone cannot announce success or update back" {
        val f = ArrivalFixture()
        val replay = f.prepare()

        replay.displayTeleportingComplete(f.user)
        f.tick()

        f.arrivals shouldBe 0
        verify(exactly = 0) { f.user.sendMessage(f.successMessage) }
        f.failures.shouldBeEmpty()
        f.logs.any { "NATIVE_HANDOFF" in it && "paperResult=unavailable" in it } shouldBe true
    }

    "only matching event and next-tick actual arrival release native success and back once" {
        val f = ArrivalFixture()
        val replay = f.prepare()
        val event = f.bukkitEvent()
        f.observer.bukkitEvent(event)
        f.arrivals shouldBe 0
        verify(exactly = 0) { f.user.sendMessage(f.successMessage) }

        f.currentLocation = f.destination.clone()
        f.tick()
        replay.displayTeleportingComplete(f.user)
        f.tick(200)

        f.arrivals shouldBe 1
        verify(exactly = 1) { f.user.sendMessage(f.successMessage) }
        verify(exactly = 1) { f.user.playSound("success-sound") }
        f.failures.shouldBeEmpty()
        f.logs.count { "outcome=ARRIVAL_CONFIRMED" in it } shouldBe 1
    }

    "an uncancelled event without movement cannot announce success or save back" {
        val f = ArrivalFixture()
        f.prepare()
        f.observer.bukkitEvent(f.bukkitEvent())
        f.tick()

        f.arrivals shouldBe 0
        f.failures shouldBe listOf(false)
        f.logs.any { "outcome=ARRIVAL_NOT_OBSERVED" in it } shouldBe true
        verify(exactly = 0) { f.user.sendMessage(f.successMessage) }
    }

    "a cancellation after MONITOR observation is honored" {
        val f = ArrivalFixture()
        f.prepare()
        val event = f.bukkitEvent()
        f.observer.bukkitEvent(event)
        event.isCancelled = true
        f.currentLocation = f.destination.clone()
        f.tick()

        f.arrivals shouldBe 0
        f.failures shouldBe listOf(true)
        f.logs.any { "outcome=BUKKIT_CANCELLED" in it } shouldBe true
    }

    "unrelated cancelled and non-plugin events do not consume a pending home arrival" {
        val f = ArrivalFixture()
        f.prepare()
        val unrelated = f.bukkitEvent().apply {
            to = f.destination.clone().add(20.0, 0.0, 0.0)
            isCancelled = true
        }
        f.observer.bukkitEvent(unrelated)
        f.observer.bukkitEvent(
            PlayerTeleportEvent(f.player, f.currentLocation.clone(), f.destination.clone(), PlayerTeleportEvent.TeleportCause.COMMAND),
        )
        f.tick()
        f.failures.shouldBeEmpty()
        f.logs.none { "outcome=BUKKIT_EVENT" in it } shouldBe true

        f.observer.bukkitEvent(f.bukkitEvent())
        f.currentLocation = f.destination.clone()
        f.tick()

        f.arrivals shouldBe 1
        f.failures.shouldBeEmpty()
        verify(exactly = 1) { f.user.sendMessage(f.successMessage) }
    }

    "a superseded destination event cannot terminate its replacement operation" {
        val f = ArrivalFixture()
        f.prepare()
        val replacement = f.destination.clone().add(20.0, 0.0, 0.0)
        every { f.template.target } returns Position.at(
            replacement.x, replacement.y, replacement.z,
            net.william278.huskhomes.position.World.from("survival"), "survival",
        )
        f.prepare()
        f.observer.bukkitEvent(f.bukkitEvent().apply { isCancelled = true })
        f.tick()
        f.failures.shouldBeEmpty()

        f.observer.bukkitEvent(
            PlayerTeleportEvent(f.player, f.currentLocation.clone(), replacement, PlayerTeleportEvent.TeleportCause.PLUGIN),
        )
        f.currentLocation = replacement.clone()
        f.tick()

        f.arrivals shouldBe 1
        f.failures.shouldBeEmpty()
    }

    "a late destination rewrite cannot confirm the requested home" {
        val f = ArrivalFixture()
        f.prepare()
        val event = f.bukkitEvent()
        f.observer.bukkitEvent(event)
        event.to = f.destination.clone().add(20.0, 0.0, 0.0)
        f.currentLocation = event.to!!.clone()
        f.tick()

        f.arrivals shouldBe 0
        f.logs.any { "outcome=OTHER_OR_REDIRECTED_EVENT" in it } shouldBe true
    }

    "native event cancellation stops observation without retrying transport" {
        val f = ArrivalFixture()
        val replay = f.prepare()
        val nativeEvent = TeleportEvent(replay)
        f.observer.nativeEvent(nativeEvent, replay)
        nativeEvent.isCancelled = true
        f.tick()

        f.arrivals shouldBe 0
        f.failures shouldBe listOf(true)
        f.logs.any { "outcome=HUSK_CANCELLED" in it } shouldBe true
    }

    "missing event deadline reports unknown and fences late completion" {
        val f = ArrivalFixture()
        val replay = f.prepare()
        f.tick(200)
        f.currentLocation = f.destination.clone()
        f.observer.bukkitEvent(f.bukkitEvent())
        replay.displayTeleportingComplete(f.user)
        f.tick()

        f.arrivals shouldBe 0
        f.failures shouldBe listOf(false)
        f.logs.any { "outcome=NO_BUKKIT_EVENT_UNKNOWN" in it } shouldBe true
        verify(exactly = 0) { f.user.sendMessage(f.successMessage) }
    }

    "quit, replacement player, replacement operation and close fence queued confirmations" {
        for (exit in listOf("quit", "player", "operation", "close")) {
            val f = ArrivalFixture()
            f.prepare()
            f.observer.bukkitEvent(f.bukkitEvent())
            f.currentLocation = f.destination.clone()
            when (exit) {
                "quit" -> f.observer.quit(f.player)
                "player" -> f.live = mockk()
                "operation" -> f.prepare()
                "close" -> f.observer.close()
            }
            f.tick()
            f.arrivals shouldBe 0
            verify(exactly = 0) { f.user.sendMessage(f.successMessage) }
            f.observer.close()
        }
    }

    "cached native player identity mismatch is diagnostic, never automatically repaired" {
        val f = ArrivalFixture()
        every { f.user.player } returns f.otherPlayer()
        f.prepare()
        f.tick(200)

        f.logs.any { "nativeSameAsPassed=false" in it && "nativeSameAsLive=false" in it } shouldBe true
        f.arrivals shouldBe 0
        verify(exactly = 0) { f.user.teleportLocally(any(), any()) }
    }

    "Username and remote Position targets remain native" {
        val f = ArrivalFixture()
        every { f.template.target } returns mockk<Target>()
        f.observer.prepare(f.template, f.player) { f.arrivals++ } shouldBe null
        every { f.template.target } returns Position.at(1.0, 64.0, 2.0, net.william278.huskhomes.position.World.from("survival"), "other-server")
        f.observer.prepare(f.template, f.player) { f.arrivals++ } shouldBe null

        f.logs.shouldBeEmpty()
        f.jobs.shouldBeEmpty()
    }

    "native world UUID resolution confirms a renamed world with an old saved name" {
        mockkStatic(Bukkit::class)
        try {
            val f = ArrivalFixture(resolveNatively = true)
            val savedId = f.targetWorld.uid
            every { Bukkit.getWorld(savedId) } returns f.targetWorld
            every { Bukkit.getWorld("old-survival-name") } returns null
            every { f.template.target } returns Position.at(
                f.destination.x, f.destination.y, f.destination.z,
                net.william278.huskhomes.position.World.from("old-survival-name", savedId), "survival",
            )
            f.prepare()
            f.observer.bukkitEvent(f.bukkitEvent())
            f.currentLocation = f.destination.clone()
            f.tick()

            f.arrivals shouldBe 1
            verify(exactly = 0) { Bukkit.getWorld("old-survival-name") }
            f.logs.any { "target=survival/old-survival-name" in it && "resolvedTarget=survival[" in it } shouldBe true
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }

    "native name fallback confirms a saved world whose UUID no longer resolves" {
        mockkStatic(Bukkit::class)
        try {
            val f = ArrivalFixture(resolveNatively = true)
            val staleId = UUID.randomUUID()
            every { Bukkit.getWorld(staleId) } returns null
            every { Bukkit.getWorld("survival") } returns f.targetWorld
            every { f.template.target } returns Position.at(
                f.destination.x, f.destination.y, f.destination.z,
                net.william278.huskhomes.position.World.from("survival", staleId), "survival",
            )
            f.prepare()
            f.observer.bukkitEvent(f.bukkitEvent())
            f.currentLocation = f.destination.clone()
            f.tick()

            f.arrivals shouldBe 1
            verify(exactly = 1) { Bukkit.getWorld(staleId) }
            verify(exactly = 1) { Bukkit.getWorld("survival") }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
})

private class ArrivalFixture(resolveNatively: Boolean = false) {
    private val playerId = UUID.randomUUID()
    private val originWorld = world("mining")
    val targetWorld = world("survival")
    val destination = Location(targetWorld, 2500.0, 87.0, 4334.0)
    var currentLocation = Location(originWorld, 1764.0, 64.0, -2180.0)
    val player = player()
    var live: Player? = player
    val user = mockk<BukkitUser>(relaxed = true) {
        every { uuid } returns playerId
        every { this@mockk.player } returns this@ArrivalFixture.player
    }
    val successMessage = mockk<MineDown>()
    private val plugin = mockk<HuskHomes> {
        every { settings.crossServer.isEnabled } returns true
        every { serverName } returns "survival"
        every { settings.general.isTeleportAsync } returns true
        every { locales.getLocale("teleporting_complete") } returns Optional.of(successMessage)
        every { settings.general.soundEffects.get(Settings.SoundEffectAction.TELEPORTATION_COMPLETE) } returns Optional.of("success-sound")
    }
    val template = mockk<Teleport> {
        every { executor } returns user
        every { teleporter } returns user
        every { target } returns Position.at(destination.x, destination.y, destination.z, net.william278.huskhomes.position.World.from("survival"), "survival")
        every { type } returns Teleport.Type.BACK
        every { actions } returns emptyList()
        every { this@mockk.plugin } returns this@ArrivalFixture.plugin
    }
    val jobs = mutableListOf<TestJob>()
    private var now = 0L
    private val scheduler = mockk<TaskScheduler> {
        every { runLater(any(), any()) } answers {
            TestJob(now + firstArg<Long>(), secondArg<Runnable>()).also(jobs::add)
        }
        every { runSync(any()) } answers {
            TestJob(now, firstArg<Runnable>()).also(jobs::add)
        }
    }
    val logs = mutableListOf<String>()
    val failures = mutableListOf<Boolean>()
    var arrivals = 0
    val observer = HuskHomesLocalTeleportObserver(
        scheduler = { scheduler },
        livePlayer = { live },
        resolveDestination = { position ->
            if (resolveNatively) BukkitHuskHomes.Adapter.adapt(position)
            else Location(targetWorld, position.x, position.y, position.z, position.yaw, position.pitch)
        },
        timeoutTicks = { 200L },
        notifyUnconfirmed = { _, cancelled -> failures += cancelled },
        diagnostic = { _, message -> logs += message },
    )

    fun prepare(): ObservedHuskTeleport =
        observer.prepare(template, player) { arrivals++ }!!.also { observer.nativeEvent(TeleportEvent(it), it) }

    fun bukkitEvent() = PlayerTeleportEvent(player, currentLocation.clone(), destination.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)

    fun tick(ticks: Int = 1) {
        repeat(ticks) {
            now++
            jobs.filter { !it.isCancelled && it.at <= now }.toList().forEach {
                jobs.remove(it)
                it.runnable.run()
            }
        }
    }

    fun otherPlayer(): Player = player()

    private fun player(): Player = mockk {
        every { uniqueId } returns playerId
        every { name } returns "TestPlayer"
        every { location } answers { currentLocation.clone() }
        every { isOnline } returns true
        every { isValid } returns true
        every { isDead } returns false
        every { isSleeping } returns false
        every { vehicle } returns null
        every { passengers } returns emptyList()
    }

    private fun world(name: String): World = mockk {
        every { this@mockk.name } returns name
        every { uid } returns UUID.randomUUID()
    }
}

private class TestJob(val at: Long, val runnable: Runnable) : ScheduledTask {
    override val id = 0
    override var isCancelled = false
    override fun cancel() { isCancelled = true }
}
