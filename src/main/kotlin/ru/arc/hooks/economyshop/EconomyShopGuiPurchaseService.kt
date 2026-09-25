package ru.arc.hooks.economyshop

import dev.lone.itemsadder.api.CustomStack
import me.gypopo.economyshopgui.EconomyShopGUI
import me.gypopo.economyshopgui.api.EconomyShopGUIHook
import me.gypopo.economyshopgui.api.events.ShopItemsLoadEvent
import me.gypopo.economyshopgui.objects.ShopItem
import me.gypopo.economyshopgui.objects.TransactionMenu
import me.gypopo.economyshopgui.util.EcoType
import me.gypopo.economyshopgui.util.EconomyType
import me.gypopo.economyshopgui.util.Transaction
import me.gypopo.economyshopgui.util.Transaction.Mode
import me.gypopo.economyshopgui.util.Transaction.Type
import org.bukkit.Material
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import java.util.Locale

/** EconomyShopGUI Premium 6.3.0 implementation, loaded only while that plugin is present. */
internal class EconomyShopGuiPurchaseService(
    private val translateItem: (ItemStack?) -> String,
) : ShopPurchaseService, Listener {
    @Volatile
    private var furnitureItemIndex = FurnitureShopItemIndex.empty<ShopItem>()
    @Volatile
    private var loggedIndexFailure: String? = null

    init {
        rebuildFurnitureItemIndex()
    }

    override fun itemQueries(player: Player): List<String> =
        allItems()
            .asSequence()
            .filter { !it.hasItemError() && !it.isHidden && !it.isDisplayItem && it.isBuyAble }
            .filter { EconomyShopGUIHook.hasPermissions(it, player) }
            .map(::descriptor)
            .toList()
            .let(ShopItemQueryIndex::preferredQueries)

    override fun purchase(
        player: Player,
        itemPath: String,
        amount: Int,
    ): ShopPurchaseOutcome {
        val item = resolveItem(itemPath)
            ?: return ShopPurchaseOutcome(ShopPurchaseStatus.ITEM_NOT_FOUND, itemPath, amount)
        val canonicalPath = item.itemPath
        val itemName = translatedName(item)

        if (item.isHidden || item.isDisplayItem || !item.isBuyAble) {
            return ShopPurchaseOutcome(
                ShopPurchaseStatus.NOT_BUYABLE,
                canonicalPath,
                amount,
                itemName = itemName,
            )
        }

        // check permissions, check requirements, disallow partial quantity, suppress native
        // chat output (ARC maps every result to one consistent command response).
        val result = EconomyShopGUIHook.purchaseItem(player, item, amount, true, true, false, false)
        val fulfilledAmount = result.amount.takeIf { it > 0 } ?: amount
        return ShopPurchaseOutcome(
            status = result.result.toPurchaseStatus(),
            itemPath = canonicalPath,
            amount = fulfilledAmount,
            formattedPrice = formatPrices(result.prices),
            itemName = itemName,
        )
    }

    override fun furnitureOffers(player: Player, amount: Int): List<FurnitureShopOffer> {
        if (amount <= 0) return emptyList()
        return allItems()
            .asSequence()
            .mapNotNull { item -> runCatching { furnitureOffer(player, item, amount) }.getOrNull() }
            .sortedWith(
                compareBy<FurnitureShopOffer> { it.category.lowercase(Locale.ROOT) }
                    .thenBy { it.displayName.lowercase(Locale.ROOT) }
                    .thenBy { it.itemPath },
            )
            .toList()
    }

    override fun furnitureOffer(player: Player, itemPath: String, amount: Int): FurnitureShopOffer? {
        if (amount <= 0) return null
        return resolveItem(itemPath)?.let { item -> furnitureOffer(player, item, amount) }
    }

    override fun furnitureOfferForId(player: Player, furnitureId: String): FurnitureShopOffer? {
        if (!canBrowseFurnitureShop(player)) return null
        val matches = furnitureItemIndex.entries(furnitureId)
        if (matches.isEmpty()) return null
        val offers = matches.mapNotNull { item -> runCatching { furnitureOffer(player, item, 1) }.getOrNull() }
        return offers.singleOrNull()
    }

    override fun hasFurniturePurchaseMenu(furnitureId: String): Boolean = furnitureItemIndex.contains(furnitureId)

    override fun openFurniturePurchaseMenu(player: Player, furnitureId: String): FurnitureShopMenuOpenResult {
        if (!isShopPluginEnabled()) return FurnitureShopMenuOpenResult.SHOP_UNAVAILABLE
        val matches = furnitureItemIndex.entries(furnitureId)
        if (matches.isEmpty()) return FurnitureShopMenuOpenResult.NOT_LISTED

        val plugin = runCatching { EconomyShopGUI.getInstance() }.getOrNull()
            ?: return FurnitureShopMenuOpenResult.SHOP_UNAVAILABLE
        val hasGlobalShopPermission = player.hasPermission(SHOP_PERMISSION)
        val allowedGameMode = player.gameMode !in plugin.bannedGamemodes

        val eligible = mutableListOf<ShopItem>()
        var permissionDenied = false
        var requirementsNotMet = false
        for (item in matches) {
            if (item.hasItemError() || item.isHidden || item.isDisplayItem || !item.isBuyAble || item.isBuyCommand) {
                continue
            }
            val canPurchase = hasGlobalShopPermission && allowedGameMode &&
                runCatching { item.canPurchase(player, "shop", false) }.getOrDefault(false)
            when (
                furnitureShopMenuAccess(
                    hasGlobalShopPermission = hasGlobalShopPermission,
                    allowedGameMode = allowedGameMode,
                    itemCanPurchase = canPurchase,
                    requirementsMet = { runCatching { item.meetsRequirements(player, false) }.getOrDefault(false) },
                )
            ) {
                FurnitureShopMenuAccessDecision.NO_PERMISSION -> permissionDenied = true
                FurnitureShopMenuAccessDecision.REQUIREMENTS_NOT_MET -> requirementsNotMet = true
                FurnitureShopMenuAccessDecision.ALLOWED -> eligible += item
            }
        }

        if (eligible.isEmpty()) {
            return when {
                requirementsNotMet -> FurnitureShopMenuOpenResult.REQUIREMENTS_NOT_MET
                permissionDenied -> FurnitureShopMenuOpenResult.NO_PERMISSION
                else -> FurnitureShopMenuOpenResult.SHOP_UNAVAILABLE
            }
        }
        val item = eligible.singleOrNull() ?: return FurnitureShopMenuOpenResult.AMBIGUOUS_OFFER

        return try {
            // Mirrors ESG 6.3.0 MenuHandler's normal BUY_SCREEN construction and open(false).
            TransactionMenu(player, item, item.section(), false, Mode.BUY, Type.BUY_SCREEN, 1).open(false)
            FurnitureShopMenuOpenResult.OPENED
        } catch (_: LinkageError) {
            FurnitureShopMenuOpenResult.FAILED
        } catch (_: Exception) {
            FurnitureShopMenuOpenResult.FAILED
        }
    }

    override fun quotePlainMaterial(
        player: Player,
        material: Material,
        amount: Int,
    ): ShopMaterialQuote? {
        if (!material.isItem || material.isAir || amount <= 0) return null
        val candidates = allItems().mapNotNull { item ->
            runCatching { quoteCandidate(player, material, amount, item) }.getOrNull()
        }
        val selected = ShopMaterialOfferSelector.cheapest(candidates.map { it.first }) ?: return null
        return candidates.first { it.first == selected }.second
    }

    override fun vaultBalance(player: Player): Double? =
        vaultProvider()
            ?.let { provider -> runCatching { provider.getBalance(player) }.getOrNull() }
            ?.takeIf { it.isFinite() && it >= 0.0 }

    override fun formatVaultPrice(amount: Double): String? {
        if (!amount.isFinite() || amount < 0.0) return null
        return vaultProvider()
            ?.let { provider -> runCatching { provider.formatPrice(amount) }.getOrNull() }
            ?.takeIf(String::isNotBlank)
    }

    private fun quoteCandidate(
        player: Player,
        material: Material,
        amount: Int,
        item: ShopItem,
    ): Pair<ShopMaterialOffer, ShopMaterialQuote>? {
        if (
            item.hasItemError() || item.isHidden || item.isDisplayItem || !item.isBuyAble ||
            item.isBuyCommand || item.isABuyPricing ||
            item.ecoType.type != EconomyType.VAULT ||
            item.isMinBuy(amount) || item.isMaxBuy(amount)
        ) {
            return null
        }
        if (!EconomyShopGUIHook.hasPermissions(item, player)) return null
        // The true flag suppresses native "requirement not met" chat while this
        // read-only quote is being assembled.
        if (!runCatching { item.meetsRequirements(player, true) }.getOrDefault(false)) return null
        if (item.limitedStockMode > 0) {
            val stock = runCatching { EconomyShopGUIHook.getItemStock(item, player.uniqueId) }.getOrNull() ?: return null
            if (stock < amount) return null
        }
        val given = runCatching { item.itemToGive }.getOrNull() ?: return null
        val plain = ItemStack(material)
        if (given.type != material || given.amount != 1 || !given.isSimilar(plain)) return null

        val total = runCatching { item.getBuyPrice(player, amount) }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val formatted = runCatching {
            EconomyShopGUIHook.getEcon(item.ecoType)?.formatPrice(total)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val offer = ShopMaterialOffer(item.itemPath, total)
        return offer to ShopMaterialQuote(material, item.itemPath, amount, total, formatted)
    }

    private fun furnitureOffer(
        player: Player,
        item: ShopItem,
        amount: Int,
    ): FurnitureShopOffer? {
        if (
            item.hasItemError() || item.isHidden || item.isDisplayItem || !item.isBuyAble ||
            item.isBuyCommand || item.ecoType.type != EconomyType.VAULT ||
            item.isMinBuy(amount) || item.isMaxBuy(amount)
        ) {
            return null
        }
        if (!canBrowseFurnitureShop(player, item)) return null
        if (!EconomyShopGUIHook.hasPermissions(item, player)) return null
        if (!runCatching { item.meetsRequirements(player, true) }.getOrDefault(false)) return null
        if (item.limitedStockMode > 0) {
            val stock = runCatching { EconomyShopGUIHook.getItemStock(item, player.uniqueId) }.getOrNull() ?: return null
            if (stock < amount) return null
        }

        val custom = runCatching { CustomStack.byItemStack(item.itemToGive) }.getOrNull() ?: return null
        val furnitureId = runCatching { custom.namespacedID }.getOrNull()?.trim().orEmpty()
        if (furnitureId.isBlank() || !hasFurnitureBehaviour(custom)) return null

        val total = runCatching { item.getBuyPrice(player, amount) }.getOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        val formatted = runCatching {
            EconomyShopGUIHook.getEcon(item.ecoType)?.formatPrice(total)
        }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        val displayName =
            runCatching { item.displayname }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
                ?: runCatching { custom.displayName }.getOrNull()?.trim()?.takeIf(String::isNotBlank)
                ?: furnitureId
        return FurnitureShopOffer(
            item.itemPath,
            furnitureId,
            item.section().trim(),
            displayName.replace('\n', ' ').replace('\r', ' ').take(256),
            amount,
            total,
            formatted,
        )
    }

    private fun hasFurnitureBehaviour(custom: CustomStack): Boolean =
        runCatching { hasFurnitureBehaviour(custom.config, custom.id) }.getOrDefault(false)

    /** Rebuild only on hook initialization and ESG's item-reload event, never on the viewer tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShopItemsLoaded(@Suppress("UNUSED_PARAMETER") event: ShopItemsLoadEvent) {
        rebuildFurnitureItemIndex()
    }

    private fun rebuildFurnitureItemIndex() {
        if (!isShopPluginEnabled()) {
            furnitureItemIndex = FurnitureShopItemIndex.empty(complete = false)
            return
        }

        try {
            val shopItems = EconomyShopGUIHook.getSections().values.asSequence()
                .flatMap { section -> section.shopItems.asSequence() }
                .take(MAX_INDEXED_SHOP_ITEMS + 1)
                .toList()
            if (shopItems.size > MAX_INDEXED_SHOP_ITEMS) {
                furnitureItemIndex = FurnitureShopItemIndex.empty(complete = false)
                logIndexUnavailable("shop-item-limit-exceeded")
                return
            }

            furnitureItemIndex = FurnitureShopItemIndex.from(
                shopItems.asSequence().mapNotNull { item ->
                    runCatching { furnitureId(item) }.getOrNull()?.let { id -> id to item }
                },
                maxEntries = MAX_INDEXED_SHOP_ITEMS,
            )
            loggedIndexFailure = null
        } catch (_: LinkageError) {
            furnitureItemIndex = FurnitureShopItemIndex.empty(complete = false)
            logIndexUnavailable("shop-api-unavailable")
        } catch (_: Exception) {
            furnitureItemIndex = FurnitureShopItemIndex.empty(complete = false)
            logIndexUnavailable("shop-catalog-unavailable")
        }
    }

    private fun furnitureId(item: ShopItem): String? {
        if (item.hasItemError() || item.isHidden || item.isDisplayItem || !item.isBuyAble || item.isBuyCommand) {
            return null
        }
        val custom = CustomStack.byItemStack(item.itemToGive) ?: return null
        val id = custom.namespacedID?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return id.takeIf { hasFurnitureBehaviour(custom) }
    }

    private fun canBrowseFurnitureShop(player: Player, item: ShopItem? = null): Boolean {
        if (!isShopPluginEnabled()) return false
        val plugin = runCatching { EconomyShopGUI.getInstance() }.getOrNull() ?: return false
        if (!player.hasPermission(SHOP_PERMISSION) || player.gameMode in plugin.bannedGamemodes) return false
        return item == null || runCatching { item.canPurchase(player, "shop", true) }.getOrDefault(false)
    }

    private fun isShopPluginEnabled(): Boolean =
        runCatching { Bukkit.getPluginManager().isPluginEnabled(SHOP_PLUGIN_NAME) }.getOrDefault(false)

    private fun logIndexUnavailable(reason: String) {
        if (loggedIndexFailure == reason) return
        loggedIndexFailure = reason
        runCatching {
            Bukkit.getLogger().warning("ARC furniture shop index unavailable ($reason); gallery price and menu lookup fail closed")
        }
    }

    private fun vaultProvider() =
        allItems()
            .asSequence()
            .mapNotNull { item -> runCatching { item.ecoType }.getOrNull() }
            .firstOrNull { it.type == EconomyType.VAULT }
            ?.let(EconomyShopGUIHook::getEcon)

    private fun translatedName(item: ShopItem): String? =
        runCatching { translateItem(item.shopItem) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun resolveItem(itemPath: String): ShopItem? {
        EconomyShopGUIHook.getShopItem(itemPath)?.let { return it }
        val items = allItems()
        val canonicalPath = ShopItemQueryIndex.resolve(itemPath, items.map(::descriptor)) ?: return null
        return items.firstOrNull { it.itemPath.equals(canonicalPath, ignoreCase = true) }
    }

    private fun allItems(): List<ShopItem> =
        EconomyShopGUIHook.getSections().values.flatMap { it.shopItems }

    private fun descriptor(item: ShopItem): ShopItemDescriptor =
        ShopItemDescriptor(
            canonicalPath = item.itemPath,
            section = item.section(),
            relativeLocation = item.itemLoc(),
            material = runCatching { item.shopItem.type.name }.getOrNull(),
        )

    private fun formatPrices(prices: Map<EcoType, Double>?): String? {
        if (prices.isNullOrEmpty()) return null
        return prices.entries
            .sortedBy { it.key.toString() }
            .joinToString(" + ") { (type, price) ->
                EconomyShopGUIHook.getEcon(type)?.formatPrice(price) ?: price.toString()
            }
            .takeIf(String::isNotBlank)
    }

    private fun Transaction.Result.toPurchaseStatus(): ShopPurchaseStatus =
        when (this) {
            Transaction.Result.SUCCESS,
            Transaction.Result.SUCCESS_COMMANDS_EXECUTED,
            -> ShopPurchaseStatus.SUCCESS

            Transaction.Result.ITEM_ERROR -> ShopPurchaseStatus.ITEM_ERROR
            Transaction.Result.DISPLAY_ITEM,
            Transaction.Result.NEGATIVE_ITEM_PRICE,
            Transaction.Result.NO_ITEMS_FOUND,
            -> ShopPurchaseStatus.NOT_BUYABLE

            Transaction.Result.NO_PERMISSIONS -> ShopPurchaseStatus.NO_PERMISSIONS
            Transaction.Result.REQUIREMENTS_FAILED -> ShopPurchaseStatus.REQUIREMENTS_FAILED
            Transaction.Result.INSUFFICIENT_FUNDS -> ShopPurchaseStatus.INSUFFICIENT_FUNDS
            Transaction.Result.NO_INVENTORY_SPACE -> ShopPurchaseStatus.NO_INVENTORY_SPACE
            Transaction.Result.TRANSACTION_CANCELLED -> ShopPurchaseStatus.TRANSACTION_CANCELLED
            Transaction.Result.NOT_ENOUGH_ITEMS -> ShopPurchaseStatus.BELOW_MINIMUM
            Transaction.Result.TO_MANY_ITEMS -> ShopPurchaseStatus.ABOVE_MAXIMUM
            Transaction.Result.NO_ITEM_STOCK_LEFT -> ShopPurchaseStatus.OUT_OF_STOCK
            Transaction.Result.REACHED_SELL_LIMIT,
            Transaction.Result.CANT_STORE_PAYMENT,
            -> ShopPurchaseStatus.FAILED
        }

    private companion object {
        const val SHOP_PLUGIN_NAME = "EconomyShopGUI-Premium"
        const val SHOP_PERMISSION = "EconomyShopGUI.shop"
        const val MAX_INDEXED_SHOP_ITEMS = 16_384
    }
}

/** Checks only the exact ItemsAdder item entry; sibling items in one file are ignored. */
internal fun hasFurnitureBehaviour(config: org.bukkit.configuration.ConfigurationSection, itemId: String): Boolean {
    val id = itemId.trim()
    if (id.isEmpty() || id.contains('.')) return false
    return config.isConfigurationSection("items.$id.behaviours.furniture")
}

internal data class ShopItemDescriptor(
    val canonicalPath: String,
    val section: String,
    val relativeLocation: String,
    val material: String?,
)

/** Selects concise unambiguous command tokens while retaining canonical-path lookup. */
internal object ShopItemQueryIndex {
    fun preferredQueries(items: List<ShopItemDescriptor>): List<String> =
        items
            .groupBy { it.section.lowercase() }
            .values
            .flatMap { sectionItems ->
                sectionItems.map { item ->
                    val shortLocation = item.relativeLocation.substringAfterLast('.')
                    val selector =
                        listOfNotNull(item.material, shortLocation, item.relativeLocation)
                            .distinctBy { it.lowercase() }
                            .firstOrNull { candidate ->
                                sectionItems.count { matchesSelector(it, candidate) } == 1
                            } ?: item.relativeLocation
                    "${item.section}.$selector"
                }
            }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun resolve(query: String, items: List<ShopItemDescriptor>): String? {
        items.singleOrNull { it.canonicalPath.equals(query, ignoreCase = true) }?.let { return it.canonicalPath }

        val separator = query.indexOf('.')
        if (separator <= 0 || separator == query.lastIndex) return null
        val section = query.substring(0, separator)
        val selector = query.substring(separator + 1)
        val matches =
            items.filter { item ->
                item.section.equals(section, ignoreCase = true) &&
                    matchesSelector(item, selector)
            }
        return matches.singleOrNull()?.canonicalPath
    }

    private fun matchesSelector(item: ShopItemDescriptor, selector: String): Boolean =
        item.relativeLocation.equals(selector, ignoreCase = true) ||
            item.relativeLocation.substringAfterLast('.').equals(selector, ignoreCase = true) ||
            item.material?.equals(selector, ignoreCase = true) == true
}
