package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AuroraEngineTest : FreeSpec({
    "ordinary effects require permission at capture and again before sending" {
        withEngine { fixture ->
            fixture.engine.tick()
            fixture.queued.size shouldBe 0
            fixture.locations.size shouldBe 0

            fixture.permission(true)
            fixture.capture()
            fixture.completePlanning()
            fixture.locations.size shouldBe 0
            fixture.permission(false)
            fixture.engine.tick()
            fixture.locations.size shouldBe 0

            fixture.permission(true)
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.engine.submittedPackets shouldBe 1L
        }
    }

    "events bypass ordinary permission and personal toggle but reject a current ping spike before sending" {
        withEngine { fixture ->
            fixture.engine.setPersonallyEnabled(fixture.player, false)
            fixture.event = fixture.settings.profiles
            fixture.currentPing = 50
            fixture.capture()
            fixture.completePlanning()
            fixture.currentPing = 251
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            (fixture.engine.pingSkipped > 0L) shouldBe true
            fixture.queued.size shouldBe 0

            fixture.currentPing = 50
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.player.hasPermission(fixture.settings.permission) shouldBe false
            fixture.engine.personallyEnabled(fixture.player) shouldBe false
        }
    }

    "events also reject a recovered current ping while its rolling average is still too high" {
        val settings = engineSettings().copy(ping = AuroraPingSettings(250, 180, 3, 1))
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.currentPing = 240
            repeat(3) { fixture.engine.tick() }
            fixture.queued.size shouldBe 0
            fixture.currentPing = 100
            fixture.engine.tick() // Average (240 + 240 + 100) / 3 remains above 180.
            fixture.queued.size shouldBe 0
            fixture.locations.size shouldBe 0

            fixture.capture() // Average (240 + 100 + 100) / 3 now allows the viewer.
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
        }
    }

    "personal toggle invalidates pending ordinary work and re-enabling permits a fresh batch" {
        withEngine { fixture ->
            fixture.permission(true)
            fixture.capture()
            fixture.engine.setPersonallyEnabled(fixture.player, false)
            fixture.engine.personallyEnabled(fixture.player) shouldBe false
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.queued.size shouldBe 0
            verify(atLeast = 1) { fixture.emitter.invalidatePending() }

            fixture.engine.setPersonallyEnabled(fixture.player, true)
            fixture.engine.personallyEnabled(fixture.player) shouldBe true
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
        }
    }

    "ending or replacing an event fences its planned batch by the exact lease generation" {
        withEngine { fixture ->
            fixture.event = fixture.settings.profiles
            fixture.capture()
            fixture.event = null
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.queued.size shouldBe 0

            fixture.event = fixture.settings.profiles
            fixture.capture()
            fixture.event = fixture.settings.profiles.map { it.copy(id = "replacement") }
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.queued.size shouldBe 1 // A new generation may plan while the old one is discarded.
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
        }
    }

    "quit while async work is queued prevents delivery and closes the viewer emitter" {
        withEngine { fixture ->
            fixture.permission(true)
            fixture.capture()
            fixture.engine.quit(fixture.player.uniqueId)
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.engine.viewerCount shouldBe 0
            verify(exactly = 1) { fixture.emitter.close() }
        }
    }

    "world change while async work is queued prevents delivery in the new world" {
        withEngine { fixture ->
            fixture.permission(true)
            fixture.capture()
            val destination = fixture.paper.addSimpleWorld("destination")
            fixture.player.teleport(Location(destination, 0.5, 80.0, 0.5)) shouldBe true
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            verify(atLeast = 1) { fixture.emitter.invalidatePending() }
        }
    }

    "closing while async work is queued prevents its completion from publishing a batch" {
        withEngine { fixture ->
            fixture.permission(true)
            fixture.capture()
            fixture.engine.close()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.engine.viewerCount shouldBe 0
            verify(exactly = 1) { fixture.emitter.close() }
        }
    }

    "a dense profile cannot exceed the configured per-viewer packet cap" {
        val settings = engineSettings(points = 24).copy(attemptsPerViewer = 8, packetsPerViewer = 3)
        withEngine(settings) { fixture ->
            fixture.permission(true)
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 3
            fixture.engine.submittedPackets shouldBe 3L
        }
    }
})

