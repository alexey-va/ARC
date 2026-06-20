package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import de.tr7zw.changeme.nbtapi.NBT
import de.tr7zw.changeme.nbtapi.NBTItem
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.util.ConfigItemSpec
import ru.arc.util.applySpec
import ru.arc.util.customModelDataOrNull
import ru.arc.util.itemStack

/**
 * JSON item specification for ops HTTP item endpoints.
 *
 * Example:
 * ```json
 * {
 *   "material": "STICK",
 *   "amount": 1,
 *   "display": "<gold>Coins",
 *   "lore": ["<gray>Line 1"],
 *   "customModelData": 11138,
 *   "itemsadder": "iageneric:bag_of_coins",
 *   "enchants": {"sharpness": 5},
 *   "unbreakable": true,
 *   "itemFlags": ["HIDE_ENCHANTS"],
 *   "glowing": true,
 *   "nbt": "{CustomTag:1b}",
 *   "customData": {"arc:treasure_key": "sf", "slimefun_token": 1}
 * }
 * ```
 */
object OpsItemSpec {
    private val miniMessage = MiniMessage.miniMessage()

    private val knownItemsAdder: Map<String, Pair<Material, Int>> =
        mapOf(
            "arc:background" to (Material.GRAY_STAINED_GLASS_PANE to 11000),
            "arc:back_gray" to (Material.GRAY_STAINED_GLASS_PANE to 11004),
            "arc:back" to (Material.BLUE_STAINED_GLASS_PANE to 11001),
            "arc:cancel" to (Material.RED_STAINED_GLASS_PANE to 91002),
            "arc:confirm_gray" to (Material.GREEN_STAINED_GLASS_PANE to 91007),
            "arc:comment_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11003),
            "arc:search_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11005),
            "arc:web_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11006),
            "arc:right_blue_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11008),
            "arc:left_blue_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11009),
            "arc:refresh_gray" to (Material.BLACK_STAINED_GLASS_PANE to 11010),
            "arc:gray_arrow_chest" to (Material.CYAN_STAINED_GLASS_PANE to 11011),
            "arc:right_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11012),
            "arc:left_gray" to (Material.BLUE_STAINED_GLASS_PANE to 11013),
            "arc:change_category" to (Material.WHITE_STAINED_GLASS_PANE to 11019),
            "arc:warp_list" to (Material.CYAN_STAINED_GLASS_PANE to 11020),
            "arc:auction" to (Material.RED_STAINED_GLASS_PANE to 11021),
            "iageneric:gold_coin" to (Material.STICK to 11137),
            "iageneric:bag_of_coins" to (Material.STICK to 11138),
        )

    fun build(json: JsonObject): ItemStack {
        val itemsAdderId = json.stringOrNull("itemsadder") ?: json.stringOrNull("ia")
        val mapped = itemsAdderId?.let { knownItemsAdder[it.lowercase()] }
        val itemFields = ConfigItemSpec.fromJsonFields(json.toItemFieldMap())

        val materialName = itemFields.material?.name
            ?: json.stringOrNull("material")
            ?: mapped?.first?.name
            ?: throw IllegalArgumentException("material or itemsadder required")

        val material =
            Material.matchMaterial(materialName)
                ?: throw IllegalArgumentException("Unknown material: $materialName")

        val amount = json.get("amount")?.takeIf { !it.isJsonNull }?.asInt?.coerceAtLeast(1) ?: 1
        val modelData =
            itemFields.modelData
                ?: json.get("customModelData")?.takeIf { !it.isJsonNull }?.asInt
                ?: json.get("modelData")?.takeIf { !it.isJsonNull }?.asInt
                ?: mapped?.second
                ?: 0

        val display = itemFields.display ?: json.stringOrNull("display")
        val lore = itemFields.lore ?: json.stringList("lore")
        val unbreakable = json.get("unbreakable")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val glowing = json.get("glowing")?.takeIf { !it.isJsonNull }?.asBoolean == true
        val enchants = parseEnchants(json.get("enchants"))
        val flags = parseItemFlags(json.get("itemFlags"))

        val stack =
            itemStack(material, amount) {
                applySpec(
                    ConfigItemSpec(
                        material = null,
                        display = display,
                        lore = lore,
                        modelData = modelData.takeIf { it != 0 },
                    ),
                    applyMaterial = false,
                )
                enchants.forEach { (enchant, level) ->
                    enchant(enchant, level)
                }
                if (flags.isNotEmpty()) {
                    flags(*flags.toTypedArray())
                }
                if (glowing) {
                    glowing()
                }
                if (unbreakable) {
                    unbreakable(true)
                }
            }

        json.get("customData")?.takeIf { !it.isJsonNull }?.let { element ->
            applyCustomData(stack, element)
        }

        json.stringOrNull("nbt")?.let { snbt ->
            applySnbt(stack, snbt)
        }

        return stack
    }

    fun toMap(stack: ItemStack?): Map<String, Any?> {
        if (stack == null || stack.type.isAir) {
            return mapOf("empty" to true)
        }

        val meta = stack.itemMeta
        val result = linkedMapOf<String, Any?>(
            "material" to stack.type.name,
            "amount" to stack.amount,
        )

        meta?.displayName()?.let { name ->
            result["display"] = miniMessage.serialize(name)
        }

        meta?.lore()?.takeIf { it.isNotEmpty() }?.let { lines ->
            result["lore"] = lines.map { miniMessage.serialize(it) }
        }

        stack.customModelDataOrNull?.let { result["customModelData"] = it }

        meta?.enchants?.takeIf { it.isNotEmpty() }?.let { enchants ->
            result["enchants"] =
                enchants.entries.associate { (enchant, level) ->
                    enchant.key.key to level
                }
        }

        if (meta?.isUnbreakable == true) {
            result["unbreakable"] = true
        }

        meta?.itemFlags?.takeIf { it.isNotEmpty() }?.let { flags ->
            result["itemFlags"] = flags.map { it.name }
        }

        if (System.getProperty("arc.test.unit") == null) runCatching {
            val compound = NBTItem(stack, false).compound ?: return@runCatching
            val snbt = compound.toString()
            if (snbt.isNotBlank() && snbt != "{}") {
                result["nbt"] = snbt
            }
        }

        return result
    }

