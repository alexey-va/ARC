package ru.arc.cosmetics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.TestTaskScheduler
import ru.arc.core.Tasks
import java.net.URLClassLoader
import java.util.UUID

class PlayerParticlesGatewayTest : StringSpec({
    "production replacement derivation requires exact multiplicity and rejects extras" {
        val first = part("first")
        val extra = ParticlePresetSignature("flame", "halo", "extra")
        val preset = listOf(first, first)
        fun derive(active: List<ParticlePresetSignature>) = deriveParticlePresetOption(
            "duplicates", "Duplicates", preset, active.groupingBy { it }.eachCount(), true, false,
        )
        derive(listOf(first)).active shouldBe false
        derive(listOf(first, first)).active shouldBe true
        derive(listOf(first, first, extra)).active shouldBe false
        expectedParticlePresetTarget(mapOf(extra to 1), preset, true, false) shouldBe mapOf(first to 2)
    }

    "production one-per-style derivation uses last target per style and retains unrelated extras" {
        val first = ParticlePresetSignature("cloud", "overhead", "first")
        val last = ParticlePresetSignature("rain", "overhead", "last")
        val extra = ParticlePresetSignature("flame", "halo", "extra")
        val preset = listOf(first, last, last)
        val before = mapOf(first to 1, last to 2, extra to 1)
        val effective = expectedParticlePresetTarget(before, preset, false, true)
        effective shouldBe mapOf(last to 1, extra to 1)
        deriveParticlePresetOption("raincloud", "Raincloud", preset, before, false, true).active shouldBe false
        deriveParticlePresetOption("raincloud", "Raincloud", preset, effective, false, true).active shouldBe true
        deriveParticlePresetOption("raincloud", "Raincloud", preset, mapOf(last to 2), false, true).active shouldBe false
    }

    "production additive derivation counts duplicates while allowing extras and repeat application" {
        val first = part("target")
        val extra = ParticlePresetSignature("flame", "halo", "extra")
        val preset = listOf(first, first)
        deriveParticlePresetOption("double", "Double", preset, mapOf(first to 1, extra to 1), false, false)
            .active shouldBe false
        val before = mapOf(first to 2, extra to 1)
        deriveParticlePresetOption("double", "Double", preset, before, false, false).active shouldBe true
        val repeated = expectedParticlePresetTarget(before, preset, false, false)
        repeated shouldBe mapOf(first to 4, extra to 1)
        deriveParticlePresetOption("double", "Double", preset, repeated, false, false).active shouldBe true
    }

    "all native permitted presets stay available, including non-certificate presets" {
        val f = fixture()
        f.gateway.snapshot(f.player).presets.map { it.id } shouldBe listOf("vendor_preset", "other_native")
        f.backend.online = false
        f.gateway.snapshot(f.player).available shouldBe false
    }

    "permission loss, changed active data, and changed preset definitions reject captured confirmation" {
        val f = fixture()
        val captured = f.gateway.snapshot(f.player)
        f.backend.state = captured.copy(presets = emptyList())
        f.gateway.applyPreset(f.player, "vendor_preset", captured).join().applied shouldBe false
        f.backend.state = state(active = listOf(part("same_count_different_data")))
        f.gateway.applyPreset(f.player, "vendor_preset", captured).join().applied shouldBe false
        f.backend.state = captured.copy(presets = listOf(option("vendor_preset", listOf(part("changed_preset")))))
        f.gateway.applyPreset(f.player, "vendor_preset", captured).join().applied shouldBe false
        f.gateway.reset(f.player, captured.copy(playerId = UUID.randomUUID())).join().applied shouldBe false
        f.backend.commands shouldBe emptyList()
    }

    "replacement succeeds only after exact native data readback, not dispatch or equal count" {
        val f = fixture()
        val result = f.gateway.applyPreset(f.player, "vendor_preset", f.gateway.snapshot(f.player))
        f.backend.commands shouldBe listOf("pp group load vendor_preset")
        result.isDone shouldBe false
        f.scheduler.tick(1)
        result.isDone shouldBe false
        f.backend.state = state(active = listOf(part("target")))
        f.scheduler.tick(1)
        result.join().applied shouldBe true
        result.join().snapshot.activeCount shouldBe 1
    }

    "native additive mode preserves old particles and native one-per-style mode replaces matching styles" {
        val f = fixture()
        val original = part("old")
        val target = part("target")
        f.backend.state = state(active = listOf(original)).copy(presetsReplaceActive = false)
        val additive = f.gateway.applyPreset(f.player, "vendor_preset", f.gateway.snapshot(f.player))
        f.backend.state = state(active = listOf(original, target)).copy(presetsReplaceActive = false)
        f.scheduler.tick(1)
        additive.join().applied shouldBe true

        f.backend.state = state(active = listOf(original)).copy(presetsReplaceActive = false, onePerStyle = true)
        val exclusive = f.gateway.applyPreset(f.player, "vendor_preset", f.gateway.snapshot(f.player))
        f.backend.state = state(active = listOf(target)).copy(presetsReplaceActive = false, onePerStyle = true)
        f.scheduler.tick(1)
        exclusive.join().applied shouldBe true
    }

    "mode drift, unsafe native ids and saved-group shadowing never dispatch another action" {
        val f = fixture()
        val captured = f.gateway.snapshot(f.player)
        f.backend.state = captured.copy(presetsReplaceActive = false)
        f.gateway.applyPreset(f.player, "vendor_preset", captured).join().applied shouldBe false
        f.backend.state = captured.copy(shadowedPresetIds = setOf("vendor_preset"))
        f.gateway.applyPreset(f.player, "vendor_preset", captured).join().applied shouldBe false
        f.backend.state = captured.copy(presets = listOf(option("bad reset", listOf(part("target")))))
        f.gateway.applyPreset(f.player, "bad reset", f.gateway.snapshot(f.player)).join().applied shouldBe false
        f.backend.commands shouldBe emptyList()
    }

    "a pending command excludes overlapping mutations and unconfirmed dispatch has bounded readback" {
        val f = fixture()
        val captured = f.gateway.snapshot(f.player)
        val result = f.gateway.applyPreset(f.player, "vendor_preset", captured)
        f.gateway.reset(f.player, captured).join().applied shouldBe false
        f.gateway.setVisible(f.player, false).join().applied shouldBe false
        f.backend.commands shouldBe listOf("pp group load vendor_preset")
        f.scheduler.tick(40)
        result.join().applied shouldBe false
        f.backend.failures shouldBe listOf("readback_unconfirmed:pp group load vendor_preset")
        f.scheduler.pendingCount() shouldBe 0
    }

    "reset and visibility use only actor commands and confirm actual state" {
        val f = fixture()
        val reset = f.gateway.reset(f.player, f.gateway.snapshot(f.player))
        f.backend.state = state(active = emptyList())
        f.scheduler.tick(1)
        reset.join().applied shouldBe true
        val hide = f.gateway.setVisible(f.player, false)
        f.scheduler.tick(1)
        hide.isDone shouldBe false
        f.backend.state = f.backend.state.copy(visible = false)
        f.scheduler.tick(1)
        hide.join().applied shouldBe true
        f.backend.commands shouldBe listOf("pp reset", "pp toggle off")
    }

    "failed dispatch and disconnected actor are not successful changes; close cancels pending polls" {
        val f = fixture()
        f.backend.accepted = false
        f.gateway.reset(f.player, f.gateway.snapshot(f.player)).join().applied shouldBe false
        f.backend.accepted = true
        val disconnected = f.gateway.setVisible(f.player, false)
        f.backend.online = false
        f.scheduler.tick(1)
        disconnected.join().applied shouldBe false
        f.backend.online = true
        val pending = f.gateway.setVisible(f.player, false)
        f.gateway.close()
        pending.join().applied shouldBe false
        f.scheduler.pendingCount() shouldBe 0
        f.gateway.snapshot(f.player).available shouldBe false
    }

    "production gateway loads and method discovery works without PlayerParticles API classes" {
        val plugin = mockk<Plugin>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        every { plugin.isEnabled } returns true
        every { player.isOnline } returns true
        every { player.isDead } returns false
        every { player.uniqueId } returns PLAYER_ID
        every { plugin.server.getPlayer(PLAYER_ID) } returns player
        every { plugin.server.pluginManager.getPlugin("PlayerParticles") } returns null
        val location = PlayerParticlesGateway::class.java.protectionDomain.codeSource.location
        val loader = object : URLClassLoader(arrayOf(location), PlayerParticlesGateway::class.java.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name.startsWith("dev.esophose.playerparticles.")) throw ClassNotFoundException(name)
                if (name.startsWith("ru.arc.cosmetics.PlayerParticlesGateway") || name.startsWith("ru.arc.cosmetics.ParticlePreset")) {
                    return findLoadedClass(name) ?: findClass(name).also { if (resolve) resolveClass(it) }
                }
                return super.loadClass(name, resolve)
            }
        }
        Tasks.withScheduler(TestTaskScheduler()) {
            loader.use {
                val type = it.loadClass(PlayerParticlesGateway::class.java.name)
                type.declaredMethods.toList().isNotEmpty() shouldBe true
                val gateway = type.getConstructor(Plugin::class.java).newInstance(plugin)
                val snapshot = type.getMethod("snapshot", Player::class.java).invoke(gateway, player)
                snapshot.javaClass.getMethod("getAvailable").invoke(snapshot) shouldBe false
                (gateway as AutoCloseable).close()
            }
        }
    }
})

