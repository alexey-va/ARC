package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.economy.EconomyHandler
import com.magmaguy.elitemobs.items.customitems.CustomItem
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import ru.arc.util.Logging
import java.util.UUID

internal data class SupplyOffer(val id: String, val material: Material, val amount: Int, val price: Double, val customItem: String? = null)
internal data class SupplyQuote(val offer: SupplyOffer, val item: ItemStack, val buyer: UUID) {
    var consumed: Boolean = false
        internal set
}
internal enum class SupplyResult(val success: Boolean = false) {
    BOUGHT(true), CHANGED, OUTSIDE, NO_SPACE, NO_MONEY, ITEM_UNAVAILABLE, PAYMENT_FAILED,
}

internal interface DungeonSupplyEconomy {
    fun balance(player: Player): Double
    fun withdraw(player: Player, amount: Double): Boolean
}

private object NativeDungeonSupplyEconomy : DungeonSupplyEconomy {
    override fun balance(player: Player): Double = EconomyHandler.checkCurrency(player.uniqueId)
    override fun withdraw(player: Player, amount: Double): Boolean {
        val before = balance(player)
        if (!before.isFinite() || before < amount) return false
        val attempted = runCatching { EconomyHandler.subtractCurrency(player.uniqueId, amount) }
        // Native subtraction has no return value. Read back the wallet, including a late exception.
        val after = balance(player)
        if (kotlin.math.abs(after - (before - amount)) < 0.005) return true
        attempted.exceptionOrNull()?.let { Logging.error("Dungeon supply payment failed", it) }
        return false
    }
}

/** Main-thread purchase of the exact quoted stack. No item/command is accepted from a client. */
internal class DungeonSupplyShop(
    private val offers: () -> List<SupplyOffer> = { DEFAULT_SUPPLY_OFFERS },
    private val current: (Player) -> DungeonPanelView? = { null },
    private val economy: DungeonSupplyEconomy = NativeDungeonSupplyEconomy,
    private val createItem: (Player, SupplyOffer) -> ItemStack? = ::nativeSupplyItem,
    private val createReward: (Player, SupplyOffer) -> ItemStack? = DungeonCaseRewards::create,
    private val celebrate: (Player, ItemStack) -> Unit = ru.arc.eliteloot.EliteLootEffects::received,
) {
    fun list(): List<SupplyOffer> = offers().filter(::valid).distinctBy { it.id }

    fun quote(player: Player, offer: SupplyOffer): SupplyQuote? = runCatching {
        if (list().none { it == offer }) return null
        val item = createItem(player, offer)?.clone() ?: return null
        if (item.type.isAir || item.amount != offer.amount || item.amount > item.maxStackSize) return null
        SupplyQuote(offer, item, player.uniqueId)
    }.getOrElse { Logging.error("Unable to prepare dungeon supply item {}", offer.id, it); null }

    fun buy(player: Player, displayed: SupplyQuote, expected: DungeonPanelView): SupplyResult {
        if (displayed.consumed || displayed.buyer != player.uniqueId) return SupplyResult.CHANGED
        val actual = current(player)
        if (actual == null || actual.worldId != expected.worldId || actual.visit.run != expected.visit.run) return SupplyResult.OUTSIDE
        val quote = quote(player, displayed.offer) ?: return SupplyResult.ITEM_UNAVAILABLE
        if (quote != displayed) return SupplyResult.CHANGED
        if (if (quote.offer.isCase) player.inventory.storageContents.none { it == null || it.type.isAir } else !hasRoom(player, quote.item)) return SupplyResult.NO_SPACE
        val funds = runCatching { economy.balance(player) }.getOrNull()
        if (funds == null || !funds.isFinite()) return SupplyResult.PAYMENT_FAILED
        if (funds < quote.offer.price) return SupplyResult.NO_MONEY
        val reward = if (quote.offer.isCase) runCatching { createReward(player, quote.offer) }.getOrElse {
            Logging.error("Dungeon case generation failed for {} offer={}", player.uniqueId, quote.offer.id, it); null
        } ?: return SupplyResult.ITEM_UNAVAILABLE else quote.item
        if (!hasRoom(player, reward)) return SupplyResult.NO_SPACE
        displayed.consumed = true
        val snapshot = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val delivered = runCatching { player.inventory.addItem(reward.clone()).isEmpty() }.getOrDefault(false)
        if (!delivered) {
            player.inventory.storageContents = snapshot
            return SupplyResult.NO_SPACE
        }
        if (!runCatching { economy.withdraw(player, quote.offer.price) }.getOrElse {
            Logging.error("Dungeon supply payment failed for {}", player.uniqueId, it); false
        }) {
            player.inventory.storageContents = snapshot
            return SupplyResult.PAYMENT_FAILED
        }
        if (quote.offer.isCase) runCatching { celebrate(player, reward.clone()) }.onFailure {
            Logging.warn("Dungeon case visual failed for {}; purchase completed", player.uniqueId, it)
        }
        return SupplyResult.BOUGHT
    }

    private fun hasRoom(player: Player, item: ItemStack): Boolean {
        var remaining = item.amount
        for (stack in player.inventory.storageContents) {
            if (stack == null || stack.type.isAir) remaining -= minOf(item.maxStackSize, player.inventory.maxStackSize)
            else if (stack.isSimilar(item)) remaining -= minOf(stack.maxStackSize, player.inventory.maxStackSize) - stack.amount
            if (remaining <= 0) return true
        }
        return false
    }

    private fun valid(offer: SupplyOffer): Boolean = offer.id.matches(Regex("[a-z][a-z0-9_]{0,31}")) && !offer.material.isAir &&
        offer.amount in 1..offer.material.maxStackSize && offer.price.isFinite() && offer.price > 0.0 &&
        offer.price.toBigDecimal().stripTrailingZeros().scale() <= 2 &&
        (!offer.isCase || offer.amount == 1)
}

private fun nativeSupplyItem(player: Player, offer: SupplyOffer): ItemStack? {
    if (offer.isCase) return DungeonCaseRewards.preview(player, offer)
    if (offer.customItem == null) return ItemStack(offer.material, offer.amount).also { item ->
        if (offer.id == "healing") item.editMeta(PotionMeta::class.java) { it.basePotionType = PotionType.HEALING }
    }
    return CustomItem.getCustomItem(offer.customItem)?.generateDefaultsItemStack(player, false, null)?.clone()?.also { it.amount = offer.amount }
}

internal val DEFAULT_SUPPLY_OFFERS = listOf(
    SupplyOffer("beef", Material.COOKED_BEEF, 8, 20.0),
    SupplyOffer("bread", Material.BREAD, 16, 20.0),
    SupplyOffer("arrows", Material.ARROW, 32, 20.0),
    SupplyOffer("healing", Material.POTION, 1, 20.0),
    SupplyOffer("merchant", Material.PAPER, 1, 100.0, "summon_merchant_scroll.yml"),
    SupplyOffer("enchant_case", Material.ENCHANTED_BOOK, 1, 120.0),
    SupplyOffer("loot_case", Material.CHEST, 1, 200.0),
    SupplyOffer("loot_case_large", Material.ENDER_CHEST, 1, 400.0),
)
