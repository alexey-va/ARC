package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.persistence.PersistentDataType
import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.util.Logging.warn

class EliteLootProcessor(
    private val config: Config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "elite-loot.yml"),
    private val selectDecor: (LootType) -> DecorItem? = { EliteLootManager.map[it]?.randomItem() },
    private val template: (DecorItem) -> ItemStack? = ::eliteSkinTemplate,
) {
    @Suppress("DEPRECATION", "UnstableApiUsage")
    fun processEliteLoot(originalStack: ItemStack?, caseReward: Boolean = false): ItemStack? {
        if (originalStack == null || !EliteItemManager.isEliteMobsItem(originalStack)) return originalStack
        if (!caseReward && !config.bool("replace-skins", true)) return originalStack
        val meta = originalStack.itemMeta
        if (meta.persistentDataContainer.has(skinKey) || meta.hasItemModel() ||
            (meta.hasCustomModelData() && meta.customModelData != 0)) return originalStack
        if (!caseReward && Math.random() > config.real("replace-chance", 0.9)) return originalStack
        val type = EliteLootManager.toLootType(originalStack) ?: return originalStack
        val decor = selectDecor(type) ?: return originalStack
        val skin = template(decor) ?: return originalStack
        applyEliteSkin(originalStack, skin)
        originalStack.editMeta {
            it.persistentDataContainer.set(skinKey, PersistentDataType.STRING, "${decor.iaNamespace}:${decor.iaId}")
        }
        return originalStack
    }

    companion object {
        private val skinKey = NamespacedKey("arc", "elite_skin")
    }
}

/** Copy appearance only: the original material, attributes, durability and plugin identity stay intact. */
@Suppress("UnstableApiUsage")
internal fun applyEliteSkin(item: ItemStack, skin: ItemStack) {
    skin.getData(DataComponentTypes.ITEM_MODEL)?.let { item.setData(DataComponentTypes.ITEM_MODEL, it) }
    skin.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.let { item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, it) }
    skin.getData(DataComponentTypes.DYED_COLOR)?.let { item.setData(DataComponentTypes.DYED_COLOR, it) }
    val originalEquipment = item.getData(DataComponentTypes.EQUIPPABLE)
    val skinEquipment = skin.getData(DataComponentTypes.EQUIPPABLE)
    if (originalEquipment != null && skinEquipment != null && originalEquipment.slot() == skinEquipment.slot()) {
        item.setData(DataComponentTypes.EQUIPPABLE, originalEquipment.toBuilder().assetId(skinEquipment.assetId()).build())
    }
}

@Suppress("DEPRECATION")
private fun eliteSkinTemplate(decor: DecorItem): ItemStack? {
    val id = if (!decor.iaNamespace.isNullOrBlank() && !decor.iaId.isNullOrBlank()) "${decor.iaNamespace}:${decor.iaId}" else null
    if (id != null) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        val skin = CustomStack.getInstance(id)?.itemStack?.clone()
        if (skin == null && missingSkins.add(id)) warn("EliteLoot: ItemsAdder skin {} is not registered; keeping original appearance", id)
        return skin
    }
    return ItemStack(decor.material).also { stack ->
        stack.editMeta {
            if (decor.modelId != 0) it.setCustomModelData(decor.modelId)
            if (it is LeatherArmorMeta && decor.color != null) it.setColor(decor.color)
        }
    }
}

private val missingSkins = mutableSetOf<String>()
