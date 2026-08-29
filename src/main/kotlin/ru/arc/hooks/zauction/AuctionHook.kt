package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.category.CategoryManager
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.StorageType
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.config.AuctionConfig
import ru.arc.core.ScheduledTask
import ru.arc.core.async
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

internal class AuctionHook : AutoCloseable {

    var auctionMessager: AuctionMessager? = null
    private var auctionPlugin: AuctionPlugin? = null
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
            repeating(
                AuctionConfig.refreshRate.ticks,
                delay = AuctionConfig.refreshRate.ticks,
            ) {
                if (!AuctionConfig.broadcastItems) return@repeating
                val messager = auctionMessager ?: return@repeating
                val snapshot = getAuctionItems()
                async { messager.send(snapshot) }
            }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelTasks()
        auctionMessager = null
        auctionPlugin = null
        auctionManager = null
        categoryManager = null
    }

    private fun getAuctionItems(): List<AuctionItemDto> {
        val manager = auctionManager ?: return emptyList()
        return manager.getItems(StorageType.LISTED)
            .filter { !it.isExpired }
            .mapNotNull { item ->
                runCatching { fromAuctionItem(resolveCategory(item), item) }
                    .onFailure { error ->
                        warn("Skipping auction listing {} while building Discord snapshot", item.id, error)
                    }
                    .getOrNull()
            }
    }

    private fun resolveCategory(item: Item): String =
        AuctionConfig.categories.firstOrNull { item.hasCategory(it) } ?: "misc"

    private fun fromAuctionItem(category: String, item: Item): AuctionItemDto? {
        if (item.isExpired) return null

        // zAuctionHouse v4's buildItemStack(Player) renders interactive GUI
        // placeholders and dereferences the player. The Discord feed needs the
        // stored lot itself, not a viewer-specific menu item.
        val stack = (item as? AuctionItem)?.itemStack
        var display: String? = item.itemDisplay
        if (display.isNullOrBlank()) {
            val meta = stack?.itemMeta
            if (meta != null && meta.hasDisplayName()) {
                val name = meta.displayName()
                if (name is TextComponent) {
                    display = PlainTextComponentSerializer.plainText().serialize(name)
                }
            }
            if (display.isNullOrBlank()) {
                val translator = HookRegistry.translatorHook
                display = if (translator != null && stack != null) {
                    translator.translate(stack)
                } else {
                    item.translationKey.takeIf(String::isNotBlank) ?: "предмет"
                }
            }
        }

        val lore = stack?.itemMeta?.lore()
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

    internal fun saleEvent(item: Item): AuctionSaleEventDto? {
        val buyer = item.buyerName?.trim()?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,16}")) } ?: return null
        val seller = item.sellerName.trim().takeIf { it.matches(Regex("[A-Za-z0-9_]{1,16}")) } ?: return null
        val dto = fromAuctionItem(resolveCategory(item), item) ?: return null
        val price = dto.price?.takeIf { it.isNotBlank() }?.take(100) ?: return null
        return AuctionSaleEventDto(
            listingId = item.id.toString(),
            sellerUuid = item.sellerUniqueId?.toString(),
            sellerName = seller,
            buyerName = buyer,
            itemDisplay = dto.display?.takeIf { it.isNotBlank() }?.take(200) ?: "предмет",
            amount = item.amount.coerceIn(1, 1_000_000),
            price = price,
            occurredAt = System.currentTimeMillis(),
        )
    }

    private fun resolveApi() {
        val plugin = Bukkit.getPluginManager().getPlugin("zAuctionHouse")
            ?: Bukkit.getPluginManager().getPlugin("zAuctionHouseV3")
        if (plugin is AuctionPlugin) {
            auctionPlugin = plugin
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
