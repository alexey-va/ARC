package ru.arc.ops

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.papermc.paper.plugin.configuration.PluginMeta
import org.bukkit.plugin.Plugin

class OpsHttpHandlersTest :
    FreeSpec({
        "plugin summary reports metadata through the current Paper API" {
            val meta =
                mockk<PluginMeta> {
                    every { version } returns "1.0"
                    every { authors } returns listOf("RusCrafting")
                    every { mainClass } returns "ru.arc.ARC"
                }
            val plugin =
                mockk<Plugin> {
                    every { name } returns "ARC"
                    every { isEnabled } returns true
                    every { pluginMeta } returns meta
                }

            val summary = OpsHttpHandlers.pluginSummary(plugin)

            summary["name"] shouldBe "ARC"
            summary["enabled"] shouldBe true
            summary["status"] shouldBe "ok"
            summary["version"] shouldBe "1.0"
            summary["authors"] shouldBe listOf("RusCrafting")
            summary["main"] shouldBe "ru.arc.ARC"
        }

        "plugin summary reports disabled state consistently" {
            val meta =
                mockk<PluginMeta>(relaxed = true) {
                    every { version } returns "1.0"
                    every { authors } returns emptyList()
                    every { mainClass } returns "example.DisabledPlugin"
                }
            val plugin =
                mockk<Plugin> {
                    every { name } returns "DisabledPlugin"
                    every { isEnabled } returns false
                    every { pluginMeta } returns meta
                }

            val summary = OpsHttpHandlers.pluginSummary(plugin)

            summary["enabled"] shouldBe false
            summary["status"] shouldBe "disabled"
        }
    })
