package ru.arc.iteminfo

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.ServicePriority
import ru.arc.ARC
import ru.arc.hooks.HookRegistry
import ru.arc.core.PluginModule
import ru.arc.util.Logging.info
import ru.arc.paper.api.ArcInspectionService
import ru.arc.paper.inspection.PaperArcInspectionService

object ItemInfoModule : PluginModule {
    override val name = "ItemInfo"
    override val priority = 90

    private var runtime: ItemInfoRuntime? = null
    private var inspection: PaperArcInspectionService? = null

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
        inspection?.let {
            Bukkit.getServicesManager().unregister(it)
            it.close()
        }
        inspection = null
    }

    private fun start(settings: ItemInfoSettings) {
        val service = PaperArcInspectionService(ARC.instance)
        val galleryPurchasePrice: (Player, String) -> String? = HookRegistry.shopPurchaseService
            ?.let { purchaseService ->
                { player, furnitureId -> purchaseService.furnitureOfferForId(player, furnitureId)?.formattedPrice }
            }
            ?: { _, _ -> null }
        inspection = service
        Bukkit.getServicesManager().register(
            ArcInspectionService::class.java,
            service,
            ARC.instance,
            ServicePriority.Normal,
        )
        runtime = ItemInfoRuntime(settings, service, galleryPurchasePrice).also {
            Bukkit.getPluginManager().registerEvents(it, ARC.instance)
            it.start()
        }
        if (settings.enabled) info("Item info module initialized for ItemsAdder and Slimefun blocks")
        else info("Item info source disabled; shared inspection service initialized")
    }
}