private fun withEngine(settings: AuroraSettings = engineSettings(), block: (EngineFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            EngineFixture(paper, settings).use(block)
        }
    }
}

private class EngineFixture(val paper: MockBukkitTestRuntime, val settings: AuroraSettings) : AutoCloseable {
    private val mainThread = Thread.currentThread()
    private val plugin = paper.createSimplePlugin("ArcAuroraEngineTest")
    private val world = paper.addSimpleWorld("ambient")
    val player = EnginePlayer(paper.server)
    var currentPing: Int
        get() = player.observedPing
        set(value) { player.observedPing = value }
    var event: List<AuroraProfile>? = null
    val locations = mutableListOf<Location>()
    val queued = ArrayDeque<Runnable>()
    val emitter = mockk<PaperViewerParticles>(relaxed = true)
    private val scheduler = mockk<TaskScheduler>()
    private val scope = LifecycleTaskScope(scheduler)
    private val attachment = player.addAttachment(plugin, settings.permission, false)
    val engine = AuroraEngine(plugin, scope, settings, { if (it == world.uid) event else null }, { emitter })

    init {
        every { scheduler.runAsync(any()) } answers {
            queued.addLast(firstArg())
            mockk<ScheduledTask>(relaxed = true)
        }
        every { emitter.emit(any<Player>(), any<Location>(), any<Particle>(), any<Int>(),
            any<Double>(), any<Double>(), any<Double>(), any<Double>(), any(), any<Boolean>()) } answers {
            requireMain()
            arg<Boolean>(9) shouldBe false
            locations += arg<Location>(1).clone()
        }
        paper.server.addPlayer(player)
        player.teleport(Location(world, 0.5, 80.0, 0.5)) shouldBe true
        for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
        world.isChunkLoaded(0, 0) shouldBe true
        world.getBlockAt(0, 80, 0).type shouldBe Material.AIR
        world.getBlockAt(0, 82, 0).isPassable shouldBe true
        // Read the native fixture once outside the bounded tick to avoid first-use class loading there.
        world.getBlockAt(0, 80, 0).biome.key.toString()
        world.getBlockAt(0, 80, 0).lightFromSky
        engine.personallyEnabled(player) shouldBe true
        player.hasPermission(settings.permission) shouldBe false
        engine.join(player)
    }

    fun permission(granted: Boolean) = attachment.setPermission(settings.permission, granted)

    fun capture() {
        queued.size shouldBe 0
        engine.tick()
        queued.size shouldBe 1
    }

    /** Only the captured Runnable runs on the worker; Paper capture/delivery stays on this test thread. */
    fun completePlanning() {
        val work = queued.removeFirst()
        CompletableFuture.runAsync { work.run() }.get(5, TimeUnit.SECONDS)
    }

    override fun close() {
        engine.close()
        scope.close()
    }

    private fun requireMain() {
        check(Thread.currentThread() === mainThread) { "Native viewer access or emission escaped the main test thread" }
    }
}

/** Override the one controlled observation without spying on MockBukkit's inherited entity methods. */
private class EnginePlayer(server: ServerMock) : PlayerMock(server, "AuroraViewer") {
    private val mainThread = Thread.currentThread()
    var observedPing = 50

    override fun getPing(): Int {
        check(Thread.currentThread() === mainThread) { "Ping was read outside the main test thread" }
        return observedPing
    }
}

private fun engineSettings(points: Int = 1): AuroraSettings = AuroraConfig.parse(YamlConfiguration().apply {
    loadFromString("""
        interval-ticks: 1
        viewers-per-tick: 1
        attempts-per-viewer: 1
        packets-per-viewer: 1
        native-budget-micros: 10000
        ping:
          window-samples: 3
          sample-interval-ticks: 1
        profiles:
          ambient:
            particle: minecraft:end_rod
            biomes: ['*']
            worlds: ['*']
            radius: 4
            min-distance: 2
            vertical-min: 2
            vertical-max: 2
            shape: POINT
            points: $points
    """.trimIndent())
})
