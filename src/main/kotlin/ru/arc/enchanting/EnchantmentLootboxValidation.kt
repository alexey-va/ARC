package ru.arc.enchanting

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.iface.ReadableItemNBT
import org.bukkit.inventory.ItemStack

/** Reads the custom-data root used by ItemPresets and BlockListener, on Paper's owning thread. */
internal fun readEnchantmentLootboxTreasureKey(item: ItemStack): String? =
    NBT.get<String>(item) { readEnchantmentLootboxRootKey(it) }

internal fun readEnchantmentLootboxRootKey(data: ReadableItemNBT): String =
    data.getString("arc:treasure_key")

/** A physical single AE box; an unreadable key denies issuance rather than trusting presentation. */
internal fun isEnchantmentLootbox(
    item: ItemStack?,
    readTreasureKey: (ItemStack) -> String? = ::readEnchantmentLootboxTreasureKey,
): Boolean = try {
    item != null && !item.type.isAir && item.amount == 1 && readTreasureKey(item) == "ae"
} catch (_: Exception) {
    false
} catch (_: LinkageError) {
    false
}
