package ru.arc.aurora

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.paper.particle.PaperViewerParticles
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AuroraNightSkyTest : FreeSpec({
    "night selection uses inclusive world-time bounds and configured world environments" {
        val night = AuroraNightSkySettings(enabled = true, worlds = listOf("world*"))
        listOf(12999L to false, 13000L to true, 23000L to true, 23001L to false, 37000L to true).forEach { (time, active) ->
            night.matches("world_survival", World.Environment.NORMAL, time) shouldBe active
        }
        night.matches("parkour", World.Environment.NORMAL, 15000) shouldBe false
        night.matches("world_nether", World.Environment.NETHER, 15000) shouldBe false
        night.copy(enabled = false).matches("world", World.Environment.NORMAL, 15000) shouldBe false
        val wrapped = night.copy(timeStart = 23000, timeEnd = 1000)
        wrapped.matches("world", World.Environment.NORMAL, 0) shouldBe true
        wrapped.matches("world", World.Environment.NORMAL, 12000) shouldBe false
    }

    "night config stays disabled when absent and validates an enabled preset before replacement" {
        AuroraConfig.parse(YamlConfiguration()).nightSky.enabled shouldBe false
        val source = nightConfig()
        AuroraConfig.parse(source).nightSky.enabled shouldBe true
        listOf(
            "preset" to "missing", "time-start" to 24000, "time-end" to -1,
            "environments" to listOf("MOON"), "worlds" to emptyList<String>(),
        ).forEach { (field, value) ->
            val invalid = nightConfig().apply { set("night-sky.$field", value) }
            shouldThrow<AuroraConfigException> { AuroraConfig.parse(invalid) }.path shouldBe "night-sky.$field"
        }
        source.set("event-presets.night", null)
        shouldThrow<AuroraConfigException> { AuroraConfig.parse(source) }.path shouldBe "night-sky.preset"
    }

    "players without ambient permission receive a retained night scene but no daytime ordinary particles" {
        withNightEngine { fixture ->
            fixture.player.hasPermission(fixture.settings.permission) shouldBe false
            fixture.world.time = 12999
            repeat(3) { fixture.tick() }
            fixture.particles shouldBe emptyList()

            fixture.world.time = 13000
            fixture.awaitParticle(Particle.END_ROD)
            fixture.particles.all { it == Particle.END_ROD } shouldBe true

            val beforeDawn = fixture.particles.size
            fixture.world.time = 23001
            repeat(3) { fixture.tick() }
            fixture.particles.size shouldBe beforeDawn
            verify(atLeast = 1) { fixture.emitter.invalidatePending() }

            fixture.world.time = 13000
            fixture.awaitParticle(Particle.END_ROD)
        }
    }

    "a time-set to day rejects captured nightly work at final delivery before retained dispatch" {
        withNightEngine(skyScene = false) { fixture ->
            fixture.world.time = 15000
            fixture.engine.tick()
            fixture.queued.size shouldBe 1
            fixture.world.time = 6000
            fixture.completePlanning()
            fixture.engine.tick()
            fixture.particles shouldBe emptyList()
            verify(atLeast = 1) { fixture.emitter.invalidatePending() }
        }
    }

    "preview and event precedence retain their toggle bypass while nightly respects personal off" {
        withNightEngine { fixture ->
            fixture.world.time = 15000
            fixture.engine.setPersonallyEnabled(fixture.player, false)
            repeat(3) { fixture.tick() }
            fixture.particles shouldBe emptyList()

            fixture.event = fixture.settings.eventPresets.getValue("event")
            fixture.awaitParticle(Particle.FIREWORK)
            fixture.preview = fixture.settings.eventPresets.getValue("preview")
            fixture.awaitParticle(Particle.ELECTRIC_SPARK)
            fixture.preview = null
            fixture.awaitParticle(Particle.FIREWORK)

            val beforeNightly = fixture.particles.size
            fixture.event = null
            repeat(3) { fixture.tick() }
            fixture.particles.size shouldBe beforeNightly
            fixture.engine.setPersonallyEnabled(fixture.player, true)
            fixture.awaitParticle(Particle.END_ROD)
        }
    }

    "automatic night scenes retain the current and average ping gates" {
        withNightEngine { fixture ->
            fixture.world.time = 15000
            fixture.player.observedPing = 251
            repeat(3) { fixture.tick() }
            fixture.particles shouldBe emptyList()
            fixture.player.observedPing = 50
            fixture.awaitParticle(Particle.END_ROD)
            val beforeSpike = fixture.particles.size
            fixture.player.observedPing = 251
            fixture.tick()
            fixture.particles.size shouldBe beforeSpike
        }
    }
})

