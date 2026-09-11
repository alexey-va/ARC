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
    @Volatile private var rewardController: RewardCatalogGuiController? = null

    override fun init() {
        start(
            ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot(),
            RewardCatalogModuleConfig.load(ARC.instance.dataPath).snapshot(),
        )
    }

    override fun reload() {
        val loaded = ItemsCatalogModuleConfig.load(ARC.instance.dataPath).snapshot()
        val rewards = RewardCatalogModuleConfig.load(ARC.instance.dataPath).snapshot()
        shutdownRuntime()
        start(loaded, rewards)
    }

    override fun shutdown() {
        shutdownRuntime()
        settings = null
    }

    fun isAvailable(): Boolean = controller != null || rewardController?.isAvailable() == true

    fun open(player: Player) {
        val activeController = controller
        if (activeController != null) {
            activeController.openRoot(player)
            return
        }
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог предметов сейчас недоступен.", true))
    }

    fun openRewards(player: Player) {
        rewardController?.takeIf(RewardCatalogGuiController::isAvailable)?.openRoot(player)
            ?: player.sendMessage(TextUtil.mm(settings?.unavailableMessage ?: "<red>Каталог наград сейчас недоступен.", true))
    }

    internal fun currentSnapshot(): ItemsCatalogSnapshot? = service?.currentSnapshot()

    private fun start(loaded: ItemsCatalogSettings, rewards: RewardCatalogSettings) {
        settings = loaded
        rewardController = RewardCatalogGuiController(rewards, loaded.givePermission).takeIf { it.isAvailable() }
        info(
            "Reward catalogue loaded: enabled={} categories={} entries={}",
            rewards.enabled,
            rewards.categories.size,
            rewards.entryCount,
        )
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
        controller = ItemsCatalogGuiController(loaded, activeService, rewardController)
        activeService.start()
        info("Items catalog module initialized and is waiting for the ItemsAdder index")
    }

    private fun shutdownRuntime() {
        controller?.shutdown()
        controller = null
        rewardController?.shutdown()
        rewardController = null
        service?.shutdown()
        service = null
    }
}
