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
import kotlin.math.abs

class AuroraEngineTest : FreeSpec({
    "private preview uses only its explicit viewer callback and remains subject to the ping gate" {
        withEngine { fixture ->
            fixture.preview = fixture.settings.profiles
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.player.hasPermission(fixture.settings.permission) shouldBe false
            fixture.completePlanning()
            fixture.currentPing = 251
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.currentPing = 50
            fixture.preview = null
            fixture.engine.invalidate(fixture.player.uniqueId)
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
        }
    }

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
            fixture.emissions.single().force shouldBe false
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

    "a sky anchor stays in world space across movement and turning and uses its faster cadence" {
        val settings = skySettings().copy(intervalTicks = 10)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            val anchor = fixture.locations.single().clone()
            (anchor.z > 0.5) shouldBe true // Minecraft yaw zero faces +Z; creation stays within its front fan.
            (abs(anchor.x - 0.5) <= (anchor.z - 0.5) * kotlin.math.sqrt(3.0) + 1e-9) shouldBe true
            fixture.player.teleport(fixture.player.location.add(1.0, 0.0, 0.0).apply { yaw = 180f }) shouldBe true
            fixture.pass()
            fixture.locations.size shouldBe 2
            fixture.locations[1] shouldBe anchor
            fixture.emissions.forEach { emission ->
                emission.force shouldBe true
                emission.particle shouldBe Particle.END_ROD
                emission.count shouldBe 1
                emission.offsets shouldBe listOf(0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    "sky cyclic cursors cover two 48 point scenes within eight passes without chasing the viewer" {
        val settings = skySettings(points = 48, shape = AuroraShape.LINE, packets = 12).let { original ->
            original.copy(profiles = listOf(original.profiles.single().copy(id = "lower", verticalMin = 10.0, verticalMax = 10.0),
                original.profiles.single().copy(id = "upper", verticalMin = 20.0, verticalMax = 20.0, radius = 16.0, minDistance = 16.0)))
        }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            repeat(8) { fixture.pass() }
            fixture.locations.size shouldBe 96
            val lower = fixture.locations.filter { it.y < 95.0 }
            val upper = fixture.locations.filter { it.y > 95.0 }
            lower.size shouldBe 48
            upper.size shouldBe 48
            lower.map { Triple(it.x, it.y, it.z) }.toSet().size shouldBe 48
            upper.map { Triple(it.x, it.y, it.z) }.toSet().size shouldBe 48
            fixture.pass()
            fixture.locations.drop(96).filter { it.y < 95.0 } shouldBe lower.take(6)
            fixture.locations.drop(96).filter { it.y > 95.0 } shouldBe upper.take(6)
        }
    }

    "planning a sky batch never consumes its unattempted tail" {
        val cursor = AuroraSkyCursor(48)
        val planned = cursor.plan().let { pass -> List(12) { pass.next() } }
        cursor.plan().next().index shouldBe 0 // A stale or entirely dropped batch changes nothing.
        planned.take(4).forEach { it.attempted() }
        val next = cursor.plan()
        next.next().index shouldBe 4
        next.next().index shouldBe 5
        cursor.plan().next().index shouldBe 4 // Preparing another pass is still read-only.
    }

    "three sky cursors eventually cover every point when Main attempts only four of 36 planned points" {
        val cursors = List(3) { AuroraSkyCursor(48) }
        val seen = List(3) { mutableSetOf<Int>() }
        repeat(36) { pass ->
            val plans = cursors.map { it.plan() }
            val batch = List(36) { index ->
                val profile = (pass + index) % plans.size
                profile to plans[profile].next()
            }
            batch.take(4).forEach { (profile, point) ->
                seen[profile] += point.index
                point.attempted()
            }
        }
        seen.forEach { it shouldBe (0 until 48).toSet() }
        cursors.forEach { it.plan().next().index shouldBe 0 }
    }

    "sky local width faces the creation viewer and a revision recreates its anchor for the new yaw" {
        val settings = skySettings(points = 2, shape = AuroraShape.LINE, packets = 2)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.player.teleport(fixture.player.location.apply { yaw = 90f }) shouldBe true
            fixture.pass()
            val first = fixture.locations.take(2)
            val centerX = first.map { it.x }.average()
            val centerZ = first.map { it.z }.average()
            (centerX < 0.5) shouldBe true // yaw 90 faces -X.
            val widthX = first[1].x - first[0].x
            val widthZ = first[1].z - first[0].z
            (abs(widthX * (0.5 - centerX) + widthZ * (0.5 - centerZ)) < 1e-8) shouldBe true
            fixture.player.teleport(fixture.player.location.apply { yaw = -90f }) shouldBe true
            fixture.engine.invalidate(fixture.player.uniqueId)
            fixture.pass()
            (fixture.locations.takeLast(2).map { it.x }.average() > 0.5) shouldBe true
        }
    }

    "sky expiry and leaving the anchor range create a new scene while live profile replacement resets the cursor" {
        val settings = skySettings(points = 4, shape = AuroraShape.LINE, packets = 2).let { original ->
            original.copy(profiles = original.profiles.map { it.copy(sceneDurationTicks = 40) })
        }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            fixture.player.teleport(fixture.player.location.apply { yaw = 180f }) shouldBe true
            repeat(9) { fixture.pass() } // Ages 5..37 retain the first anchor, despite the changed yaw.
            (fixture.locations.takeLast(2).map { it.z }.average() > 0.5) shouldBe true
            fixture.pass() // Age 41 expires the scene and creates it in the new front fan.
            (fixture.locations.takeLast(2).map { it.z }.average() < 0.5) shouldBe true

            fixture.player.teleport(Location(fixture.player.world, 16.5, 80.0, 16.5, 0f, 0f)) shouldBe true
            fixture.pass()
            val movedCenter = fixture.locations.takeLast(2)
            (movedCenter.map { it.z }.average() > 16.5) shouldBe true
            fixture.event = settings.profiles.map { it.copy(verticalMin = 18.0, verticalMax = 18.0, radius = 16.0, minDistance = 16.0) }
            fixture.pass()
            (fixture.locations.takeLast(2).all { it.y > 97.0 && it.y < 98.0 }) shouldBe true
        }
    }

    "three sky profiles keep their configured particle data and occupy distinct creation sectors" {
        val settings = skySettings(packets = 3).let { original ->
            original.copy(profiles = listOf(Particle.END_ROD, Particle.ELECTRIC_SPARK, Particle.SMALL_FLAME).mapIndexed { index, particle ->
                original.profiles.single().copy(id = "sky_$index", particle = particle, count = index + 1,
                    offsetX = 0.25, offsetY = 0.5, offsetZ = 0.75, speed = 0.125)
            })
        }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            fixture.emissions.map { it.particle } shouldBe settings.profiles.map { it.particle }
            fixture.emissions.map { it.count } shouldBe listOf(1, 2, 3)
            fixture.emissions.forEach { it.offsets shouldBe listOf(0.25, 0.5, 0.75, 0.125); it.force shouldBe true }
            fixture.locations.forEachIndexed { index, at ->
                val bearing = Math.toDegrees(kotlin.math.atan2(0.5 - at.x, at.z - 0.5))
                (abs(bearing - (index - 1) * 35.0) <= 8.0 + 1e-9) shouldBe true
            }
        }
    }

    "mixed sky and ordinary profiles share the same cap and a one packet cap alternates groups" {
        listOf(1, 4).forEach { cap ->
            val settings = skySettings(packets = cap).let { original ->
                original.copy(attemptsPerViewer = 8, profiles = original.profiles + original.profiles.single().copy(
                    id = "ordinary", skyScene = false, particle = Particle.SMALL_FLAME, shape = AuroraShape.POINT, points = 1))
            }
            withEngine(settings) { fixture ->
                fixture.event = settings.profiles
                repeat(2) { fixture.pass() }
                fixture.locations.size shouldBe cap * 2
                fixture.emissions.count { it.force } shouldBe cap
                fixture.emissions.count { !it.force && it.particle == Particle.SMALL_FLAME } shouldBe cap
            }
        }
    }

    "sky uses its separate cap and mixed scenes preserve the ordinary maximum" {
        val sky = skySettings(points = 8, shape = AuroraShape.LINE, packets = 6).copy(packetsPerViewer = 2)
        withEngine(sky) { fixture ->
            fixture.event = sky.profiles
            // Main may discard a batch tail when its wall-clock slice expires. Check every
            // observed cap and require the full allocation within a bounded set of fresh passes.
            val sentPerPass = List(8) {
                val before = fixture.emissions.size
                fixture.pass()
                (fixture.emissions.size - before).also { sent -> (sent <= 6) shouldBe true }
            }
            sentPerPass.maxOrNull() shouldBe 6
            fixture.locations.map { Triple(it.x, it.y, it.z) }.toSet().size shouldBe 8
        }
        val mixed = sky.copy(attemptsPerViewer = 8, profiles = sky.profiles + (1..2).map { index ->
            sky.profiles.single().copy(id = "ordinary_$index", skyScene = false, particle = Particle.SMALL_FLAME,
                shape = AuroraShape.POINT, points = 1)
        })
        withEngine(mixed) { fixture ->
            fixture.event = mixed.profiles
            val sentPerPass = List(8) {
                val before = fixture.emissions.size
                fixture.pass()
                fixture.emissions.drop(before).also { sent ->
                    (sent.size <= 6) shouldBe true
                    (sent.count { it.force } <= 4) shouldBe true
                    (sent.count { !it.force } <= 2) shouldBe true
                }
            }
            sentPerPass.any { sent -> sent.size == 6 && sent.count { it.force } == 4 && sent.count { !it.force } == 2 } shouldBe true
        }
    }

    "sky scenes retain the same ping and quit fences as ordinary effects" {
        val settings = skySettings()
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            fixture.currentPing = 251
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            fixture.currentPing = 50
            fixture.waitForPlanning()
            fixture.engine.quit(fixture.player.uniqueId)
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.locations.size shouldBe 0
            verify(exactly = 1) { fixture.emitter.close() }
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
    var preview: List<AuroraProfile>? = null
    val locations = mutableListOf<Location>()
    val emissions = mutableListOf<ObservedEmission>()
    val queued = ArrayDeque<Runnable>()
    val emitter = mockk<PaperViewerParticles>(relaxed = true)
    private val scheduler = mockk<TaskScheduler>()
    private val scope = LifecycleTaskScope(scheduler)
    private val attachment = player.addAttachment(plugin, settings.permission, false)
    val engine = AuroraEngine(plugin, scope, settings, { if (it == world.uid) event else null }, { emitter },
        { if (it == player.uniqueId) preview else null })

    init {
        every { scheduler.runAsync(any()) } answers {
            queued.addLast(firstArg())
            mockk<ScheduledTask>(relaxed = true)
        }
        every { emitter.emit(any<Player>(), any<Location>(), any<Particle>(), any<Int>(),
            any<Double>(), any<Double>(), any<Double>(), any<Double>(), any(), any<Boolean>()) } answers {
            requireMain()
            locations += arg<Location>(1).clone()
            emissions += ObservedEmission(arg<Particle>(2), arg<Int>(3),
                listOf(arg<Double>(4), arg<Double>(5), arg<Double>(6), arg<Double>(7)), arg<Boolean>(9))
        }
        paper.server.addPlayer(player)
        player.teleport(Location(world, 0.5, 80.0, 0.5)) shouldBe true
        for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
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

    fun waitForPlanning() {
        queued.size shouldBe 0
        repeat(settings.intervalTicks + 1) {
            engine.tick()
            if (queued.isNotEmpty()) return
        }
        error("No planning task was captured within the configured interval")
    }

    fun pass() {
        waitForPlanning()
        completePlanning()
        engine.tick()
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

private data class ObservedEmission(val particle: Particle, val count: Int, val offsets: List<Double>, val force: Boolean)

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

private fun skySettings(points: Int = 1, shape: AuroraShape = AuroraShape.POINT, packets: Int = 1): AuroraSettings =
    engineSettings().let { original ->
        original.copy(intervalTicks = 10, packetsPerViewer = packets, skyPacketsPerViewer = packets, profiles = original.profiles.map { it.copy(
            skyScene = true, sceneDurationTicks = 240, motionPeriodTicks = 400, updateTicks = 4,
            radius = 8.0, minDistance = 8.0, verticalMin = 10.0, verticalMax = 10.0,
            shape = shape, shapeSize = 2.0, points = points, count = 1, offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0, speed = 0.0,
        ) })
    }
