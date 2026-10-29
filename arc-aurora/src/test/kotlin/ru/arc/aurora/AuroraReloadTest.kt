package ru.arc.aurora

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class AuroraReloadTest : FreeSpec({
    "the reload command replaces valid settings and retains the current engine after an invalid candidate" {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val host = paper.createSimplePlugin("ArcCore")
                paper.createSimplePlugin("packetevents")
                paper.server.servicesManager.register(
                    ArcVisualPacketBudget::class.java,
                    mockk<ArcVisualPacketBudget>(relaxed = true),
                    host,
                    ServicePriority.Normal,
                )
                paper.server.onlinePlayers.size shouldBe 0
                val plugin = paper.loadPlugin(ArcAuroraPlugin::class.java)
                plugin.isEnabled shouldBe true
                val initial = checkNotNull(plugin.engine)
                val profileId = initial.settings.profiles.first().id
                val file = plugin.dataFolder.resolve("config.yml")
                val candidate = YamlConfiguration.loadConfiguration(file)
                candidate.set("profiles.$profileId.radius", 14.0)
                candidate.set("profiles.$profileId.sky-options.wave-amplitude", null)
                candidate.set("sky-defaults.wave-amplitude", 0.31)
                candidate.set("sky-field.cell-size", 52.0)
                candidate.set("sky-field.jitter", 0.25)
                candidate.set("sky-field.view-distance", 112.0)
                candidate.set("sky-field.cull-hysteresis", 24.0)
                candidate.set("sky-field.max-scenes", 8)
                candidate.set("night-sky.enabled", true)
                candidate.set("night-sky.preset", "treasure-hunt")
                candidate.set("night-sky.time-start", 12500)
                candidate.set("night-sky.time-end", 23500)
                candidate.set("night-sky.worlds", listOf("night_*"))
                candidate.set("night-sky.environments", listOf("NORMAL"))
                candidate.save(file)

                paper.server.dispatchCommand(paper.server.consoleSender, "arcaurora reload") shouldBe true

                val replacement = checkNotNull(plugin.engine)
                (replacement === initial) shouldBe false
                val reloaded = replacement.settings.profiles.single { it.id == profileId }
                reloaded.radius shouldBe 14.0
                reloaded.skyOptions.waveAmplitude shouldBe 0.31
                val reloadedField = AuroraSkyFieldSettings(
                    cellSize = 52.0, jitter = 0.25, viewDistance = 112.0, cullHysteresis = 24.0, maxScenes = 8,
                )
                replacement.settings.skyField shouldBe reloadedField
                val reloadedNight = AuroraNightSkySettings(enabled = true, timeStart = 12500,
                    timeEnd = 23500, worlds = listOf("night_*"))
                replacement.settings.nightSky shouldBe reloadedNight

                candidate.set("profiles.$profileId.radius", 20.0)
                candidate.set("sky-defaults.wave-amplitude", 2.0)
                candidate.save(file)

                paper.server.dispatchCommand(paper.server.consoleSender, "arcaurora reload") shouldBe true

                plugin.isEnabled shouldBe true
                (plugin.engine === replacement) shouldBe true
                plugin.engine!!.settings.profiles.single { it.id == profileId }.let { retained ->
                    retained.radius shouldBe 14.0
                    retained.skyOptions.waveAmplitude shouldBe 0.31
                }

                candidate.set("sky-defaults.wave-amplitude", 0.31)
                candidate.set("sky-field.cell-size", 0.0)
                candidate.save(file)

                paper.server.dispatchCommand(paper.server.consoleSender, "arcaurora reload") shouldBe true

                plugin.isEnabled shouldBe true
                (plugin.engine === replacement) shouldBe true
                plugin.engine!!.settings.skyField shouldBe reloadedField
                plugin.engine!!.settings.profiles.single { it.id == profileId }.radius shouldBe 14.0

                candidate.set("sky-field.cell-size", 52.0)
                candidate.set("night-sky.preset", "missing-night-preset")
                candidate.save(file)

                paper.server.dispatchCommand(paper.server.consoleSender, "arcaurora reload") shouldBe true

                (plugin.engine === replacement) shouldBe true
                plugin.engine!!.settings.nightSky shouldBe reloadedNight

                val viewer = paper.addPlayer("NightToggleViewer")
                viewer.hasPermission(replacement.settings.permission) shouldBe false
                paper.server.dispatchCommand(viewer, "arcaurora off") shouldBe true
                replacement.personallyEnabled(viewer) shouldBe false
                paper.server.dispatchCommand(viewer, "arcaurora on") shouldBe true
                replacement.personallyEnabled(viewer) shouldBe true
                paper.server.dispatchCommand(viewer, "arcaurora toggle") shouldBe true
                replacement.personallyEnabled(viewer) shouldBe false
            }
        }
    }
})
