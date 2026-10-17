package ru.arc.dungeons

import com.magmaguy.elitemobs.MetadataHandler
import com.magmaguy.elitemobs.PluginState
import com.magmaguy.elitemobs.api.EliteMobsInitializedEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.junit.jupiter.api.Test
import ru.arc.hooks.elitemobs.EMDungeonQol
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.hooks.elitemobs.DungeonSharedSkillXp
import ru.arc.hooks.elitemobs.EliteMobsLifecycleListener
import ru.arc.hooks.elitemobs.PassiveClassCompatibility
import ru.arc.hooks.elitemobs.arena.NativeArenaLevels
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
            plugin.isEnabled shouldBe false
            val eliteMobs = paper.createSimplePlugin("EliteMobs")
            if (!eliteMobs.isEnabled) paper.server.pluginManager.enablePlugin(eliteMobs)
            eliteMobs.isEnabled shouldBe true

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
            startupLogs.any {
                it.level == Level.SEVERE &&
                    it.message.contains("EliteMobs integration is unavailable") &&
                    it.thrown != null
            } shouldBe true
            plugin.emHook.shouldBeNull()
            plugin.dungeonQol.shouldBeNull()
            plugin.emSync.shouldBeNull()

            val hook = mockk<EMHook>(relaxed = true)
            val qol = mockk<EMDungeonQol>(relaxed = true)
            val sync = mockk<EmSync>(relaxed = true)
            val sharedXp = mockk<DungeonSharedSkillXp>(relaxed = true)
            val passives = mockk<PassiveClassCompatibility>(relaxed = true)
            val arena = mockk<NativeArenaLevels>(relaxed = true)
            every { arena.close() } throws IllegalStateException("injected arena cleanup failure")
            every { hook.reload() } throws IllegalStateException("injected hook reload failure")
            every { qol.close() } throws IllegalStateException("injected QOL cleanup failure")
            every { hook.close() } throws IllegalStateException("injected hook cleanup failure")
            every { sync.close() } throws IllegalStateException("injected sync cleanup failure")

            setField(plugin, "emHook", hook)
            setField(plugin, "dungeonQol", qol)
            setField(plugin, "emSync", sync)
            setField(plugin, "sharedSkillXp", sharedXp)
            setField(plugin, "passiveClasses", passives)
            setField(plugin, "arenaLevels", arena)
            @Suppress("UNCHECKED_CAST")
            val listeners = field(plugin, "nativeListeners").get(plugin) as MutableList<Listener>
            listeners += mockk<Listener>(relaxed = true)

            // A native reload failure must be contained; closeNative must still visit every handle.
            invokeNativeReload(plugin)

            plugin.emHook.shouldBeNull()
            plugin.dungeonQol.shouldBeNull()
            plugin.emSync.shouldBeNull()
            field(plugin, "sharedSkillXp").get(plugin).shouldBeNull()
            listeners.isEmpty() shouldBe true
            verify(exactly = 1) { hook.reload() }
            verify(exactly = 1) { qol.close() }
            verify(exactly = 1) { hook.close() }
            verify(exactly = 1) { sync.close() }
            verify(exactly = 1) { sharedXp.close() }
            verify(exactly = 1) { passives.close() }
            verify(exactly = 1) { arena.close() }
            field(plugin, "passiveClasses").get(plugin).shouldBeNull()
            field(plugin, "arenaLevels").get(plugin).shouldBeNull()
        }
    }

    @Test
    fun `ready listener handles cold initialization and repeated native reload then unregisters`() {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = paper.createSimplePlugin("ArcDungeonsReadyTest")
            var binds = 0
            val listener = EliteMobsLifecycleListener { binds++ }
            val original = MetadataHandler.pluginState
            try {
                MetadataHandler.pluginState = PluginState.INITIALIZING
                EliteMobsLifecycleListener.isReady() shouldBe false
                paper.server.pluginManager.registerEvents(listener, owner)
                MetadataHandler.pluginState = PluginState.INITIALIZED
                EliteMobsLifecycleListener.isReady() shouldBe true
                repeat(2) { paper.server.pluginManager.callEvent(EliteMobsInitializedEvent()) }
                binds shouldBe 2
                HandlerList.unregisterAll(listener)
                paper.server.pluginManager.callEvent(EliteMobsInitializedEvent())
                binds shouldBe 2
            } finally {
                MetadataHandler.pluginState = original
                HandlerList.unregisterAll(listener)
            }
        }
    }

    private fun invokeNativeReload(plugin: ArcDungeonsPlugin) {
        val original = MetadataHandler.pluginState
        try {
            MetadataHandler.pluginState = PluginState.INITIALIZED
            ArcDungeonsPlugin::class.java.getDeclaredMethod("reloadNative").apply { isAccessible = true }.invoke(plugin)
        } finally {
            MetadataHandler.pluginState = original
        }
    }

    private fun setField(plugin: ArcDungeonsPlugin, name: String, value: Any?) {
        field(plugin, name).set(plugin, value)
    }

    private fun field(plugin: ArcDungeonsPlugin, name: String) =
        ArcDungeonsPlugin::class.java.getDeclaredField(name).apply { isAccessible = true }
}
