package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.category.CategoryManager
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.StorageType
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.configs.AuctionConfig
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil

class AuctionHook {

    var auctionMessager: AuctionMessager? = null
    private var categoryManager: CategoryManager? = null
    private var auctionManager: AuctionManager? = null
    private var auctionListener: AuctionListener? = null
    private var broadcastItemsTask: BukkitTask? = null

    init {
        resolveApi()

        if (auctionManager == null || categoryManager == null) {
            info("zAuctionHouse API providers not available yet")
        } else {
            if (auctionListener == null) {
                auctionListener = AuctionListener()
                Bukkit.getPluginManager().registerEvents(auctionListener!!, ARC.instance)
            }
            startTasks()
            info("zAuctionHouse hook initialized")
        }
    }

    fun cancelTasks() {
        broadcastItemsTask?.takeUnless { it.isCancelled }?.cancel()
    }

    fun startTasks() {
        cancelTasks()
        val am = auctionManager ?: return
        broadcastItemsTask = ARC.instance.server.scheduler.runTaskTimerAsynchronously(
            ARC.instance,
            Runnable {
                if (!AuctionConfig.broadcastItems) return@Runnable
                val messager = auctionMessager ?: return@Runnable
                messager.send(getAuctionItems())
            },
            AuctionConfig.refreshRate,
            AuctionConfig.refreshRate,
        )
    }

    private fun getAuctionItems(): List<AuctionItemDto> =
        auctionManager!!.getItems(StorageType.LISTED)
            .filter { !it.isExpired }
            .filter { matchesConfiguredCategory(it) }
            .mapNotNull { fromAuctionItem(resolveCategory(it), it) }

    private fun matchesConfiguredCategory(item: Item): Boolean =
        AuctionConfig.categories.any { item.hasCategory(it) }

    private fun resolveCategory(item: Item): String =
        AuctionConfig.categories.firstOrNull { item.hasCategory(it) } ?: "misc"

    private fun fromAuctionItem(category: String, item: Item): AuctionItemDto? {
        if (item.isExpired) return null

        var display: String? = item.itemDisplay
        if (display.isNullOrBlank()) {
            val stack = item.buildItemStack(null)
            val meta = stack.itemMeta
            if (meta != null && meta.hasDisplayName()) {
                val name = meta.displayName()
                if (name is TextComponent) {
                    display = PlainTextComponentSerializer.plainText().serialize(name)
                }
            }
            if (display.isNullOrBlank()) {
                display = if (HookRegistry.translatorHook != null) {
                    HookRegistry.translatorHook!!.translate(item.buildItemStack(null))
                } else {
                    item.buildItemStack(null).type.name.replace("_", "").lowercase()
                }
            }
        }

        val stack = item.buildItemStack(null)
        val lore = stack.itemMeta?.lore()
            ?.filterIsInstance<TextComponent>()
            ?.map { it.content() }
            ?: emptyList()

        return AuctionItemDto(
            display ?: "",
            item.sellerName,
            TextUtil.formatAmount(item.price.toDouble()),
            item.expiredAt.time,
            category,
            item.amount,
            0,
            item.id.toString(),
            true,
            lore,
        )
    }

    private fun resolveApi() {
        var plugin = Bukkit.getPluginManager().getPlugin("zAuctionHouse")
            ?: Bukkit.getPluginManager().getPlugin("zAuctionHouseV3")
        if (plugin is AuctionPlugin) {
            auctionManager = plugin.auctionManager
            categoryManager = plugin.categoryManager
            return
        }
        auctionManager = getProvider(AuctionManager::class.java)
        categoryManager = getProvider(CategoryManager::class.java)
    }

    private fun <T> getProvider(clazz: Class<T>): T? =
        ARC.instance.server.servicesManager.getRegistration(clazz)?.provider
}
