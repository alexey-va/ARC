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
            if (fixture.queued.isEmpty()) fixture.waitForPlanning()
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
        val settings = skySettings(points = 24, packets = 36).let { original -> original.copy(skyField = original.skyField.copy(maxScenes = 2), profiles = listOf(
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
        val settings = skySettings(points = 56, shape = AuroraShape.WAVE, packets = 5).let { original -> original.copy(skyField = original.skyField.copy(maxScenes = 2), profiles = listOf(
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
        val options = AuroraSkyOptions(yawMinDegrees = 0.0, yawMaxDegrees = 0.0, scaleMin = 1.0, scaleMax = 1.0,
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
                val ground = fixture.player.world.getHighestBlockYAt(8, 8, org.bukkit.HeightMap.WORLD_SURFACE)
                fixture.locations.all { it.y == ground + 10.0 && it.z == 8.0 } shouldBe true
                fixture.locations.minOf { it.x } shouldBe 6.0
                fixture.locations.maxOf { it.x } shouldBe 10.0
            }
        }
        val cautious = skySettings(points = 8, shape = AuroraShape.WAVE, packets = 8).let { original ->
            original.copy(profiles = original.profiles.map { it.copy(skyOptions = options.copy(terrainClearance = 12.0)) })
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

    "walking flying and turning beyond the old capture fence never cuts a retained world path" {
        val settings = skySettings(points = 24, shape = AuroraShape.LINE, packets = 2)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture(); fixture.completePlanning()
            repeat(8) { if (fixture.locations.isEmpty()) fixture.engine.tick() }
            val first = fixture.locations.first().clone()
            fixture.player.teleport(fixture.player.location.add(6.0, 8.0, 0.0).apply { yaw = 180f }) shouldBe true
            repeat(40) { fixture.engine.tick() } // Later worker stays blocked while the observer moves.
            fixture.locations.size shouldBe 24
            fixture.locations.toSet().size shouldBe 24
            fixture.locations.all { it.distance(first) < 6.0 } shouldBe true
        }
    }

    "ordinary captured points still reject a viewer who moved beyond four blocks" {
        withEngine { fixture ->
            fixture.permission(true)
            fixture.capture(); fixture.completePlanning()
            fixture.player.teleport(fixture.player.location.add(6.0, 0.0, 0.0)) shouldBe true
            fixture.engine.tick()
            fixture.locations shouldBe emptyList()
        }
    }

    "nearby viewers share world cell geometry despite different yaw and altitude" {
        val settings = skySettings(points = 12, shape = AuroraShape.LINE, packets = 2).copy(viewersPerTick = 2)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            val second = fixture.joinAdditional("ObserverTwo")
            val player = checkNotNull(fixture.paper.server.getPlayer(second))
            player.teleport(player.location.add(1.0, 9.0, 0.0).apply { yaw = 180f }) shouldBe true
            fixture.capture(); fixture.completePlanning()
            repeat(30) { fixture.engine.tick() }
            fun positions(id: java.util.UUID) = fixture.locations.filterIndexed { i, _ -> fixture.emissions[i].viewer == id }
            positions(second) shouldBe positions(fixture.player.uniqueId)
            positions(second).size shouldBe 12
        }
    }

    "whole scene culling cannot revive an old partial head on rapid reentry" {
        val settings = skySettings(points = 96, shape = AuroraShape.LINE, packets = 2)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture(); fixture.completePlanning()
            repeat(8) { fixture.engine.tick() }
            val previous = fixture.locations.first().clone()
            fixture.player.teleport(fixture.player.location.add(80.0, 0.0, 0.0)) shouldBe true
            val before = fixture.locations.size
            fixture.engine.tick()
            fixture.locations.size shouldBe before
            fixture.player.teleport(Location(fixture.player.world, 0.5, 80.0, 0.5)) shouldBe true
            repeat(45) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                val count = fixture.locations.size
                fixture.engine.tick()
                (fixture.locations.size - count <= 1) shouldBe true
            }
            fixture.locations.drop(before).none { it.distance(previous) < 6.0 } shouldBe true
        }
    }

    "unloaded initial admission resumes after loading without rejecting its world cell" {
        withEngine(skySettings(points = 1)) { fixture ->
            fixture.event = fixture.settings.profiles
            fixture.capture(); fixture.completePlanning()
            fixture.player.world.unloadChunk(0, 0) shouldBe true
            repeat(12) { fixture.engine.tick() }
            fixture.locations shouldBe emptyList()
            fixture.player.world.isChunkLoaded(0, 0) shouldBe false
            fixture.player.world.loadChunk(0, 0)
            repeat(8) { fixture.engine.tick() }
            fixture.locations.size shouldBe 1
        }
    }

    "admission delayed across a field epoch retains its own full lifecycle" {
        val settings = skySettings(points = 40).let { original -> original.copy(profiles =
            original.profiles.map { it.copy(sceneDurationTicks = 40) }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.capture(); fixture.completePlanning()
            fixture.player.world.unloadChunk(0, 0) shouldBe true
            repeat(45) { fixture.engine.tick() }
            fixture.player.world.loadChunk(0, 0)
            repeat(8) { if (fixture.locations.isEmpty()) fixture.engine.tick() }
            val admittedId = fixture.sceneRows(0).single().split(',')[1]
            if (fixture.queued.isEmpty()) fixture.waitForPlanning()
            fixture.completePlanning()
            repeat(10) { fixture.engine.tick() }
            fixture.sceneRows(0).single().split(',')[1] shouldBe admittedId
            (fixture.locations.size >= 10) shouldBe true
        }
    }

    "a rejected cell in one world cannot suppress the corresponding clear cell after world change" {
        withEngine(skySettings(points = 24, shape = AuroraShape.WAVE)) { fixture ->
            for (x in 4..12) for (z in 4..12) {
                if (x != 8 || z != 8) fixture.player.world.getBlockAt(x, 94, z).type = Material.STONE
            }
            fixture.event = fixture.settings.profiles
            fixture.capture(); fixture.completePlanning()
            repeat(8) { fixture.engine.tick() }
            fixture.locations shouldBe emptyList()
            val other = fixture.paper.addSimpleWorld("clear-world")
            for (x in -4..4) for (z in -4..4) other.loadChunk(x, z)
            fixture.player.teleport(Location(other, 0.5, 80.0, 0.5)) shouldBe true
            fixture.engine.invalidate(fixture.player.uniqueId)
            repeat(30) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations.any { it.world == other } shouldBe true
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
        val settings = skySettings(points = 20, packets = 1).let { original -> original.copy(skyField = original.skyField.copy(maxScenes = 3), profiles =
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
        val settings = skySettings(points = 192, packets = 1).let { original -> original.copy(viewersPerTick = 1, skyField = original.skyField.copy(maxScenes = 3), profiles =
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

    "invalidating a retained path fences pending output but never uses the new viewing direction" {
        val settings = skySettings(points = 24)
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            repeat(24) { if (fixture.locations.isEmpty()) fixture.engine.tick() }
            (fixture.locations.single().z > 0.5) shouldBe true
            fixture.player.teleport(fixture.player.location.apply { yaw = 180f }) shouldBe true
            fixture.engine.invalidate(fixture.player.uniqueId)
            val before = fixture.locations.size
            if (fixture.queued.isNotEmpty()) fixture.completePlanning() // Complete any pre-invalidation worker.
            repeat(3) { fixture.engine.tick() }
            fixture.locations.size shouldBe before
            if (fixture.queued.isEmpty()) fixture.waitForPlanning()
            fixture.completePlanning()
            repeat(24) { if (fixture.locations.size == before) fixture.engine.tick() }
            (fixture.locations.size > before) shouldBe true
            (fixture.locations.last().z > 0.5) shouldBe true
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

    "bundled world figures preserve budgets through actual observer movement turns and cell crossings" {
        val bundled = AuroraConfig.parse(YamlConfiguration().apply {
            loadFromString(checkNotNull(javaClass.classLoader.getResourceAsStream("config.yml")).bufferedReader().use { it.readText() })
        }).copy(nativeBudgetMicros = 10000)
        withEngine(bundled, highSky = true) { fixture ->
            for (x in -8..24) for (z in -8..8) fixture.player.world.loadChunk(x, z)
            fixture.event = bundled.eventPresets.getValue("treasure-hunt")
            val timeline = StringBuilder("tick,particle,x,y,z\n")
            val observer = StringBuilder("tick,x,y,z,yaw\n")
            val scenes = StringBuilder("tick,id,profile,particle,cell_x,cell_z,epoch,x,y,z,starts_at,culled,rejected\n")
            repeat(1000) { tick ->
                val origin = when {
                    tick < 160 -> doubleArrayOf(0.5, 80.0, 0.5, 0.0)
                    tick < 320 -> { val t = (tick - 160).toDouble(); doubleArrayOf(0.5 + t * 0.1, 80.0 + 3.0 * kotlin.math.sin(t / 160.0 * Math.PI),
                        0.5 + t * 0.05, t / 160.0 * 180.0) }
                    tick < 480 -> { val t = (tick - 320).toDouble(); doubleArrayOf(16.5 + t * 0.5, 80.0 + t * 0.05, 8.5, 180.0 + t / 160.0 * 180.0) }
                    tick < 560 -> { val t = (tick - 480).toDouble(); doubleArrayOf(96.5 + t, 88.0, 8.5, 360.0 + t / 80.0 * 180.0) }
                    tick < 640 -> { val t = (tick - 560).toDouble(); doubleArrayOf(176.5 - t * 2.2, 88.0 - t * 0.1, 8.5 - t * 0.1, 540.0 + t / 80.0 * 180.0) }
                    else -> doubleArrayOf(0.5, 80.0, 0.5, (tick - 640).toDouble())
                }
                fixture.player.teleport(Location(fixture.player.world, origin[0], origin[1], origin[2], origin[3].toFloat(), 0f)) shouldBe true
                observer.append("$tick,${origin.joinToString(",")}\n")
                if (fixture.queued.isNotEmpty() && tick % 2 == 0) fixture.completePlanning()
                val before = fixture.emissions.size
                fixture.engine.tick()
                val sent = fixture.emissions.drop(before)
                (sent.size <= bundled.skyPacketsPerViewer) shouldBe true
                (sent.count { it.particle == Particle.FIREWORK } <= 30) shouldBe true // Two cubes plus two brief meteors.
                for (index in before until fixture.emissions.size) {
                    val at = fixture.locations[index]
                    timeline.append("$tick,${fixture.emissions[index].particle.name},${at.x},${at.y},${at.z}\n")
                }
                fixture.sceneRows(tick).forEach { scenes.append(it).append('\n') }
            }
            fixture.emissions.map { it.particle }.toSet() shouldBe setOf(Particle.END_ROD, Particle.FIREWORK, Particle.SOUL_FIRE_FLAME, Particle.FLAME)
            System.getenv("ARCAURORA_PREVIEW_CSV")?.let { path ->
                val target = java.nio.file.Path.of(path)
                java.nio.file.Files.writeString(target, timeline)
                java.nio.file.Files.writeString(target.resolveSibling("observer-timeline.csv"), observer)
                java.nio.file.Files.writeString(target.resolveSibling("scene-timeline.csv"), scenes)
            }
        }
    }

    "custom outlines grow through two heads revisit every held point and expire without a backlog" {
        val bundled = AuroraConfig.parse(YamlConfiguration().apply {
            loadFromString(checkNotNull(javaClass.classLoader.getResourceAsStream("config.yml")).bufferedReader().use { it.readText() })
        })
        for (id in listOf("sky_crystal", "sky_star", "sky_meteor")) {
            val profile = bundled.eventPresets.getValue("treasure-hunt").single { it.id == id }
            val settings = bundled.copy(nativeBudgetMicros = 10000,
                skyField = bundled.skyField.copy(maxScenes = 1, maxPerProfile = 1))
            withEngine(settings, highSky = true) { fixture ->
                fixture.loadChunks(8)
                fixture.event = listOf(profile)
                val timeline = StringBuilder("tick,particle,x,y,z\n")
                val observer = StringBuilder("tick,x,y,z,yaw\n")
                val scenes = StringBuilder("tick,id,profile,particle,cell_x,cell_z,epoch,x,y,z,starts_at,culled,rejected\n")
                val heldIndices = mutableSetOf<Int>()
                var firstEmissionCount = 0
                val emissionTicks = mutableSetOf<Int>()
                repeat(500) { tick ->
                    if (tick == 1) fixture.completePlanning()
                    val before = fixture.locations.size
                    fixture.engine.tick() // Only the first worker completes: the retained outline must pace itself.
                    val sent = fixture.locations.drop(before)
                    if (sent.isNotEmpty()) emissionTicks += tick
                    (sent.size <= 6) shouldBe true
                    if (sent.isNotEmpty() && firstEmissionCount == 0) firstEmissionCount = sent.size
                    val expected = fixture.currentScenePoints()
                    if (profile.skyRefreshTicks > 0 && tick in 180..227) for (at in sent) {
                        val index = expected.indices.minBy { expected[it].distanceSquared(at) }
                        (expected[index].distanceSquared(at) < 1e-18) shouldBe true
                        // The final closed-path sample shares the first point's position.
                        heldIndices += if (index == expected.lastIndex) 0 else index
                    }
                    if (tick >= 470) sent.size shouldBe 0
                    for (index in before until fixture.locations.size) {
                        val at = fixture.locations[index]
                        timeline.append("$tick,${fixture.emissions[index].particle.name},${at.x},${at.y},${at.z}\n")
                    }
                    fixture.sceneRows(tick).forEach { scenes.append(it).append('\n') }
                    val at = fixture.player.location
                    observer.append("$tick,${at.x},${at.y},${at.z},${at.yaw}\n")
                }
                firstEmissionCount shouldBe profile.skyTracers
                if (profile.skyRefreshTicks > 0) heldIndices.size shouldBe profile.points - 1
                else {
                    fixture.locations.size shouldBe 80
                    emissionTicks.size shouldBe 20
                    emissionTicks.max() - emissionTicks.min() shouldBe 19
                }
                System.getenv("ARCAURORA_CUSTOM_PREVIEW_DIR")?.let { directory ->
                    val target = java.nio.file.Path.of(directory, id)
                    java.nio.file.Files.createDirectories(target)
                    java.nio.file.Files.writeString(target.resolve("engine-timeline.csv"), timeline)
                    java.nio.file.Files.writeString(target.resolve("observer-timeline.csv"), observer)
                    java.nio.file.Files.writeString(target.resolve("scene-timeline.csv"), scenes)
                }
            }
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
            for (x in 4..12) for (z in 4..12) {
                if (x != 8 || z != 8) fixture.player.world.getBlockAt(x, 94, z).type = Material.STONE
            }
            fixture.event = fixture.settings.profiles
            fixture.capture(); fixture.completePlanning()
            repeat(40) { fixture.engine.tick() }
            fixture.locations shouldBe emptyList()
        }
    }

    "new terrain retires an admitted figure and any replacement clears a different footprint" {
        val settings = skySettings(points = 24, packets = 8).let { original -> original.copy(profiles =
            original.profiles.map { it.copy(skyRefreshTicks = 12, sceneDurationTicks = 240) }) }
        withEngine(settings) { fixture ->
            fixture.event = settings.profiles
            fixture.pass()
            val before = fixture.locations.size
            (before > 0) shouldBe true
            val original = fixture.locations.last().clone()
            fixture.roof(original.blockY)
            repeat(30) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations.drop(before).none { it.distance(original) < 4.0 } shouldBe true
        }
    }

    "stationary high sky anchors are not mistaken for viewer movement and restarted each refresh" {
        val settings = skySettings(points = 1).let { original -> original.copy(profiles = original.profiles.map {
            it.copy(verticalMin = 64.0, verticalMax = 64.0, spawnDelayTicks = 0)
        }) }
        withEngine(settings, highSky = true) { fixture ->
            fixture.event = settings.profiles
            val originY = fixture.player.world.maxHeight - 66.0
            fixture.player.teleport(fixture.player.location.apply { y = originY }) shouldBe true
            repeat(24) {
                if (fixture.queued.isNotEmpty()) fixture.completePlanning()
                fixture.engine.tick()
            }
            fixture.locations.size shouldBe 1
            fixture.locations.single().y shouldBe fixture.player.world.getHighestBlockYAt(8, 8, org.bukkit.HeightMap.WORLD_SURFACE) + 64.0
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
    private val world = EngineWorld(highSky).also { it.name = "ambient"; paper.server.addWorld(it) }
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
    val engine = AuroraEngine(plugin, scope, settings, { if (it == player.world.uid) event else null }, { emitter },
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
        for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
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

    /** Export the actual retained Main scene state; reflection is confined to this diagnostic fixture. */
    fun sceneRows(tick: Int): List<String> {
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)
        val viewers = field(engine, "viewers") as Map<*, *>
        val viewer = viewers[player.uniqueId] ?: return emptyList()
        val sky = field(viewer, "sky") ?: return emptyList()
        return (field(sky, "scenes") as List<*>).filterNotNull().map { scene ->
            val profile = field(scene, "profile") as AuroraProfile
            val cell = field(scene, "cell") as AuroraSkyCell
            val epoch = field(scene, "epoch") as Long
            val surface = field(scene, "surfaceY") as Int?
            val start = field(scene, "startsAt") as Long
            val culled = (field(scene, "culled") as java.util.concurrent.atomic.AtomicBoolean).get()
            val rejected = (field(scene, "rejected") as java.util.concurrent.atomic.AtomicBoolean).get()
            listOf(tick, "${cell.x}:${cell.z}:$epoch:${profile.id}", profile.id, profile.particle.name,
                cell.x, cell.z, epoch, field(scene, "x"), (field(scene, "y") as Double) + (surface ?: 0), field(scene, "z"),
                if (start == Long.MAX_VALUE) -1 else start, culled, rejected).joinToString(",")
        }
    }

    /** Current world-space path for the single-scene native-submission oracle; test-only reflection. */
    fun currentScenePoints(): List<Location> {
        fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)
        val viewer = (field(engine, "viewers") as Map<*, *>)[player.uniqueId] ?: return emptyList()
        val sky = field(viewer, "sky") ?: return emptyList()
        val scene = (field(sky, "scenes") as List<*>).firstOrNull() ?: return emptyList()
        val surface = field(scene, "surfaceY") as Int? ?: return emptyList()
        val age = (field(engine, "age") as Long) - (field(scene, "startsAt") as Long)
        if (age < 0) return emptyList()
        val motion = field(scene, "motion") as List<*>
        val shift = motion[minOf(age.toInt(), motion.lastIndex)] as AuroraOffset
        val nx = field(scene, "normalX") as Double
        val nz = field(scene, "normalZ") as Double
        return (field(scene, "path") as List<*>).map { raw ->
            val p = raw as AuroraOffset
            Location(player.world, (field(scene, "x") as Double) + (p.x + shift.x) * nz + (p.z + shift.z) * nx,
                (field(scene, "y") as Double) + surface + p.y + shift.y,
                (field(scene, "z") as Double) - (p.x + shift.x) * nx + (p.z + shift.z) * nz)
        }
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

private class EngineWorld(highSky: Boolean) : org.mockbukkit.mockbukkit.world.WorldMock(
    Material.DIRT, 0, if (highSky) 192 else 128, if (highSky) 64 else 5,
) {
    override fun getUID(): java.util.UUID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
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
        original.copy(intervalTicks = 10, packetsPerViewer = packets, skyPacketsPerViewer = packets,
            skyField = AuroraSkyFieldSettings(cellSize = 16.0, jitter = 0.0, viewDistance = 48.0, maxScenes = 1, maxPerProfile = 1), profiles = original.profiles.map { it.copy(
            skyScene = true, sceneDurationTicks = 240, motionPeriodTicks = 400, updateTicks = 1, spawnDelayTicks = 0,
            radius = 64.0, minDistance = 2.0, verticalMin = 10.0, verticalMax = 10.0,
            shape = shape, shapeSize = 2.0, points = points, count = 1, offsetX = 0.0, offsetY = 0.0, offsetZ = 0.0, speed = 0.0,
        ) })
    }
