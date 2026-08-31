package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.cache.PlayerCacheKey
import fr.maxlego08.zauctionhouse.api.category.CategoryManager
import fr.maxlego08.zauctionhouse.api.inventories.Inventories
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.ItemStatus
import fr.maxlego08.zauctionhouse.api.item.StorageType
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem
import fr.maxlego08.zauctionhouse.api.messages.Message
import fr.maxlego08.zauctionhouse.api.tax.TaxType
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.config.AuctionConfig
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.core.async
import ru.arc.core.repeating
import ru.arc.core.ticks
import ru.arc.core.whenCompleteSync
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
    private val tasks = LifecycleTaskScope()
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
        tasks.close()
        auctionMessager = null
        auctionPlugin = null
        auctionManager = null
        categoryManager = null
    }

    private fun getAuctionItems(): List<AuctionItemDto> {
        val manager = auctionManager ?: return emptyList()
        return manager.getItems(StorageType.LISTED)
            .filter(Item::isActivelyListed)
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
        if (!item.isActivelyListed) return null

        // zAuctionHouse v4's buildItemStack(Player) renders interactive GUI
        // placeholders and dereferences the player. The Discord feed needs the
        // stored lot itself, not a viewer-specific menu item.
        val stack = (item as? AuctionItem)?.itemStack
        val plainText = PlainTextComponentSerializer.plainText()
        val meta = stack?.itemMeta
        var display: String? =
            meta?.takeIf { it.hasDisplayName() }
                ?.displayName()
                ?.let(plainText::serialize)
                ?.trim()
                ?.takeIf(String::isNotBlank)
        if (display.isNullOrBlank()) {
            display = stack?.let { HookRegistry.translatorHook?.translate(it) }
        }
        if (display.isNullOrBlank()) {
            display = item.itemDisplay?.takeIf(String::isNotBlank)
        }
        if (display.isNullOrBlank()) {
            display = item.translationKey.takeIf(String::isNotBlank) ?: "предмет"
        }

        val lore = meta?.lore()
            ?.map(plainText::serialize)
            ?.filter(String::isNotBlank)
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

    internal fun showcaseListings(): List<AuctionShowcaseListing> {
        val manager = auctionManager ?: return emptyList()
        return manager
            .getItems(StorageType.LISTED)
            .asSequence()
            .filter(Item::isActivelyListed)
            .distinctBy(Item::getId)
            .sortedBy(Item::getId)
            .mapNotNull(::showcaseListing)
            .toList()
    }

    /**
     * Revalidates a world showcase click and enters zAuctionHouse's native
     * confirmation lifecycle. This never purchases an item directly.
     */
    internal fun openShowcaseListing(
        player: Player,
        listingId: Int,
        callback: (AuctionShowcaseOpenResult) -> Unit,
    ) {
        val manager = auctionManager
        val plugin = auctionPlugin
        if (manager == null || plugin == null || closed) {
            callback(AuctionShowcaseOpenResult.Unavailable)
            return
        }
        val item = findActive(manager, listingId)
        if (item == null) {
            callback(AuctionShowcaseOpenResult.Stale)
            return
        }
        if (item.sellerUniqueId == player.uniqueId) {
            manager.openMainAuction(player)
            callback(AuctionShowcaseOpenResult.OwnAuctionOpened)
            return
        }

        val cache = manager.getCache(player)
        if (cache.get(PlayerCacheKey.PURCHASE_ITEM, false)) {
            callback(AuctionShowcaseOpenResult.Busy)
            return
        }
        cache.set(PlayerCacheKey.PURCHASE_ITEM, true)

        val economy = item.auctionEconomy
        if (economy == null) {
            cache.set(PlayerCacheKey.PURCHASE_ITEM, false)
            warn("Auction showcase listing {} has no economy", item.id)
            callback(AuctionShowcaseOpenResult.Failed)
            return
        }
        val tax = economy.taxConfiguration
        val required =
            if (tax.isEnabled && tax.taxType == TaxType.CAPITALISM) {
                economy.calculatePurchaseTax(player, item.price, null).let { result ->
                    if (result.hasTax()) result.finalPrice() else item.price
                }
            } else {
                item.price
            }

        economy.has(player.uniqueId, required).whenCompleteSync(tasks) { enough, failure ->
            if (failure != null) {
                cache.set(PlayerCacheKey.PURCHASE_ITEM, false)
                warn("Failed to check funds for auction showcase listing {}", listingId, failure)
                callback(AuctionShowcaseOpenResult.Failed)
                return@whenCompleteSync
            }
            if (enough != true) {
                val purchased = plugin.configuration.actions.purchased()
                if (purchased.sendNoMoneyMessage()) manager.message(player, Message.NOT_ENOUGH_MONEY)
                purchased.noMoneySound().play(player)
                cache.set(PlayerCacheKey.PURCHASE_ITEM, false)
                callback(AuctionShowcaseOpenResult.InsufficientFunds)
                return@whenCompleteSync
            }

            val current = findActive(manager, listingId)
            if (current == null) {
                cache.set(PlayerCacheKey.PURCHASE_ITEM, false)
                callback(AuctionShowcaseOpenResult.Stale)
                return@whenCompleteSync
            }
            cache.set(PlayerCacheKey.ITEM_SHOW, current)
            cache.set(PlayerCacheKey.CURRENT_PAGE, 1)
            cache.set(PlayerCacheKey.PURCHASE_ITEM, false)

            plugin.auctionClusterBridge
                .notifyItemStatusChange(current, ItemStatus.AVAILABLE, ItemStatus.IS_PURCHASE_CONFIRM)
                .whenCompleteSync(tasks) { _, transitionFailure ->
                    if (transitionFailure != null) {
                        cache.remove(PlayerCacheKey.ITEM_SHOW, PlayerCacheKey.CURRENT_PAGE)
                        warn("Failed to reserve auction showcase listing {} for confirmation", listingId, transitionFailure)
                        callback(AuctionShowcaseOpenResult.Failed)
                        return@whenCompleteSync
                    }
                    current.status = ItemStatus.IS_PURCHASE_CONFIRM
                    manager.updateListedItems(current, false, player)
                    val inventory =
                        if ((current as? AuctionItem)?.itemStacks?.size ?: 0 > 1) {
                            Inventories.PURCHASE_INVENTORY_CONFIRM
                        } else {
                            Inventories.PURCHASE_CONFIRM
                        }
                    plugin.inventoriesLoader.openInventory(player, inventory)
                    callback(AuctionShowcaseOpenResult.ConfirmationOpened)
                }
        }
    }

    private fun findActive(manager: AuctionManager, listingId: Int): Item? =
        manager.getItems(StorageType.LISTED).firstOrNull { it.id == listingId && it.isActivelyListed }

    private fun showcaseListing(item: Item): AuctionShowcaseListing? {
        val stack = runCatching { (item as? AuctionItem)?.itemStack?.clone() }.getOrNull()
            ?.takeUnless { it.type.isAir }
            ?: return null
        val plain = PlainTextComponentSerializer.plainText()
        val itemName =
            stack.itemMeta
                ?.takeIf { it.hasDisplayName() }
                ?.displayName()
                ?.let(plain::serialize)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: HookRegistry.translatorHook?.translate(stack)?.trim()?.takeIf(String::isNotBlank)
                ?: item.itemDisplay?.trim()?.takeIf(String::isNotBlank)
                ?: item.translationKey.trim().takeIf(String::isNotBlank)
                ?: "Предмет"
        val symbol = item.auctionEconomy?.symbol?.trim().orEmpty()
        val price = TextUtil.formatAmount(item.price.toDouble()) + symbol.takeIf(String::isNotBlank).orEmpty()
        return AuctionShowcaseListing(
            id = item.id,
            item = stack,
            itemName = itemName.take(120),
            sellerName = item.sellerName.trim().ifBlank { "неизвестен" }.take(32),
            price = price.take(64),
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

internal data class AuctionShowcaseListing(
    val id: Int,
    val item: ItemStack,
    val itemName: String,
    val sellerName: String,
    val price: String,
)

internal sealed interface AuctionShowcaseOpenResult {
    data object ConfirmationOpened : AuctionShowcaseOpenResult

    data object OwnAuctionOpened : AuctionShowcaseOpenResult

    data object InsufficientFunds : AuctionShowcaseOpenResult

    data object Stale : AuctionShowcaseOpenResult

    data object Unavailable : AuctionShowcaseOpenResult

    data object Busy : AuctionShowcaseOpenResult

    data object Failed : AuctionShowcaseOpenResult
}
