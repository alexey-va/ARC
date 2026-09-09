package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.items.ItemTagger
import com.magmaguy.elitemobs.items.ItemWorthCalculator
import com.magmaguy.elitemobs.items.customenchantments.SoulbindEnchantment
import com.magmaguy.elitemobs.items.customitems.CustomItem
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import com.magmaguy.elitemobs.skills.CombatLevelCalculator
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.ThreadLocalRandom
import ru.arc.ARC
import ru.arc.config.ConfigManager
import net.kyori.adventure.text.format.TextDecoration

internal fun caseLevel(combatLevel: Int, cap: Int, roll: Int): Int {
    require(roll in 0..99)
    val ceiling = combatLevel.coerceIn(1, cap)
    val percent = when { roll < 60 -> 80; roll < 90 -> 90; else -> 100 }
    return (ceiling * percent / 100).coerceAtLeast(1)
}

internal fun caseBook(roll: Int): String {
    require(roll in 0..99)
    return when { roll < 40 -> "critical_strikes"; roll < 70 -> "ice_breaker"; roll < 90 -> "lightning"; else -> "flamethrower" }
}

internal val SupplyOffer.isCase: Boolean get() = id in setOf("enchant_case", "loot_case", "loot_case_large")

/** Only native EliteMobs factories; the case is opened once on confirmed purchase. */
internal object DungeonCaseRewards : Listener {
    private val sourceKey = NamespacedKey("arc", "dungeon_case_reward")
    private val soulbindKey = NamespacedKey("elitemobs", "soulbind")
    private val levelKey = NamespacedKey("arc", "dungeon_case_level")

    fun ceiling(player: Player, offer: SupplyOffer): Int =
        CombatLevelCalculator.calculateCombatLevel(player.uniqueId).coerceIn(1, if (offer.id == "loot_case_large") 40 else 20)

    fun preview(player: Player, offer: SupplyOffer): ItemStack? {
        if (offer.id == "enchant_case" && (0..99).map(::caseBook).distinct().any {
                CustomItem.getCustomItem("enchanted_book_$it.yml") == null
            }) return null
        return ItemStack(offer.material).also { stack ->
            if (offer.id != "enchant_case") stack.editMeta {
                it.persistentDataContainer.set(levelKey, PersistentDataType.INTEGER, ceiling(player, offer))
            }
        }
    }

    fun create(player: Player, offer: SupplyOffer): ItemStack? {
        val roll = ThreadLocalRandom.current().nextInt(100)
        val item = if (offer.id == "enchant_case") {
            val enchant = caseBook(roll)
            CustomItem.getCustomItem("enchanted_book_$enchant.yml")
                ?.generateDefaultsItemStack(player, false, null)?.clone()?.takeIf {
                    EliteEnchantmentItems.isEliteEnchantmentBook(it) &&
                        ItemTagger.getItemEnchantments(it).filterKeys { key -> key.key != "enchanted_source" } ==
                        mapOf(NamespacedKey("elitemobs", enchant) to 1)
                }
        } else {
            val cap = if (offer.id == "loot_case_large") 40 else 20
            val level = caseLevel(ceiling(player, offer), cap, roll)
            ItemConstructor.constructItem(level.toDouble(), null, player, true)
                ?.takeIf { ItemTagger.getStoredItemLevel(it) == level }
        } ?: return null
        if (item.type.isAir || item.amount != 1 || !EliteItemManager.isEliteMobsItem(item)) return null
        val bound = SoulbindEnchantment.addEnchantment(item, player) ?: return null
        if (bound.itemMeta.persistentDataContainer.get(soulbindKey, PersistentDataType.STRING) != player.uniqueId.toString()) return null
        val resale = ItemWorthCalculator.determineResaleWorth(bound, player)
        if (!resale.isFinite() || resale < 0 || resale > offer.price / 2) return null
        bound.editMeta { meta ->
            meta.persistentDataContainer.set(sourceKey, PersistentDataType.BYTE, 1)
            if (offer.id == "enchant_case") {
                val enchant = caseBook(roll)
                val name = when (enchant) {
                    "critical_strikes" -> "Книга критических ударов I"
                    "ice_breaker" -> "Книга ледокола I"
                    "lightning" -> "Книга молнии I"
                    else -> "Книга огнемёта I"
                }
                meta.displayName(ConfigManager.of(ARC.instance.dataPath, "modules/elitemobs.yml")
                    .component("dungeon-qol.shop.book-names.$enchant", "<#c7a0e8>$name").decoration(TextDecoration.ITALIC, false))
            }
        }
        return bound
    }

    // Native soulbind protects pickup/combat. Also protect books transferred through a container.
    // The native unbinder remains supported: removing soulbind releases this guard as well.
    internal fun foreign(item: ItemStack?, player: Player): Boolean {
        val data = item?.itemMeta?.persistentDataContainer ?: return false
        if (!data.has(sourceKey, PersistentDataType.BYTE)) return false
        val owner = data.get(soulbindKey, PersistentDataType.STRING) ?: return false
        return owner != player.uniqueId.toString() && !player.hasPermission("elitemobs.soulbind.bypass")
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val hotbar = if (event.hotbarButton in 0..8) player.inventory.getItem(event.hotbarButton) else null
        if (listOf(event.currentItem, event.cursor, hotbar, player.inventory.itemInOffHand).any { foreign(it, player) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (foreign(event.oldCursor, player)) event.isCancelled = true
    }
}
