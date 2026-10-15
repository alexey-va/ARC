package ru.arc.dungeons

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.Listener
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.arc.hooks.elitemobs.EMDungeonQol
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.hooks.elitemobs.DungeonSharedSkillXp
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.sync.EmSync
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Level

class ArcDungeonsNativeIntegrationLifecycleTest {
    @Test
    fun `native construction and reload failures are contained and cleanup clears every handle`() {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ArcDungeonsPlugin::class.java)
            // The missing host intentionally self-disables this fixture before native reload is invoked.
            assertFalse(plugin.isEnabled)
            val eliteMobs = paper.createSimplePlugin("EliteMobs")
            if (!eliteMobs.isEnabled) paper.server.pluginManager.enablePlugin(eliteMobs)
            assertTrue(eliteMobs.isEnabled)

            // No host configuration was registered, so the native factory cannot read its legacy path.
            val startupLogs = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { startupLogs += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            plugin.logger.addHandler(handler)
            try {
                invokeNativeReload(plugin)
            } finally {
                plugin.logger.removeHandler(handler)
            }
            assertTrue(startupLogs.any {
                it.level == Level.SEVERE &&
                    it.message.contains("EliteMobs integration is unavailable") &&
                    it.thrown != null
            })
            assertNull(plugin.emHook)
            assertNull(plugin.dungeonQol)
            assertNull(plugin.emSync)

            val hook = mockk<EMHook>(relaxed = true)
            val qol = mockk<EMDungeonQol>(relaxed = true)
            val sync = mockk<EmSync>(relaxed = true)
            val sharedXp = mockk<DungeonSharedSkillXp>(relaxed = true)
            every { hook.reload() } throws IllegalStateException("injected hook reload failure")
            every { qol.close() } throws IllegalStateException("injected QOL cleanup failure")
            every { hook.close() } throws IllegalStateException("injected hook cleanup failure")
            every { sync.close() } throws IllegalStateException("injected sync cleanup failure")

            setField(plugin, "emHook", hook)
            setField(plugin, "dungeonQol", qol)
            setField(plugin, "emSync", sync)
            setField(plugin, "sharedSkillXp", sharedXp)
            @Suppress("UNCHECKED_CAST")
            val listeners = field(plugin, "nativeListeners").get(plugin) as MutableList<Listener>
            listeners += mockk<Listener>(relaxed = true)

            // A native reload failure must be contained; closeNative must still visit every handle.
            invokeNativeReload(plugin)

            assertNull(plugin.emHook)
            assertNull(plugin.dungeonQol)
            assertNull(plugin.emSync)
            assertNull(field(plugin, "sharedSkillXp").get(plugin))
            assertTrue(listeners.isEmpty())
            verify(exactly = 1) { hook.reload() }
            verify(exactly = 1) { qol.close() }
            verify(exactly = 1) { hook.close() }
            verify(exactly = 1) { sync.close() }
            verify(exactly = 1) { sharedXp.close() }
        }
    }

    private fun invokeNativeReload(plugin: ArcDungeonsPlugin) {
        ArcDungeonsPlugin::class.java.getDeclaredMethod("reloadNative").apply { isAccessible = true }.invoke(plugin)
    }

    private fun setField(plugin: ArcDungeonsPlugin, name: String, value: Any?) {
        field(plugin, name).set(plugin, value)
    }

    private fun field(plugin: ArcDungeonsPlugin, name: String) =
        ArcDungeonsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }
}
