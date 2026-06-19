package ru.arc.eliteloot

import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.arc.util.TextUtil

data class DecorItem(
    val material: Material = Material.AIR,
    val weight: Double = 0.0,
    val modelId: Int = 0,
    val color: Color? = null,
    val iaNamespace: String? = null,
    val iaId: String? = null,
) {

    fun toItemStack(lootType: LootType): ItemStack {
        val itemStack = ItemStack(material)
        val itemMeta = itemStack.itemMeta ?: return itemStack

        @Suppress("DEPRECATION")
        if (modelId != 0) itemMeta.setCustomModelData(modelId)

        if (color != null && itemMeta is LeatherArmorMeta) {
            itemMeta.setColor(color)
        }

        if (iaNamespace != null && iaId != null) {
            NBT.modify(itemStack) { nbt ->
                nbt.getOrCreateCompound("itemsadder")
                nbt.setString("namespace", iaNamespace)
                nbt.setString("id", iaId)
            }
        }

        val lore = buildList {
            add(Component.text("Loot Type: ${lootType.name.lowercase()}", NamedTextColor.GRAY))
            add(Component.text("Weight: $weight", NamedTextColor.GRAY))
            if (iaNamespace != null && iaId != null) {
                add(Component.text("IA: $iaNamespace:$iaId", NamedTextColor.GRAY))
            }
            if (modelId != 0) {
                add(Component.text("Model ID: $modelId", NamedTextColor.GRAY))
            }
        }
        itemMeta.lore(lore.map { TextUtil.strip(it) })
        itemStack.setItemMeta(itemMeta)
        return itemStack
    }
}