    private fun applyCustomData(
        stack: ItemStack,
        element: JsonElement,
    ) {
        if (!element.isJsonObject) {
            throw IllegalArgumentException("customData must be a JSON object")
        }
        val data = element.asJsonObject
        if (data.size() == 0) return

        if (System.getProperty("arc.test.unit") != null) return
        // Paper 1.20.5+ stores token fields in minecraft:custom_data; NBTItem writes root tags
        // that Denizen and BlockListener (NBT.get) do not see.
        NBT.modify(stack) { nbt ->
            for ((key, value) in data.entrySet()) {
                if (value.isJsonNull) continue
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                        nbt.setBoolean(key, value.asBoolean)
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                        val num = value.asJsonPrimitive
                        if (num.asString.contains('.')) {
                            nbt.setDouble(key, num.asDouble)
                        } else {
                            nbt.setInteger(key, num.asInt)
                        }
                    }
                    value.isJsonPrimitive -> nbt.setString(key, value.asString)
                    else -> throw IllegalArgumentException("customData.$key must be a scalar value")
                }
            }
        }
    }

    internal fun customDataToSnbt(element: JsonElement): String {
        if (!element.isJsonObject) {
            throw IllegalArgumentException("customData must be a JSON object")
        }
        return element.asJsonObject.toSnbtCompound()
    }

    private fun JsonObject.toSnbtCompound(): String {
        val entries =
            entrySet().mapNotNull { (key, value) ->
                if (value.isJsonNull) return@mapNotNull null
                "${formatSnbtKey(key)}:${formatSnbtValue(value)}"
            }
        return "{${entries.joinToString(",")}}"
    }

    private fun formatSnbtKey(key: String): String =
        if (key.contains(':') || !key.matches(Regex("[A-Za-z0-9_]+"))) "\"$key\"" else key

    private fun formatSnbtValue(value: JsonElement): String =
        when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                if (value.asBoolean) "1b" else "0b"
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> {
                val num = value.asJsonPrimitive
                if (num.asString.contains('.')) "${num.asDouble}d" else "${num.asInt}"
            }
            value.isJsonPrimitive -> "\"${value.asString.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            else -> throw IllegalArgumentException("customData values must be scalar")
        }

    private fun applySnbt(
        stack: ItemStack,
        snbt: String,
    ) {
        val trimmed = snbt.trim()
        if (trimmed.isEmpty()) return
        val item = NBTItem(stack)
        val compound = NBT.parseNBT(trimmed)
        item.mergeCompound(compound)
    }

    private fun parseEnchants(element: JsonElement?): Map<Enchantment, Int> {
        if (element == null || element.isJsonNull) return emptyMap()
        if (!element.isJsonObject) {
            throw IllegalArgumentException("enchants must be a JSON object")
        }

        val result = linkedMapOf<Enchantment, Int>()
        for ((rawKey, value) in element.asJsonObject.entrySet()) {
            if (value.isJsonNull) continue
            val enchant = resolveEnchantment(rawKey)
                ?: throw IllegalArgumentException("Unknown enchantment: $rawKey")
            result[enchant] = value.asInt.coerceAtLeast(1)
        }
        return result
    }

    private fun parseItemFlags(element: JsonElement?): List<ItemFlag> {
        if (element == null || element.isJsonNull) return emptyList()
        val names =
            when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { it.asStringOrNull() }
                element.isJsonPrimitive -> listOfNotNull(element.asStringOrNull())
                else -> throw IllegalArgumentException("itemFlags must be array or string")
            }
        return names.map { name ->
            runCatching { ItemFlag.valueOf(name.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Unknown item flag: $name") }
        }
    }

    private fun resolveEnchantment(raw: String): Enchantment? {
        val normalized = raw.lowercase().replace(' ', '_').replace('-', '_')
        Registry.ENCHANTMENT.get(NamespacedKey.minecraft(normalized))?.let { return it }
        @Suppress("DEPRECATION")
        return Enchantment.getByName(normalized.uppercase())
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val value = get(key) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        return when {
            value.isJsonArray ->
                value.asJsonArray.mapNotNull { element ->
                    if (element.isJsonNull) null else element.asString
                }
            value.isJsonPrimitive -> listOf(value.asString)
            else -> emptyList()
        }
    }

    private fun JsonElement.asStringOrNull(): String? =
        if (isJsonNull) null else asString

    private fun JsonObject.toItemFieldMap(): Map<String, Any> {
        val map = linkedMapOf<String, Any>()
        stringOrNull("material")?.let { map["material"] = it }
        stringOrNull("display")?.let { map["display"] = it }
        stringList("lore").takeIf { it.isNotEmpty() }?.let { map["lore"] = it }
        (get("customModelData")?.takeIf { !it.isJsonNull }?.asInt
            ?: get("modelData")?.takeIf { !it.isJsonNull }?.asInt)?.let { map["customModelData"] = it }
        return map
    }
}
