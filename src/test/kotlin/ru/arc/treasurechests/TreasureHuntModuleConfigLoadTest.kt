package ru.arc.treasurechests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.bukkit.Particle
import ru.arc.config.ConfigManager
import ru.arc.config.TestConfig
import java.nio.file.Files

class TreasureHuntModuleConfigLoadTest :
    DescribeSpec({
        beforeEach { ConfigManager.clear() }
        afterEach { ConfigManager.clear() }

        describe("bundled treasure hunt settings") {
            it("exposes the configured hunt presets") {
                val types = loadConfig().loadHuntTypes()

                types.keys shouldContain "spawn"
                types.keys shouldContain "easter"
                types.keys shouldContain "halloween"
                types.keys shouldContain "vanilla"
            }

            it("uses the portable spawn pool and the configured model counts") {
                val types = loadConfig().loadHuntTypes()

                types.values.forEach { it.locationPoolId shouldBe "spawn" }
                types.getValue("spawn").chestTypes.size() shouldBe 3
                types.getValue("easter").chestTypes.size() shouldBe 1
                types.getValue("halloween").chestTypes.size() shouldBe 1
                types.getValue("vanilla").chestTypes.size() shouldBe 1

                for (type in types.values) {
                    type.chestTypes.values().forEach {
                        it.treasurePoolId shouldBe "spawn_hunt"
                    }
                }

                types.getValue("vanilla").chestTypes.values().single().particlePath shouldBe
                    "default"
                types.getValue("easter").chestTypes.values().single().particlePath shouldBe
                    "easter"
                types.getValue("halloween").chestTypes.values().single().particlePath shouldBe
                    "halloween"
            }

            it("uses the shared announcement, lifetime, fireworks, and boss bar contract") {
                val types = loadConfig().loadHuntTypes()

                for (type in types.values) {
                    type.timeoutSeconds shouldBe 1800L
                    type.bossBar.visible shouldBe true
                    type.bossBar.color shouldBe net.kyori.adventure.bossbar.BossBar.Color.YELLOW
                    type.announcements.announceStart shouldBe true
                    type.announcements.announceStartGlobally shouldBe true
                    type.effects.launchFireworks shouldBe false
                }
            }

            it("keeps the Easter and Halloween aliases available") {
                val aliases = loadConfig().aliases

                aliases.keys shouldBe setOf("easter", "halloween")
            }

            it("loads the configured idle update and sound cadence") {
                val particles = loadConfig().particles

                particles.idleTicks shouldBe 5L
                particles.playerSoundEach shouldBe 11
            }

            it("loads the same idle and claim appearance contract for all three paths") {
                val particles = loadConfig().particles

                for (path in listOf("default", "easter", "halloween")) {
                    val idle = particles.getIdleConfig(path)
                    idle.particle shouldBe Particle.END_ROD
                    idle.count shouldBe 3
                    idle.offset shouldBe 0.35
                    idle.extra shouldBe 0.01
                    idle.radius shouldBe 24
                    idle.soundRadius shouldBe 12
                    idle.sound shouldBe "minecraft:block.amethyst_block.chime"
                    idle.soundVolume shouldBe 1.2f
                    idle.soundPitch shouldBe 1.3f

                    val claimed = particles.getClaimedConfig(path)
                    claimed.particle shouldBe Particle.FIREWORK
                    claimed.count shouldBe 18
                    claimed.offset shouldBe 0.32
                    claimed.extra shouldBe 0.04
                    claimed.sound shouldBe "minecraft:entity.experience_orb.pickup"
                    claimed.soundVolume shouldBe 1.0f
                    claimed.soundPitch shouldBe 1.25f
                }
            }
        }

        describe("sound level validation") {
            it("falls back for nonfinite or nonpositive configured levels") {
                val config = TestConfig(
                    mapOf(
                        "idle.default.sound-volume" to Double.POSITIVE_INFINITY,
                        "idle.default.sound-pitch" to 0.0,
                    ),
                )
                val idle = ParticleSettings(config).getIdleConfig("default")

                idle.soundVolume shouldBe 1.0f
                idle.soundPitch shouldBe 1.0f
            }
        }
    })

private fun loadConfig(): TreasureHuntModuleConfig =
    TreasureHuntModuleConfig.load(Files.createTempDirectory("treasure-hunt-config-test"))
