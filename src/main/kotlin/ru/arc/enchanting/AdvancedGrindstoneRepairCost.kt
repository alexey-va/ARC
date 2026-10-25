package ru.arc.enchanting

import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Repairable
import org.bukkit.persistence.PersistentDataType

private val advancedRepairCostKey = NamespacedKey("advancedenchantments", "repaircost")

/** AE 9.24.15 stores its prior-work penalty outside vanilla's grindstone reset. */
internal class AdvancedGrindstoneRepairCost : Listener {
    // Preview recovery before provider validation, so another listener may still reject it.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onRecoverOrphan(event: PrepareGrindstoneEvent) {
        if (event.result?.type?.isAir == false) return
        val source = (0..1).mapNotNull { event.inventory.getItem(it)?.takeUnless { item ->
            item.type.isAir || item.amount <= 0
        } }.singleOrNull() ?: return
        if (source.amount != 1 || source.type.maxDurability <= 0 || source.enchantments.isNotEmpty()) return
        val meta = source.itemMeta ?: return
        if ((meta as? Repairable)?.repairCost != 0) return
        val pdc = meta.persistentDataContainer
        if ((pdc.get(advancedRepairCostKey, PersistentDataType.INTEGER) ?: 0) <= 0) return
        if (pdc.keys.any { it.namespace == "advancedenchantments" && it.key.startsWith("ae_enchantment-") }) return
        event.result = source.clone().apply {
            editMeta { it.persistentDataContainer.remove(advancedRepairCostKey) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPrepare(event: PrepareGrindstoneEvent) {
        val result = event.result?.takeUnless { it.type.isAir } ?: return
        val meta = result.itemMeta ?: return
        val mirroredCost = meta.persistentDataContainer.get(advancedRepairCostKey, PersistentDataType.INTEGER) ?: return
        val nativeCost = (meta as? Repairable)?.repairCost ?: return
        if (nativeCost > 0 && mirroredCost == nativeCost) return
        event.result = result.clone().apply {
            editMeta {
                if (nativeCost == 0) it.persistentDataContainer.remove(advancedRepairCostKey)
                else it.persistentDataContainer.set(advancedRepairCostKey, PersistentDataType.INTEGER, nativeCost)
            }
        }
    }
}
