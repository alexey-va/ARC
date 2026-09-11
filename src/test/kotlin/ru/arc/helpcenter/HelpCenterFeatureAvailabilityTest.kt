package ru.arc.helpcenter

import org.bukkit.command.CommandExecutor
import org.bukkit.command.PluginCommand
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.paper.testing.MockBukkitTestRuntime

class HelpCenterFeatureAvailabilityTest {
    @Test
    fun `mine lift requires an enabled plugin with its command executor installed`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcFarms")
            val gateway = BukkitHelpCenterGateway()
            assertFalse(HelpCenterFeature.MINE_LIFT in gateway.features())
            val command = PluginCommand::class.java.getDeclaredConstructor(String::class.java, Plugin::class.java)
                .apply { isAccessible = true }.newInstance("minelift", plugin)
            paper.server.commandMap.register("arcfarms", command)
            assertFalse(HelpCenterFeature.MINE_LIFT in gateway.features())
            command.setExecutor(CommandExecutor { _, _, _, _ -> true })
            assertTrue(HelpCenterFeature.MINE_LIFT in gateway.features())
            paper.server.pluginManager.disablePlugin(plugin)
            assertFalse(HelpCenterFeature.MINE_LIFT in gateway.features())
        }
    }
}