private fun withNightEngine(skyScene: Boolean = true, block: (NightEngineFixture) -> Unit) {
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper -> NightEngineFixture(paper, skyScene).use(block) }
    }
}

private class NightEngineFixture(paper: MockBukkitTestRuntime, skyScene: Boolean) : AutoCloseable {
    private val mainThread = Thread.currentThread()
    private val plugin = paper.createSimplePlugin("AuroraNightFixture")
    val world = WorldMock(Material.DIRT, 0, 128, 5).also { it.name = "night_world"; paper.server.addWorld(it) }
    val player = NightPlayer(paper.server)
    val settings = nightlyEngineSettings(skyScene)
    var event: List<AuroraProfile>? = null
    var preview: List<AuroraProfile>? = null
    val queued = ArrayDeque<Runnable>()
    val particles = mutableListOf<Particle>()
    val emitter = mockk<PaperViewerParticles>(relaxed = true)
    private val scheduler = mockk<TaskScheduler>(relaxed = true)
    private val scope = LifecycleTaskScope(scheduler)
    val engine = AuroraEngine(plugin, scope, settings, { event }, { emitter }, { preview })

    init {
        every { scheduler.runAsync(any()) } answers {
            queued.addLast(firstArg())
            mockk<ScheduledTask>(relaxed = true)
        }
        every { emitter.emit(any<Player>(), any<Location>(), any<Particle>(), any<Int>(),
            any<Double>(), any<Double>(), any<Double>(), any<Double>(), any(), any<Boolean>()) } answers {
            check(Thread.currentThread() === mainThread)
            particles += arg<Particle>(2)
        }
        paper.server.addPlayer(player)
        player.teleport(Location(world, 0.5, 80.0, 0.5)) shouldBe true
        for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
        world.getBlockAt(0, 80, 0).biome.key.toString()
        world.getBlockAt(0, 80, 0).lightFromSky
        engine.join(player)
    }

    fun tick() {
        if (queued.isNotEmpty()) completePlanning()
        engine.tick()
    }

    fun completePlanning() {
        val work = queued.removeFirst()
        CompletableFuture.runAsync { work.run() }.get(5, TimeUnit.SECONDS)
    }

    fun awaitParticle(particle: Particle) {
        val before = particles.size
        repeat(60) {
            tick()
            if (particles.drop(before).contains(particle)) return
        }
        error("No $particle emission after the selection changed")
    }

    override fun close() {
        engine.close()
        scope.close()
    }
}

private class NightPlayer(server: ServerMock) : PlayerMock(server, "NightViewer") {
    private val mainThread = Thread.currentThread()
    var observedPing = 50
    override fun getPing(): Int {
        check(Thread.currentThread() === mainThread)
        return observedPing
    }
}

private fun nightlyEngineSettings(skyScene: Boolean): AuroraSettings {
    val base = AuroraConfig.parse(YamlConfiguration().apply { loadFromString("""
        interval-ticks: 10
        native-budget-micros: 10000
        attempts-per-viewer: 1
        packets-per-viewer: 1
        sky-packets-per-viewer: 1
        ping:
          window-samples: 1
          sample-interval-ticks: 1
        profiles:
          ordinary:
            particle: minecraft:small_flame
            shape: POINT
            radius: 4
            min-distance: 2
            vertical-min: 2
            vertical-max: 2
    """.trimIndent()) })
    val profile = base.profiles.single().copy(skyScene = skyScene, points = if (skyScene) 32 else 1, radius = 32.0,
        verticalMin = 10.0, verticalMax = 10.0, shapeSize = 1.0, spawnDelayTicks = 0, updateTicks = 1)
    return base.copy(nightSky = AuroraNightSkySettings(enabled = true, preset = "night"),
        skyField = AuroraSkyFieldSettings(cellSize = 16.0, jitter = 0.0, viewDistance = 32.0, maxScenes = 1),
        eventPresets = mapOf(
            "night" to listOf(profile.copy(id = "night", particle = Particle.END_ROD)),
            "event" to listOf(profile.copy(id = "event", particle = Particle.FIREWORK)),
            "preview" to listOf(profile.copy(id = "preview", particle = Particle.ELECTRIC_SPARK)),
        ))
}

private fun nightConfig() = YamlConfiguration().apply { loadFromString("""
    night-sky:
      enabled: true
      preset: night
    event-presets:
      night:
        effect:
          sky-scene: true
          particle: minecraft:end_rod
    """.trimIndent()) }
