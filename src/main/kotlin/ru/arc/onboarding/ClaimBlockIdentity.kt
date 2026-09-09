package ru.arc.onboarding

import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Lands special-item markers, not the display name or the block material. */
internal object ClaimBlockIdentity {
    private val type = NamespacedKey("lands", "type")
    private val ownerKey = NamespacedKey("lands", "o")
    private val radiusKey = NamespacedKey("lands", "radius")

    fun matches(item: ItemStack): Boolean = radius(item) != null

    fun usableBy(item: ItemStack, player: Player): Boolean {
        val owner = item.itemMeta?.persistentDataContainer?.get(ownerKey, PersistentDataType.STRING)
        return owner == null || owner == player.uniqueId.toString()
    }

    fun heldRadius(player: Player): Int? = radius(player.inventory.itemInMainHand)
        ?: radius(player.inventory.itemInOffHand)

    /** Edit the currently held stack in place; never recreate an item from a stale menu snapshot. */
    fun setHeldRadius(player: Player, radius: Int, lore: List<Component>): Boolean {
        require(radius in 0..4)
        val inventory = player.inventory
        val slot = if (matches(inventory.itemInMainHand)) inventory.heldItemSlot
            else if (matches(inventory.itemInOffHand)) 40 else return false
        val item = inventory.getItem(slot)?.clone() ?: return false
        item.editMeta { meta ->
            meta.persistentDataContainer.set(radiusKey, PersistentDataType.INTEGER, radius)
            meta.lore(lore)
        }
        inventory.setItem(slot, item)
        return true
    }

    // Normal kit blocks use 0 or 1; bound special-item geometry to at most 81 chunks.
    fun radius(item: ItemStack): Int? {
        if (!item.hasItemMeta()) return null
        val data = item.itemMeta.persistentDataContainer
        if (data.get(type, PersistentDataType.STRING) != "CLAIM_BLOCK") return null
        return data.get(radiusKey, PersistentDataType.INTEGER)?.takeIf { it in 0..4 }
    }
}
