package ru.arc.itemcatalog

import org.bukkit.entity.Player
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

object ItemsCatalogModule : PluginModule {
    override val name = "ItemsCatalog"
    override val priority = 84

    @Volatile private var settings: ItemsCatalogSettings? = null
    @Volatile private var service: ItemsCatalogService? = null
    @Volatile private var controller: ItemsCatalogGuiController? = null

    override fun init() {
        start(ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot())
    }

    override fun reload() {
        val loaded = ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot()
        shutdownRuntime()
        start(loaded)
    }

    override fun shutdown() {
        shutdownRuntime()
        settings = null
    }

    fun isAvailable(): Boolean = controller != null

    fun open(player: Player) {
        val activeController = controller
        if (activeController == null) {
            val message = settings?.unavailableMessage ?: "<red>Каталог предметов сейчас недоступен."
            player.sendMessage(TextUtil.mm(message, true))
            return
        }
        activeController.openRoot(player)
    }

    internal fun currentSnapshot(): ItemsCatalogSnapshot? = service?.currentSnapshot()

    private fun start(loaded: ItemsCatalogSettings) {
        settings = loaded
        if (!loaded.enabled) {
            info("Items catalog module disabled by configuration")
            return
        }
        val itemsAdder = Bukkit.getPluginManager().getPlugin("ItemsAdder")
        if (itemsAdder == null || !itemsAdder.isEnabled) {
            warn("Items catalog module disabled because ItemsAdder is unavailable")
            return
        }
        val gateway = BukkitItemsAdderCatalogGateway(itemsAdder)
        val activeService = ItemsCatalogService(ARC.instance, loaded, gateway)
        service = activeService
        controller = ItemsCatalogGuiController(loaded, activeService)
        activeService.start()
        info("Items catalog module initialized and is waiting for the ItemsAdder index")
    }

    private fun shutdownRuntime() {
        controller = null
        service?.shutdown()
        service = null
    }
}
