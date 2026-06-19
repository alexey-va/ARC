package ru.arc.board

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import ru.arc.util.HeadUtil
import ru.arc.util.customModelDataOrNull
import java.util.UUID

/**
 * Represents an item icon for a board entry — either a material with optional custom model data,
 * or a player head identified by UUID.
 */
data class ItemIcon(
    val material: Material,
    val headUuid: UUID?,
    val modelData: Int
) {
    /** No-args constructor for Jackson/Gson deserialization compatibility. */
    constructor() : this(Material.STONE, null, 0)

    fun stack(): ItemStack {
        if (material == Material.PLAYER_HEAD) {
            return headUuid?.let { HeadUtil.getSkull(it) } ?: ItemStack(Material.PLAYER_HEAD)
        }
        val stack = ItemStack(material)
        if (modelData != 0) {
            @Suppress("DEPRECATION")
            stack.editMeta { it.setCustomModelData(modelData) }
        }
        return stack
    }

    companion object {
        @JvmStatic
        fun of(uuid: UUID): ItemIcon = ItemIcon(material = Material.PLAYER_HEAD, headUuid = uuid, modelData = 0)

        @JvmStatic
        fun of(material: Material, modelData: Int): ItemIcon = ItemIcon(material = material, headUuid = null, modelData = modelData)

        @JvmStatic
        fun of(stack: ItemStack): ItemIcon {
            val meta = stack.itemMeta
            val customModelData = meta?.customModelDataOrNull ?: 0
            val headUuid = if (stack.type == Material.PLAYER_HEAD) {
                (meta as? SkullMeta)?.owningPlayer?.uniqueId
            } else null
            return ItemIcon(material = stack.type, headUuid = headUuid, modelData = customModelData)
        }
    }
}
