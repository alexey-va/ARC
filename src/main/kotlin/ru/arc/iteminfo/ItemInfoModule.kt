package ru.arc.iteminfo

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info

object ItemInfoModule : PluginModule {
    override val name = "ItemInfo"
    override val priority = 90

    private var runtime: ItemInfoRuntime? = null

    override fun init() = start(ItemInfoConfig.load(ARC.instance.dataPath).snapshot())

    override fun reload() {
        shutdown()
        init()
    }

    override fun shutdown() {
        runtime?.let {
            HandlerList.unregisterAll(it)
            it.close()
        }
        runtime = null
    }

    private fun start(settings: ItemInfoSettings) {
        if (!settings.enabled) {
            info("Item info module disabled by configuration")
            return
        }
        runtime = ItemInfoRuntime(settings).also {
            Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            it.start()
        }
        info("Item info module initialized for ItemsAdder and Slimefun blocks")
    }
}
