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

    "a prepared wave advances one head per visit with at most one fresh repair despite worker delay" {
        val settings = skySettings(points = 24, shape = AuroraShape.WAVE, packets = 36)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            val sent = List(35) {
                val before = fixture.locations.size
                fixture.engine.tick()
                fixture.locations.size - before
            }
            val afterAdmission = sent.dropWhile { it == 0 }
            afterAdmission.take(25) shouldBe listOf(1) + List(23) { 2 } + listOf(1)
            afterAdmission.drop(25).all { it == 0 } shouldBe true // Finite stroke; no cyclic overpainting.
            fixture.queued.size shouldBe 1 // The delayed refresh never stalled or burst the retained path.
            fixture.locations.toSet().size shouldBe 24
        }
    }

    "each retained profile honors its own cadence without filling the available packet cap" {
        val settings = skySettings(points = 24, packets = 36).let { original -> original.copy(profiles = listOf(
            original.profiles.single(), original.profiles.single().copy(id = "slower", updateTicks = 3, particle = Particle.ELECTRIC_SPARK),
        )) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            repeat(8) {
                if (fixture.emissions.map { it.particle }.toSet().size < 2) fixture.engine.tick()
            }
            fixture.locations.clear()
            fixture.emissions.clear()
            repeat(12) {
                val before = fixture.emissions.size
                fixture.engine.tick()
                (fixture.emissions.size - before <= 2) shouldBe true
            }
            fixture.emissions.count { it.particle == Particle.END_ROD } shouldBe 12
            fixture.emissions.count { it.particle == Particle.ELECTRIC_SPARK } shouldBe 4
        }
    }

    "three cube tracers and a repaired wave share the cap and finish without old-tail replay" {
        val settings = skySettings(points = 56, shape = AuroraShape.WAVE, packets = 5).let { original -> original.copy(profiles = listOf(
            original.profiles.single(), original.profiles.single().copy(id = "cube", shape = AuroraShape.CUBE,
                points = 96, skyTracers = 3, particle = Particle.FIREWORK),
        )) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            repeat(80) {
                val before = fixture.emissions.size
                fixture.engine.tick()
                val sent = fixture.emissions.drop(before)
                (sent.size <= 5) shouldBe true
                (sent.count { it.particle == Particle.END_ROD } <= 2) shouldBe true
                (sent.count { it.particle == Particle.FIREWORK } <= 3) shouldBe true
            }
            fixture.emissions.count { it.particle == Particle.FIREWORK } shouldBe 96
            fixture.locations.filterIndexed { index, _ -> fixture.emissions[index].particle == Particle.END_ROD }
                .toSet().size shouldBe 56
        }
    }

    "sky options control actual anchor geometry fresh repair and terrain clearance" {
        val options = AuroraSkyOptions(fanDegrees = 0.0, scaleMin = 1.0, scaleMax = 1.0,
            rollRadians = 0.0, waveAmplitude = 0.0, waveSlope = 0.0, waveDepth = 0.0,
            strokeDrift = 0.0, strokeLift = 0.0)
        for (repair in listOf(false, true)) {
            val settings = skySettings(points = 8, shape = AuroraShape.WAVE, packets = 8).let { original ->
                original.copy(profiles = original.profiles.map { it.copy(skyOptions = options.copy(freshHeadRepair = repair)) })
            }
            withEngine(settings) { fixture ->
                fixture.event = settings.profiles
                fixture.capture()
                fixture.completePlanning()
                repeat(32) { fixture.engine.tick() }
                fixture.locations.size shouldBe if (repair) 16 else 8
                fixture.locations.all { it.y == 90.0 && kotlin.math.abs(it.z - 8.5) < 1e-8 } shouldBe true
                fixture.locations.minOf { it.x } shouldBe -1.5
                fixture.locations.maxOf { it.x } shouldBe 2.5
            }
        }
        val cautious = skySettings(points = 8, shape = AuroraShape.WAVE, packets = 8).let { original ->
            original.copy(profiles = original.profiles.map { it.copy(skyOptions = options.copy(terrainClearance = 8.0)) })
        }
        withEngine(cautious) { fixture ->
            fixture.roof(85)
            fixture.event = cautious.profiles
            fixture.capture()
            fixture.completePlanning()
            repeat(32) { fixture.engine.tick() }
            fixture.locations shouldBe emptyList()
        }
    }

    "a retained sky path keeps its creation anchor and facing when the viewer turns or moves" {
        val settings = skySettings(points = 8)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            fixture.engine.tick()
            val first = fixture.locations.single()
            (first.z > 0.5) shouldBe true
            fixture.player.teleport(fixture.player.location.add(1.0, 0.0, 0.0).apply { yaw = 180f }) shouldBe true
            repeat(7) { fixture.engine.tick() }
            fixture.locations.size shouldBe 8
            fixture.locations.all { it.distance(first) < 0.3 } shouldBe true
            fixture.emissions.forEach { emission ->
                emission.force shouldBe true
                emission.count shouldBe 1
                emission.offsets shouldBe listOf(0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    "planning a finite sky stroke never consumes an unattempted tail or rewinds a newer acknowledgement" {
        val cursor = AuroraSkyCursor(8)
        val planned = cursor.plan().let { pass -> List(8) { checkNotNull(pass.next()) } }
        checkNotNull(cursor.plan().next()).index shouldBe 0
        planned.take(3).forEach { it.attempted() }
        checkNotNull(cursor.plan().next()).index shouldBe 3
        planned.first().attempted() // A stale acknowledgement cannot rewind the path.
        checkNotNull(cursor.plan().next()).index shouldBe 3
        planned.drop(3).forEach { it.attempted() }
        cursor.plan().next() shouldBe null
    }

    "sky deadlines and a one request cap preserve profile fairness without batching" {
        val settings = skySettings(points = 20, packets = 1).let { original -> original.copy(profiles =
            listOf(Particle.END_ROD, Particle.ELECTRIC_SPARK, Particle.SMALL_FLAME).mapIndexed { index, particle ->
                original.profiles.single().copy(id = "sky_$index", particle = particle)
            }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            repeat(12) {
                val before = fixture.emissions.size
                fixture.engine.tick()
                (fixture.emissions.size - before) shouldBe 1
            }
            settings.profiles.forEach { profile -> fixture.emissions.count { it.particle == profile.particle } shouldBe 4 }
        }
    }

    "viewer rotation cannot alias profile fairness at a one request cap" {
        val settings = skySettings(points = 192, packets = 1).let { original -> original.copy(viewersPerTick = 1, profiles =
            listOf(Particle.END_ROD, Particle.ELECTRIC_SPARK, Particle.SMALL_FLAME).mapIndexed { index, particle ->
                original.profiles.single().copy(id = "sky_$index", particle = particle)
            }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            val ids = listOf(fixture.player.uniqueId, fixture.joinAdditional("Second"), fixture.joinAdditional("Third"))
            repeat(75) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            ids.forEach { id ->
                val seen = fixture.emissions.filter { it.viewer == id }.map { it.particle }.toSet()
                seen shouldBe settings.profiles.map { it.particle }.toSet()
            }
        }
    }

    "a delayed scene remains empty until its own birth and expired strokes are not replayed" {
        val settings = skySettings(points = 60).let { original -> original.copy(profiles =
            original.profiles.map { it.copy(sceneDurationTicks = 40, spawnDelayTicks = 20) }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            val sent = List(70) {
                val before = fixture.locations.size
                fixture.engine.tick()
                fixture.locations.size - before
            }
            sent.all { it in 0..1 } shouldBe true
            (sent.sum() in 38..40) shouldBe true
            sent.takeLast(10).all { it == 0 } shouldBe true
        }
    }

    "invalidating a retained path retires it immediately and a fresh scene uses the new facing" {
        val settings = skySettings(points = 24)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            (fixture.locations.single().z > 0.5) shouldBe true
            fixture.player.teleport(fixture.player.location.apply { yaw = 180f }) shouldBe true
            fixture.engine.invalidate(fixture.player.uniqueId)
            val before = fixture.locations.size
            repeat(3) { fixture.engine.tick() }
            fixture.locations.size shouldBe before
            fixture.waitForPlanning()
            fixture.completePlanning()
            fixture.engine.tick()
            (fixture.locations.last().z < 0.5) shouldBe true
        }
    }

    "mixed sky and ordinary emissions remain under the combined cap and both groups progress" {
        listOf(1, 4).forEach { cap ->
            val settings = skySettings(points = 48, packets = cap).let { original ->
                original.copy(attemptsPerViewer = 8, profiles = original.profiles + original.profiles.single().copy(
                    id = "ordinary", skyScene = false, particle = Particle.SMALL_FLAME, shape = AuroraShape.POINT, points = 1))
            }
            withEngine(settings) { fixture ->
                fixture.event = settings.profiles
                repeat(25) {
                    if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                    val before = fixture.emissions.size
                    fixture.engine.tick()
                    val sent = fixture.emissions.drop(before)
                    (sent.size <= cap) shouldBe true
                    (sent.count { it.force } <= 1) shouldBe true
                }
                fixture.emissions.any { it.force } shouldBe true
                fixture.emissions.any { !it.force } shouldBe true
            }
        }
    }

    "bundled distant figures keep per-head pacing across delayed worker refreshes and rebirth" {
        val bundled = AuroraConfig.parse(YamlConfiguration().apply {
            loadFromString(checkNotNull(javaClass.classLoader.getResourceAsStream("config.yml")).bufferedReader().use { it.readText() })
        }).copy(nativeBudgetMicros = 10000)
        withEngine(bundled, highSky = true) { fixture ->
            fixture.loadChunks(6)
            fixture.event = bundled.eventPresets.getValue("treasure-hunt")
            val timeline = StringBuilder("tick,particle,x,y,z\n")
            repeat(1000) { tick ->
                if (fixture.queued.isNotEmpty() && tick % 2 == 0) fixture.completePlanning()
                val before = fixture.emissions.size
                fixture.engine.tick()
                val sent = fixture.emissions.drop(before)
                (sent.size <= bundled.skyPacketsPerViewer) shouldBe true
                (sent.count { it.particle == Particle.END_ROD } <= 6) shouldBe true
                (sent.count { it.particle == Particle.FIREWORK } <= 11) shouldBe true
                (sent.count { it.particle == Particle.TOTEM_OF_UNDYING } <= 6) shouldBe true
                for (index in before until fixture.emissions.size) {
                    val at = fixture.locations[index]
                    timeline.append("$tick,${fixture.emissions[index].particle.name},${at.x - 0.5},${at.y - 80.0},${at.z - 0.5}\n")
                }
            }
            fixture.emissions.map { it.particle }.toSet() shouldBe setOf(Particle.END_ROD, Particle.FIREWORK, Particle.TOTEM_OF_UNDYING)
            // Optional source-driven preview export: exact native submissions from this same engine fixture.
            System.getenv("ARCAURORA_PREVIEW_CSV")?.let { path -> java.nio.file.Files.writeString(java.nio.file.Path.of(path), timeline) }
        }
    }

    "held cube refreshes revealed points after birth and stops replenishing before natural exit" {
        val settings = skySettings(points = 192, shape = AuroraShape.CUBE, packets = 12).let { original ->
            original.copy(profiles = original.profiles.map { it.copy(shapeSize = 6.0, verticalMin = 20.0,
                verticalMax = 20.0, skyTracers = 3, particle = Particle.FIREWORK, skyRefreshTicks = 24, sceneDurationTicks = 320) })
        }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            val perTick = List(400) {
                val before = fixture.locations.size
                fixture.engine.tick() // Hold later worker work: pacing/refresh must not depend on it.
                fixture.locations.size - before
            }
            perTick.all { it in 0..11 } shouldBe true
            (perTick.take(120).sum() > 192) shouldBe true
            (perTick.slice(160..240).sum() >= 300) shouldBe true // Full contour sustained at 8..12 seconds.
            perTick.takeLast(40).all { it == 0 } shouldBe true
        }
    }

    "the minimum held scene duration supports both a proportional exit and no staged exit" {
        for (exitTicks in listOf(0, 40)) {
            val settings = skySettings(points = 12, shape = AuroraShape.LINE, packets = 4).let { original ->
                original.copy(profiles = original.profiles.map { it.copy(skyRefreshTicks = 12, sceneDurationTicks = 40,
                    skyOptions = it.skyOptions.copy(exitTicks = exitTicks)) })
            }
            withEngine(settings) { fixture ->
                fixture.event = settings.profiles
                fixture.capture()
                fixture.completePlanning()
                repeat(50) { fixture.engine.tick() }
                (fixture.locations.size > 12) shouldBe true
            }
        }
    }

    "a dropped advancing wave point is repaired next visit without keeping its older tail alive" {
        val settings = skySettings(points = 96, shape = AuroraShape.WAVE, packets = 12).let { original ->
            original.copy(profiles = original.profiles.map { it.copy(shapeSize = 6.0, verticalMin = 20.0,
                verticalMax = 20.0, skyRefreshTicks = 0, sceneDurationTicks = 160) })
        }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture()
            fixture.completePlanning()
            repeat(40) { fixture.engine.tick() }
            fixture.dropNextEmission = true
            fixture.engine.tick()
            val missing = checkNotNull(fixture.droppedEmission)
            val before = fixture.locations.size
            fixture.engine.tick()
            fixture.locations.drop(before).any { it == missing } shouldBe true
            repeat(180) { fixture.engine.tick() }
            fixture.locations.groupingBy { Triple(it.x, it.y, it.z) }.eachCount().values.all { it <= 2 } shouldBe true
            (fixture.locations.size <= 192) shouldBe true
            val stopped = fixture.locations.size
            repeat(80) { fixture.engine.tick() }
            fixture.locations.size shouldBe stopped // No whole-wave hold or old-tail refresh after the head exits.
        }
    }

    "a roof above otherwise free spawn centres rejects the whole sky candidate before its first point" {
        withEngine(skySettings(points = 48, shape = AuroraShape.WAVE)) { fixture ->
            fixture.roof(94)
            fixture.event = fixture.settings.profiles
            repeat(40) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations shouldBe emptyList()
        }
    }

    "new terrain retires an admitted held figure and blocked replacements never start" {
        val settings = skySettings(points = 24, packets = 8).let { original -> original.copy(profiles =
            original.profiles.map { it.copy(skyRefreshTicks = 12, sceneDurationTicks = 240) }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            val before = fixture.locations.size
            (before > 0) shouldBe true
            fixture.roof(90)
            repeat(30) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations.size shouldBe before
        }
    }

    "stationary high sky anchors are not mistaken for viewer movement and restarted each refresh" {
        val settings = skySettings(points = 1).let { original -> original.copy(profiles = original.profiles.map {
            it.copy(radius = 4.0, minDistance = 4.0, verticalMin = 64.0, verticalMax = 64.0, spawnDelayTicks = 0)
        }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            val originY = fixture.player.world.maxHeight - 66.0
            fixture.player.teleport(fixture.player.location.apply { y = originY }) shouldBe true
            repeat(24) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations.size shouldBe 1
            fixture.locations.single().y shouldBe originY + 64.0
        }
    }

    "ordinary retained sky stops when the current viewer biome no longer selects its profile" {
        val settings = skySettings(points = 24).let { original -> original.copy(profiles =
            original.profiles.map { it.copy(biomes = listOf("minecraft:plains")) }) }
        withEngine(settings) { fixture ->
            fixture.permission(true)
            fixture.player.location.block.biome = org.bukkit.block.Biome.PLAINS
            fixture.pass()
            repeat(6) { if (fixture.locations.isEmpty()) fixture.engine.tick() }
            fixture.locations.size shouldBe 1
            fixture.player.location.block.biome = org.bukkit.block.Biome.DESERT
            repeat(4) { fixture.engine.tick() }
            fixture.locations.size shouldBe 1
        }
    }

    "retained sky paths recheck ping lease generation and quit before each native attempt" {
        val settings = skySettings(points = 24)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            fixture.locations.size shouldBe 1
            fixture.currentPing = 251
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.currentPing = 50
            fixture.event = null
            repeat(3) { fixture.engine.tick() }
            fixture.locations.size shouldBe 1
            fixture.event = settings.profiles.map { it.copy() } // Equal values are not the same event generation.
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            fixture.engine.quit(fixture.player.uniqueId)
            fixture.engine.tick()
            fixture.locations.size shouldBe 1
            verify(exactly = 1) { fixture.emitter.close() }
        }
    }

})

private fun withEngine(settings: AuroraSettings = engineSettings(), highSky: Boolean = false, block: (EngineFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            EngineFixture(paper, settings, highSky).use(block)
        }
    }
}

private class EngineFixture(val paper: MockBukkitTestRuntime, val settings: AuroraSettings, highSky: Boolean) : AutoCloseable {
    private val mainThread = Thread.currentThread()
    private val plugin = paper.createSimplePlugin("ArcAuroraEngineTest")
    private val world = if (highSky) org.mockbukkit.mockbukkit.world.WorldMock(Material.DIRT, 0, 192, 64)
        .also { paper.server.addWorld(it) } else paper.addSimpleWorld("ambient")
    val player = EnginePlayer(paper.server)
    var currentPing: Int
        get() = player.observedPing
        set(value) { player.observedPing = value }
    var event: List<AuroraProfile>? = null
    var preview: List<AuroraProfile>? = null
    var dropNextEmission = false
    var droppedEmission: Location? = null
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
            if (dropNextEmission) {
                dropNextEmission = false
                droppedEmission = arg<Location>(1).clone()
                return@answers Unit
            }
            locations += arg<Location>(1).clone()
            emissions += ObservedEmission(arg<Particle>(2), arg<Int>(3),
                listOf(arg<Double>(4), arg<Double>(5), arg<Double>(6), arg<Double>(7)), arg<Boolean>(9), arg<Player>(0).uniqueId)
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

    fun joinAdditional(name: String): java.util.UUID {
        val extra = EnginePlayer(paper.server, name)
        paper.server.addPlayer(extra)
        extra.teleport(Location(world, 0.5, 80.0, 0.5)) shouldBe true
        engine.join(extra)
        return extra.uniqueId
    }

    fun roof(y: Int) { for (x in -12..12) for (z in -12..12) world.getBlockAt(x, y, z).type = Material.STONE }

    fun loadChunks(radius: Int) { for (x in -radius..radius) for (z in -radius..radius) world.loadChunk(x, z) }

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

private data class ObservedEmission(val particle: Particle, val count: Int, val offsets: List<Double>, val force: Boolean, val viewer: java.util.UUID)

/** Override the one controlled observation without spying on MockBukkit's inherited entity methods. */
private class EnginePlayer(server: ServerMock, name: String = "AuroraViewer") : PlayerMock(server, name) {
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
            skyScene = true, sceneDurationTicks = 240, motionPeriodTicks = 400, updateTicks = 1, spawnDelayTicks = 0,
            radius = 8.0, minDistance = 8.0, verticalMin = 10.0, verticalMax = 10.0,
            shape = shape, shapeSize = 2.0, points = points, count = 1, offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0, speed = 0.0,
        ) })
    }
