package ru.arc.enchanting

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.items.EliteEnchantmentCatalog
import com.magmaguy.elitemobs.items.EliteItemLore
import com.magmaguy.elitemobs.items.EliteScroll
import com.magmaguy.elitemobs.items.upgradesystem.EliteEnchantmentItems
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import ru.arc.eliteloot.presentEliteItem
import ru.arc.eliteloot.reindexEliteEnchantmentLore
import java.util.UUID
import java.util.logging.Level

/** Native inventory handling owns consumption, shift-click movement and experience. */
internal class EliteGrindstoneController(
    private val prepareItem: (ItemStack, Player) -> ItemStack? = ::prepareEliteGrindstoneItem,
) : Listener {
    private val reportedFailures = mutableSetOf<Class<out Throwable>>()
    private val prepared = mutableMapOf<UUID, PreparedResult>()

    private data class PreparedResult(val inventory: Inventory, val inputs: List<ItemStack?>, val output: ItemStack)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepare(event: PrepareGrindstoneEvent) {
        val player = event.view.player as? Player
        player?.uniqueId?.let(prepared::remove)
        if (!containsEliteInput(event.inventory)) return
        val inputs = inputs(event.inventory)
        val output = result(inputs, player)
        event.result = output
        if (player != null && output != null) {
            prepared[player.uniqueId] = PreparedResult(event.inventory, inputs, output.clone())
        }
    }

    // AuraSkills awards its grindstone skill XP at NORMAL. Reject invalid output first.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onTake(event: InventoryClickEvent) {
        if (event.inventory.type != InventoryType.GRINDSTONE || event.rawSlot != 2) return
        val expected = prepared[event.whoClicked.uniqueId]
        if (expected == null && !containsEliteInput(event.inventory)) return
        // Presentation may migrate a legacy staff's random spell. Use the exact prepared output.
        if (expected == null || expected.inventory != event.inventory ||
            expected.inputs != inputs(event.inventory) || expected.output != event.currentItem) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        prepared.remove(event.player.uniqueId)
    }

    private fun containsEliteInput(inventory: Inventory): Boolean =
        (0..1).any { EliteItemManager.isEliteMobsItem(inventory.getItem(it)) }

    private fun inputs(inventory: Inventory): List<ItemStack?> =
        (0..1).map { slot ->
            inventory.getItem(slot)?.takeUnless { it.type.isAir || it.amount <= 0 }?.clone()
        }

    private fun result(inputs: List<ItemStack?>, player: Player?): ItemStack? {
        if (player == null) return null
        // A native repair cannot preserve two distinct elite identities or soulbound owners.
        val source = inputs.filterNotNull().singleOrNull()?.takeIf { it.amount == 1 } ?: return null
        return try {
            prepareItem(source.clone(), player)
        } catch (failure: Exception) {
            report(player, source, failure)
            null
        } catch (failure: LinkageError) {
            report(player, source, failure)
            null
        }
    }

    private fun report(player: Player, source: ItemStack, failure: Throwable) {
        if (reportedFailures.add(failure.javaClass)) {
            Bukkit.getLogger().log(Level.WARNING,
                "Elite grindstone rejected output player=${player.uniqueId} item=${source.type}; inputs retained", failure)
        }
    }
}

/** Remove only enchantments; clone every identity, owner, skin, attribute and durability field. */
internal fun removeEliteGrindstoneEnchantments(source: ItemStack): ItemStack? {
    if (source.amount != 1 || source.type == Material.ENCHANTED_BOOK ||
        !EliteItemManager.isEliteMobsItem(source) || EliteScroll.isEliteScroll(source)) return null
    val removableNative = EliteEnchantmentItems.nativeLevels(source).keys.filterNot { it.isCursed }
    val custom = EliteEnchantmentItems.custom(source)
    val retainedCustom = custom.filterKeys { id ->
        requireNotNull(EliteEnchantmentCatalog.definition(id)) { "Unknown EliteMobs enchantment $id" }.curse()
    }
    if (removableNative.isEmpty() && retainedCustom.size == custom.size) return null

    var result = source.clone()
    result.editMeta { meta ->
        for (enchantment in removableNative) {
            meta.removeEnchant(enchantment)
            // EM's full combat level survives vanilla removal unless its exact mirror is cleared.
            meta.persistentDataContainer.remove(enchantment.key)
        }
    }
    if (retainedCustom.size != custom.size) {
        result.editMeta(::reindexEliteEnchantmentLore)
        result = EliteEnchantmentItems.ITEMS.previewCustom(result, retainedCustom).apply(result)
    }
    return result
}

private fun prepareEliteGrindstoneItem(source: ItemStack, player: Player): ItemStack? {
    val result = removeEliteGrindstoneEnchantments(source) ?: return null
    result.editMeta(::reindexEliteEnchantmentLore)
    // Unlike presentation-only refreshes, this updates the actual native resale value too.
    EliteItemLore(result, false)
    return presentEliteItem(result, player)
}
