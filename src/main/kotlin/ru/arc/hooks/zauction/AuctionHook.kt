package ru.arc.hooks.zauction

import fr.maxlego08.zauctionhouse.api.AuctionManager
import fr.maxlego08.zauctionhouse.api.AuctionPlugin
import fr.maxlego08.zauctionhouse.api.category.CategoryManager
import fr.maxlego08.zauctionhouse.api.item.Item
import fr.maxlego08.zauctionhouse.api.item.ItemType
import fr.maxlego08.zauctionhouse.api.item.StorageType
import fr.maxlego08.zauctionhouse.api.item.items.AuctionItem
import fr.maxlego08.zauctionhouse.api.rules.Rule
import fr.maxlego08.zauctionhouse.api.services.AuctionSellService
import fr.maxlego08.zauctionhouse.api.services.result.SellFailReason
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.buildertools.BuilderBookAuctionFailure
import ru.arc.buildertools.BuilderBookAuctionItemGuard
import ru.arc.buildertools.BuilderBookAuctionListingResult
import ru.arc.buildertools.BuilderBookAuctionPort
import ru.arc.buildertools.BuilderBookAuctionToken
import ru.arc.buildertools.BuilderBookAuctionTokenCodec
import ru.arc.config.AuctionConfig
import ru.arc.core.ScheduledTask
import ru.arc.core.repeatingAsync
import ru.arc.core.ticks
import ru.arc.hooks.HookRegistry
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

internal class AuctionHook : AutoCloseable, BuilderBookAuctionPort {

    var auctionMessager: AuctionMessager? = null
    private var auctionPlugin: AuctionPlugin? = null
    private var categoryManager: CategoryManager? = null
    private var auctionManager: AuctionManager? = null
    private var builderBookDeliveryHandler: ((Player, ItemStack) -> Unit)? = null
    private val authorizedBuilderBooks = mutableSetOf<BuilderBookAuctionToken>()
    private val builderBookRule = Rule { context ->
        val item = context.itemStack
        BuilderBookAuctionItemGuard.containsPlayerCreatedBook(item) && !isAuthorizedBuilderBook(item)
    }
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
            ensureBuilderBookRule()
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
        builderBookDeliveryHandler = null
        authorizedBuilderBooks.clear()
        auctionPlugin = null
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

    override fun submit(
        player: Player,
        price: BigDecimal,
        tokenizedItem: ItemStack,
    ): CompletableFuture<BuilderBookAuctionListingResult> {
        check(Bukkit.isPrimaryThread()) { "Builder-book auction submission must run on the primary thread" }
        val plugin = auctionPlugin ?: return CompletableFuture.completedFuture(
            BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.UNAVAILABLE),
        )
        val manager = auctionManager ?: return CompletableFuture.completedFuture(
            BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.UNAVAILABLE),
        )
        val token = BuilderBookAuctionTokenCodec.read(tokenizedItem)
            ?: return CompletableFuture.completedFuture(
                BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED),
            )
        val economy = plugin.economyManager.getDefaultEconomy(ItemType.AUCTION)
            ?: return CompletableFuture.completedFuture(
                BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.UNAVAILABLE),
            )
        val expirationSeconds = plugin.configuration.sellExpiration.getExpiration(player)
        val expiredAt = if (expirationSeconds > 0L) System.currentTimeMillis() + expirationSeconds * 1_000L else 0L
        val sellFuture = try {
            authorizedBuilderBooks += token
            manager.sellService.sellAuctionItems(
                player,
                price,
                expiredAt,
                mapOf(AuctionSellService.MAIN_HAND_SLOT to tokenizedItem.clone()),
                economy,
            )
        } finally {
            authorizedBuilderBooks -= token
        }
        return sellFuture.handle { result, failure ->
            when {
                failure != null || result == null ->
                    BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.AMBIGUOUS)
                result.success() -> {
                    val auctionItem = result.auctionItem()
                    if (auctionItem == null) {
                        BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.AMBIGUOUS)
                    } else {
                        BuilderBookAuctionListingResult.Listed(auctionItem.id.toString())
                    }
                }
                result.failReason() == SellFailReason.DATABASE_ERROR ->
                    BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.AMBIGUOUS)
                else -> BuilderBookAuctionListingResult.Failed(BuilderBookAuctionFailure.REJECTED)
            }
        }
    }

    override fun contains(token: BuilderBookAuctionToken): Boolean {
        check(Bukkit.isPrimaryThread()) { "Builder-book auction lookup must run on the primary thread" }
        val manager = auctionManager ?: return false
        return listOf(StorageType.LISTED, StorageType.PURCHASED, StorageType.EXPIRED).any { storage ->
            manager.getItems(storage).any { item ->
                (item as? AuctionItem)?.itemStacks?.any { stack ->
                    BuilderBookAuctionTokenCodec.read(stack) == token
                } == true
            }
        }
    }

    override fun setDeliveryHandler(handler: ((Player, ItemStack) -> Unit)?) {
        check(Bukkit.isPrimaryThread()) { "Builder-book auction delivery handler must change on the primary thread" }
        builderBookDeliveryHandler = handler
    }

    internal fun isBlockedBuilderBook(item: ItemStack): Boolean =
        BuilderBookAuctionItemGuard.containsPlayerCreatedBook(item) && !isAuthorizedBuilderBook(item)

    internal fun ensureBuilderBookRule() {
        val manager = auctionPlugin?.itemRuleManager ?: return
        if (manager.blacklistRules().rules().none { it === builderBookRule }) {
            manager.addBlacklistRule(builderBookRule)
        }
        if (!manager.isBlacklistEnabled) {
            manager.isBlacklistEnabled = true
            warn("Enabled zAuctionHouse blacklist at runtime to protect registered builder books")
        }
    }

    internal fun onBuilderBookDelivered(player: Player, item: Item) {
        val handler = builderBookDeliveryHandler ?: return
        (item as? AuctionItem)?.itemStacks?.forEach { stack ->
            if (BuilderBookAuctionTokenCodec.read(stack) != null) handler(player, stack)
        }
    }

    private fun isAuthorizedBuilderBook(item: ItemStack): Boolean =
        BuilderBookAuctionTokenCodec.read(item)?.let(authorizedBuilderBooks::contains) == true

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
