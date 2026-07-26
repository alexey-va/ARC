package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.category.CategoryManager
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.StorageType
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.config.AuctionConfig
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil

class AuctionHook : AutoCloseable {

    var auctionMessager: AuctionMessager? = null
    private var categoryManager: CategoryManager? = null
    private var auctionManager: AuctionManager? = null
    private var broadcastItemsTask: ScheduledTask? = null
    private var closed = false

    init {
        resolveApi()
    }

    @Synchronized
    fun start() {
        check(!closed) { "AuctionHook is closed" }
        if (auctionManager == null || categoryManager == null) {
            info("zAuctionHouse API providers not available yet")
        } else {
            startTasks()
            info("zAuctionHouse hook initialized")
        }
    }

    @Synchronized
    fun cancelTasks() {
        broadcastItemsTask?.takeUnless { it.isCancelled }?.cancel()
        broadcastItemsTask = null
    }

    @Synchronized
    fun startTasks() {
        if (closed) return
        cancelTasks()
        auctionManager ?: return
        broadcastItemsTask =
            repeatingAsync(
                AuctionConfig.refreshRate.ticks,
                delay = AuctionConfig.refreshRate.ticks,
            ) {
                if (!AuctionConfig.broadcastItems) return@repeatingAsync
                val messager = auctionMessager ?: return@repeatingAsync
                messager.send(getAuctionItems())
            }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelTasks()
        auctionMessager = null
        auctionManager = null
        categoryManager = null
    }

    private fun getAuctionItems(): List<AuctionItemDto> {
        val manager = auctionManager ?: return emptyList()
        return manager.getItems(StorageType.LISTED)
            .filter { !it.isExpired }
            .filter { matchesConfiguredCategory(it) }
            .mapNotNull { fromAuctionItem(resolveCategory(it), it) }
    }

    private fun matchesConfiguredCategory(item: Item): Boolean =
        AuctionConfig.categories.any { item.hasCategory(it) }

    private fun resolveCategory(item: Item): String =
        AuctionConfig.categories.firstOrNull { item.hasCategory(it) } ?: "misc"

    private fun fromAuctionItem(category: String, item: Item): AuctionItemDto? {
        if (item.isExpired) return null

        val stack = item.buildItemStack(null)
        var display: String? = item.itemDisplay
        if (display.isNullOrBlank()) {
            val meta = stack.itemMeta
            if (meta != null && meta.hasDisplayName()) {
                val name = meta.displayName()
                if (name is TextComponent) {
                    display = PlainTextComponentSerializer.plainText().serialize(name)
                }
            }
            if (display.isNullOrBlank()) {
                val translator = HookRegistry.translatorHook
                display = if (translator != null) {
                    translator.translate(stack)
                } else {
                    stack.type.name.replace("_", "").lowercase()
                }
            }
        }

        val lore = stack.itemMeta?.lore()
            ?.filterIsInstance<TextComponent>()
            ?.map { it.content() }
            ?: emptyList()

        return AuctionItemDto(
            display,
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
        val plugin = Bukkit.getPluginManager().getPlugin("zAuctionHouse")
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
