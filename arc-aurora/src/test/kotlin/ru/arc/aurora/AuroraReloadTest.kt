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
                candidate.save(file)

                paper.server.dispatchCommand(paper.server.consoleSender, "arcaurora reload") shouldBe true

                val replacement = checkNotNull(plugin.engine)
                (replacement === initial) shouldBe false
                val reloaded = replacement.settings.profiles.single { it.id == profileId }
                reloaded.radius shouldBe 14.0
                reloaded.skyOptions.waveAmplitude shouldBe 0.31

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
            }
        }
    }
})