private val PLAYER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000813")
private fun part(data: String) = ParticlePresetSignature("dust", "wings", data)
private fun option(id: String, particles: List<ParticlePresetSignature>) = ParticlePresetOption(id, id, false, particles)
private fun state(active: List<ParticlePresetSignature> = listOf(part("old"))) = ParticlePresetsSnapshot(
    available = true,
    presets = listOf(option("vendor_preset", listOf(part("target"))), option("other_native", listOf(part("other")))),
    activeCount = active.size,
    visible = true,
    playerId = PLAYER_ID,
    activeParticles = active.groupingBy { it }.eachCount(),
)

private class ParticleBackend : PlayerParticlesGateway.Backend {
    var state = state()
    var online = true
    var accepted = true
    val commands = mutableListOf<String>()
    val failures = mutableListOf<String>()
    override fun snapshot(player: Player) = state
    override fun current(player: Player) = online
    override fun execute(player: Player, command: String): Boolean { commands += command; return accepted }
    override fun failure(player: Player, operation: String, failure: Throwable?) { failures += operation }
}

private class ParticleFixture {
    val backend = ParticleBackend()
    val scheduler = TestTaskScheduler()
    val player = mockk<Player>().also { every { it.uniqueId } returns PLAYER_ID }
    val gateway = PlayerParticlesGateway(backend, scheduler)
}

private fun fixture() = ParticleFixture()
