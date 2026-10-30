package ru.arc.listeners

import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.NBTType
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/** Closes reward items hidden in entity data that Bukkit item-container metadata does not expose. */
internal object CreativeRewardOpaqueComponents {
    internal const val MAX_DEPTH = 16
    internal const val MAX_NODES = 512
    private val opaqueKeys = listOf("minecraft:entity_data", "minecraft:bucket_entity_data", "minecraft:block_entity_data")
    private val rewardKeys = setOf(
        "arc:treasure_key", "arc:pouch_id", "money_token_mean", "money_token_sigma", "money_token_minimum",
        "ae_token", "slimefun_token",
        "arc:physical_reward_voucher", "arc:physical_reward_voucher_version",
        "arc:physical_reward_voucher_source", "arc:physical_reward_voucher_fingerprint",
    )
    private val entityMaterials = setOf(
        Material.ARMOR_STAND, Material.MINECART, Material.BUCKET,
        Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME, Material.PAINTING, Material.END_CRYSTAL,
    )

    /**
     * Call on the owning gameplay thread. NBT API takes one indivisible native snapshot of this item
     * before the traversal budget can run. Restrict that cost to metadata-bearing entity/block carriers;
     * the limits below bound traversal, not native serialization or packet decoding.
     */
    fun isProtected(stack: ItemStack): Boolean = runCatching {
        if (!stack.hasItemMeta() || !canMaterializeEntityData(stack.type)) return false
        NBT.getComponents<Boolean>(stack) { containsProtectedData(it) }
    }.getOrDefault(true)

    internal fun canMaterializeEntityData(material: Material): Boolean =
        material.isBlock || material.name.endsWith("_SPAWN_EGG") || material.name.endsWith("_BOAT") ||
            material.name.endsWith("_RAFT") || material.name.endsWith("_MINECART") ||
            material.name.endsWith("_BUCKET") || material in entityMaterials

    internal fun containsProtectedData(components: ReadableNBT): Boolean = runCatching {
        var remaining = MAX_NODES
        fun visit(node: ReadableNBT, depth: Int): Boolean {
            if (depth > MAX_DEPTH || --remaining < 0) return true
            val keys = node.keys
            if (keys.size > remaining) return true
            if (keys.any { it in rewardKeys } || isLegacyMoney(node)) return true
            for (key in keys) {
                if (--remaining < 0) return true
                when (node.getType(key)) {
                    NBTType.NBTTagCompound -> if (visit(node.getCompound(key) ?: return true, depth + 1)) return true
                    NBTType.NBTTagList -> {
                        when (node.getListType(key)) {
                            NBTType.NBTTagCompound -> {
                                val children = node.getCompoundList(key)
                                if (children.size() > remaining) return true
                                for (index in 0 until children.size()) {
                                    if (visit(children[index], depth + 1)) return true
                                }
                            }
                            NBTType.NBTTagString -> remaining -= node.getStringList(key).size()
                            NBTType.NBTTagInt -> remaining -= node.getIntegerList(key).size()
                            NBTType.NBTTagLong -> remaining -= node.getLongList(key).size()
                            NBTType.NBTTagFloat -> remaining -= node.getFloatList(key).size()
                            NBTType.NBTTagDouble -> remaining -= node.getDoubleList(key).size()
                            NBTType.NBTTagIntArray -> remaining -= node.getIntArrayList(key).size()
                            NBTType.NBTTagEnd -> Unit // The native NBT format uses END for an empty list.
                            else -> return true // ReadableNBT cannot inspect nested lists or byte/short lists.
                        }
                        if (remaining < 0) return true
                    }
                    NBTType.NBTTagString, NBTType.NBTTagByte, NBTType.NBTTagShort, NBTType.NBTTagInt,
                    NBTType.NBTTagLong, NBTType.NBTTagFloat, NBTType.NBTTagDouble, NBTType.NBTTagByteArray,
                    NBTType.NBTTagIntArray, NBTType.NBTTagLongArray -> Unit
                    else -> return true
                }
            }
            return false
        }
        opaqueKeys.any { key ->
            if (!components.hasTag(key)) false
            else if (components.getType(key) != NBTType.NBTTagCompound) true
            else visit(components.getCompound(key) ?: return true, 0)
        }
    }.getOrDefault(true)

    private fun isLegacyMoney(node: ReadableNBT): Boolean {
        if (node.getType("id") != NBTType.NBTTagString) return false
        val id = node.getString("id")
        if (id != "minecraft:stick" && id != "stick") {
            return false
        }
        val components = node.getCompound("components")
        val model = when (components?.getType("minecraft:custom_model_data")) {
            NBTType.NBTTagInt -> components.getInteger("minecraft:custom_model_data")
            NBTType.NBTTagCompound -> {
                val customModel = components.getCompound("minecraft:custom_model_data") ?: return true
                if (customModel.getListType("floats") == NBTType.NBTTagFloat) {
                    val floats = customModel.getFloatList("floats")
                    if (floats.size() > 0) floats[0].toInt() else null
                } else null
            }
            else -> null
        }
        if (model in 11135..11138) return true
        val oldTag = node.getCompound("tag") ?: return false
        return oldTag.getType("CustomModelData") == NBTType.NBTTagInt && oldTag.getInteger("CustomModelData") in 11135..11138
    }
}
