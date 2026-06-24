package ru.arc.eliteloot

import com.google.common.collect.Multimap
import com.magmaguy.elitemobs.api.utils.EliteItemManager
import de.tr7zw.changeme.nbtapi.NBT
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.LeatherArmorMeta
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.eliteloot.EliteLootManager.toLootType
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil

class EliteLootProcessor {

    private val config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "elite-loot.yml")
    private val leathers = setOf(
        Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
        Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS,
    )

    @Suppress("DEPRECATION")
    fun processEliteLoot(originalStack: ItemStack?): ItemStack? {
        if (originalStack == null) return null
        if (!EliteItemManager.isEliteMobsItem(originalStack)) return originalStack
        if (!config.bool("replace-skins", true)) return originalStack
        if (originalStack.itemMeta.hasCustomModelData()) return originalStack

        val replaceChance = config.real("replace-chance", 0.4)
        if (Math.random() > replaceChance) return originalStack

        var meta = originalStack.itemMeta
        if (config.bool("clear-lore", false)) {
            meta.lore(removeUselessEMLore(meta.lore()))
        }
        originalStack.itemMeta = meta

        val lootType = toLootType(originalStack) ?: run {
            debug("Elite loot: no LootType for elite item {}", originalStack.type)
            return originalStack
        }
        val decorPool = EliteLootManager.map[lootType] ?: run {
            debug("Elite loot: no decor pool for type {}", lootType)
            return originalStack
        }
        val decorItem = decorPool.randomItem() ?: return originalStack

        debug(
            "Replacing elite loot skin: type={} -> material={} modelId={} ia={}:{}",
            lootType, decorItem.material, decorItem.modelId, decorItem.iaNamespace, decorItem.iaId,
        )

        val armorTypes = setOf(LootType.HELMET, LootType.BOOTS, LootType.CHESTPLATE, LootType.LEGGINGS)
        var updatedStack = originalStack
        if (lootType !in armorTypes) {
            val appendAttributes = config.bool("append-attributes", false)
            updatedStack = changeItemMaterial(originalStack, decorItem.material, appendAttributes)
            meta = updatedStack.itemMeta
        } else if (meta is LeatherArmorMeta) {
            meta.setColor(decorItem.color)
        }

        if (decorItem.modelId == 0) meta.setCustomModelData(null) else meta.setCustomModelData(decorItem.modelId)
        if (meta is Damageable) meta.damage = 0

        updatedStack.itemMeta = meta

        if (decorItem.iaNamespace != null && decorItem.iaId != null) {
            NBT.modify(updatedStack) { nbt ->
                val itemsadder = nbt.getOrCreateCompound("itemsadder")
                itemsadder.setString("namespace", decorItem.iaNamespace)
                itemsadder.setString("id", decorItem.iaId)
            }
        }

        return updatedStack
    }

    @Suppress("UnstableApiUsage")
    private fun changeItemMaterial(origin: ItemStack, targetMaterial: Material, appendAttrLore: Boolean): ItemStack {
        if (origin.type == targetMaterial) return origin
        val target = origin.withType(targetMaterial)

        val attrs = origin.type.defaultAttributeModifiers
        val targetMeta = target.itemMeta
        try {
            targetMeta.setAttributeModifiers(attrs)
        } catch (e: Exception) {
            error("Failed to add attributes for {}", origin.type, e)
            error("Default attributes for origin {}: {}", origin.type, attrs)
            error("Default attributes for target {}: {}", targetMaterial, targetMaterial.defaultAttributeModifiers)
        }

        if (appendAttrLore) {
            targetMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            val currentLore = (targetMeta.lore() ?: emptyList()).toMutableList()
            val attr: Multimap<Attribute, AttributeModifier> = targetMaterial.defaultAttributeModifiers

            if (!attr.isEmpty) {
                var appended = false
                currentLore.add(Component.text(""))
                for (entry in attr.entries()) {
                    val slot = entry.value.slotGroup.toString().replace(".", "_").lowercase()
                    val slotTitle = config.string("attribute-slot-title.$slot")
                    if (!appended) {
                        currentLore.add(TextUtil.mm(slotTitle, true))
                        appended = true
                    }
                    val attrName = config.string("attribute-name.${entry.key.translationKey()}", entry.key.translationKey())
                    var attrValue = config.string("attribute-value", "<dark_green><value>")
                    var value = entry.value.amount
                    if (entry.key == Attribute.ATTACK_SPEED) value += 4.0
                    val afterPoint = Math.abs(entry.value.amount - entry.value.amount.toInt())
                    attrValue = if (Math.abs(afterPoint) > 0.05) {
                        attrValue.replace("<value>", "%.1f".format(value))
                    } else {
                        attrValue.replace("<value>", value.toInt().toString())
                    }
                    currentLore.add(TextUtil.mm(attrName + attrValue, true))
                }
            }
            targetMeta.lore(currentLore)
        }

        target.itemMeta = targetMeta
        return target
    }

    private fun removeUselessEMLore(lore: List<Component>?): List<Component> {
        if (lore == null) return emptyList()
        val toSearch = config.stringList("useless-lore")
        val serializer = PlainTextComponentSerializer.plainText()
        val newLore = mutableListOf<Component>()
        var prevIsEmpty = false

        for (component in lore) {
            val line = serializer.serialize(component)
            var remove = toSearch.any { line.contains(it) }
            val noLetters = noLetters(line)
            if (onlySpaces(line) && prevIsEmpty) remove = true
            if (!remove) newLore.add(component)
            prevIsEmpty = noLetters
        }

        val iter = newLore.iterator()
        var prev: Component? = null
        while (iter.hasNext()) {
            val component = iter.next()
            val line = serializer.serialize(component)
            val prevLine = if (prev == null) "" else serializer.serialize(prev)
            if (onlySpaces(line) && onlySpaces(prevLine)) iter.remove()
            prev = component
        }
        return newLore
    }

    private fun onlySpaces(s: String?): Boolean = s?.all { it.isWhitespace() } ?: false

    private fun noLetters(s: String?): Boolean = s?.none { it.isLetter() } ?: false
}
