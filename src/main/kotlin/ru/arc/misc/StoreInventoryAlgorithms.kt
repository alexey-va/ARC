package ru.arc.misc

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

internal data class StorageSlotUpdate(
    val slot: Int,
    val item: ItemStack,
)

internal data class PlayerStorageTransferPlan(
    val updates: List<StorageSlotUpdate>,
)

/**
 * Plans the same destination slots as a vanilla chest quick-move into player storage.
 *
 * Chest menus traverse the player section backwards: hotbar 8..0, then main inventory 35..9.
 * Matching stacks are merged in that order before empty slots are used. The plan is atomic:
 * when the whole source stack cannot fit, no partial plan is returned.
 */
internal object VanillaPlayerStorageTransfer {
    private val destinationOrder: List<Int> = (8 downTo 0) + (35 downTo 9)

    fun planFull(
        storageContents: Array<ItemStack?>,
        source: ItemStack,
    ): PlayerStorageTransferPlan? {
        if (source.type == Material.AIR || source.amount <= 0) {
            return PlayerStorageTransferPlan(emptyList())
        }

        val working =
            Array<ItemStack?>(storageContents.size) { slot ->
                storageContents[slot].normalizedClone()
            }
        val touchedSlots = linkedSetOf<Int>()
        var remaining = source.amount
        val usableSlots = destinationOrder.filter { it in working.indices }

        for (slot in usableSlots) {
            val target = working[slot] ?: continue
            if (!target.isSimilar(source)) continue

            val capacity = (target.maxStackSize - target.amount).coerceAtLeast(0)
            val moved = minOf(capacity, remaining)
            if (moved <= 0) continue

            target.amount += moved
            remaining -= moved
            touchedSlots += slot
            if (remaining == 0) break
        }

        if (remaining > 0) {
            for (slot in usableSlots) {
                if (working[slot] != null) continue

                val moved = minOf(source.maxStackSize, remaining)
                working[slot] = source.clone().also { it.amount = moved }
                remaining -= moved
                touchedSlots += slot
                if (remaining == 0) break
            }
        }

        if (remaining > 0) return null

        return PlayerStorageTransferPlan(
            touchedSlots.map { slot -> StorageSlotUpdate(slot, requireNotNull(working[slot]).clone()) },
        )
    }
}

/** Finds exact Store cells whose complete Bukkit item state changed. */
internal object StoreSlotDiff {
    fun changedSlots(
        previous: List<ItemStack?>,
        desired: List<ItemStack?>,
    ): List<Int> =
        (0 until maxOf(previous.size, desired.size)).filter { slot ->
            !sameState(previous.getOrNull(slot), desired.getOrNull(slot))
        }

    private fun sameState(
        first: ItemStack?,
        second: ItemStack?,
    ): Boolean {
        val normalizedFirst = first.takeUnlessNullOrAir()
        val normalizedSecond = second.takeUnlessNullOrAir()
        return normalizedFirst == normalizedSecond
    }
}

private fun ItemStack?.normalizedClone(): ItemStack? = takeUnlessNullOrAir()?.clone()

private fun ItemStack?.takeUnlessNullOrAir(): ItemStack? = this?.takeUnless { it.type == Material.AIR }
