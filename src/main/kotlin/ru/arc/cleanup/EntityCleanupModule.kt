package ru.arc.cleanup

import org.bukkit.Bukkit
import org.bukkit.event.world.WorldLoadEvent
import ru.arc.ARC
import ru.arc.core.BukkitEventBus
import ru.arc.core.EventScope
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info

object EntityCleanupModule : PluginModule {
    override val name = "EntityCleanup"
    override val priority = 82
    private var cleanup: MobEquipmentCleanup? = null
    private var worldEvents: EventScope? = null

    override fun init() {
        shutdown()
        reload()
    }

    override fun reload() {
        // Validate before replacing the running snapshot; an invalid reload leaves it intact.
        val settings = EntityCleanupConfig.load(ARC.instance.dataPath).settings
        if (!settings.enabled) {
            shutdown()
            info("Entity cleanup disabled by configuration")
            return
        }
        val lifetime = NativeItemLifetime.load(Bukkit.getServer())
        val current = cleanup
        if (current != null) {
            current.reload(settings, lifetime::ticks)
        } else {
            val bus = BukkitEventBus(ARC.instance)
            cleanup = MobEquipmentCleanup(bus, settings, lifetime::ticks, Bukkit::getCurrentTick).also { it.start() }
            worldEvents = EventScope(bus).also { scope ->
                scope.on<WorldLoadEvent> { reload() }
            }
        }
        info("Entity cleanup: mob-equipment={}, lifetime={} ticks, aged={}, unknown-native-rate={}",
            settings.equipment.enabled, settings.equipment.lifetimeTicks,
            cleanup?.agedItems ?: 0, cleanup?.unknownLifetimeItems ?: 0)
    }

    override fun shutdown() {
        cleanup?.close()
        cleanup = null
        worldEvents?.unregisterAll()
        worldEvents = null
    }
}
