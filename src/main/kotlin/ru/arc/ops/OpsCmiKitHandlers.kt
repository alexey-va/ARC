package ru.arc.ops

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIPlayerInventory.CMIInventorySlot
import com.Zrips.CMI.Modules.Kits.Kit
import com.Zrips.CMI.Modules.Kits.KitsManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * CMI kit administration through CMI's Java API.
 *
 * MCP sends regular ItemSpec JSON. ARC builds Bukkit [ItemStack] objects and
 * gives them to CMI; CMI remains responsible for its runtime cache and file
 * serialization. No caller needs to read or write CMI's `!!binary` format.
 */
object OpsCmiKitHandlers {
    private val extraSlots =
        linkedMapOf(
            "helmet" to CMIInventorySlot.Helmet,
            "chestplate" to CMIInventorySlot.ChestPlate,
            "chest" to CMIInventorySlot.ChestPlate,
            "leggings" to CMIInventorySlot.Pants,
            "legs" to CMIInventorySlot.Pants,
            "boots" to CMIInventorySlot.Boots,
            "offhand" to CMIInventorySlot.OffHand,
        )

    fun listKits(name: String? = null): Map<String, Any?> =
        OpsBukkitSync.call {
            val manager = manager()
            val selected =
                if (name.isNullOrBlank()) {
                    manager.kitMap.values.toList()
                } else {
                    listOfNotNull(manager.getKit(name, true))
                }
            if (!name.isNullOrBlank() && selected.isEmpty()) {
                throw NoSuchElementException("CMI kit not found: $name")
            }
            mapOf(
                "source" to "cmi-api",
                "count" to selected.size,
                "kits" to selected.map(::kitToMap),
            )
        }

    fun preview(
        name: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            manager()
            val definition = parseDefinition(name, body)
            applyDefinition(Kit(name), definition)
            definitionToMap(definition) +
                mapOf(
                    "preview" to true,
                    "persisted" to false,
                    "source" to "cmi-api",
                )
        }

    fun upsert(
        name: String,
        body: JsonObject,
    ): Map<String, Any?> =
        OpsBukkitSync.call {
            val definition = parseDefinition(name, body)
            val manager = manager()
            val existing = manager.getKit(name, true)
            val kit = existing ?: Kit(name)

            applyDefinition(kit, definition)
            if (existing == null) {
                manager.addKit(kit)
            }
            manager.safeSave()

            mapOf(
                "source" to "cmi-api",
                "created" to (existing == null),
                "saved" to true,
                "kit" to kitToMap(kit),
            )
        }

    internal fun parseDefinition(
        name: String,
        body: JsonObject,
    ): CmiKitDefinition {
        require(name.matches(Regex("[A-Za-z0-9_]+"))) { "kit name must be alphanumeric/underscore" }

        val display = requiredString(body, "display", maxLength = 160)
        val commandName = optionalString(body, "commandName", maxLength = 64) ?: name
        require(commandName.matches(Regex("[A-Za-z0-9_]+"))) {
            "commandName must be alphanumeric/underscore"
        }

        val delay = body.get("delay")?.takeUnless(JsonElement::isJsonNull)?.asLong ?: 86400L
        require(delay >= 0) { "delay must be non-negative" }

        val iconElement = body.get("icon") ?: throw IllegalArgumentException("icon ItemSpec is required")
        require(iconElement.isJsonObject) { "icon must be an ItemSpec object" }
        val icon = buildItem(iconElement.asJsonObject).apply { amount = 1 }

        val items = linkedMapOf<Int, ItemStack>()
        body.get("items")?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonObject) { "items must be an object keyed by inventory slot 0-35" }
            for ((rawSlot, value) in element.asJsonObject.entrySet()) {
                val slot = rawSlot.toIntOrNull()
                    ?: throw IllegalArgumentException("item slot must be numeric: $rawSlot")
                require(slot in 0..35) { "item slot must be 0-35: $slot" }
                require(value.isJsonObject) { "item slot $slot must contain an ItemSpec object" }
                items[slot] = buildItem(value.asJsonObject)
            }
        }

        val extras = linkedMapOf<CMIInventorySlot, ItemStack>()
        body.get("extraItems")?.takeUnless(JsonElement::isJsonNull)?.let { element ->
            require(element.isJsonObject) { "extraItems must be an object" }
            for ((rawSlot, value) in element.asJsonObject.entrySet()) {
                val slot =
                    extraSlots[rawSlot.lowercase()]
                        ?: throw IllegalArgumentException(
                            "unsupported extraItems slot: $rawSlot (helmet, chestplate, leggings, boots, offhand)",
                        )
                require(value.isJsonObject) { "extraItems.$rawSlot must contain an ItemSpec object" }
                extras[slot] = buildItem(value.asJsonObject)
            }
        }

        val commands = stringList(body, "commands", maxEntries = 64, maxLength = 512)
        require(items.isNotEmpty() || extras.isNotEmpty() || commands.isNotEmpty()) {
            "kit needs items, extraItems, and/or commands"
        }

        return CmiKitDefinition(
            name = name,
            commandName = commandName,
            display = display,
            delay = delay,
            enabled = body.get("enabled")?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: true,
            icon = icon,
            items = items,
            extraItems = extras,
            commands = commands,
            conditions = stringList(body, "conditions", maxEntries = 32, maxLength = 256),
            description = stringList(body, "description", maxEntries = 32, maxLength = 256),
            cost = body.get("cost")?.takeUnless(JsonElement::isJsonNull)?.asDouble ?: 0.0,
            expCost = body.get("expCost")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0,
            group = optionalString(body, "group", maxLength = 64).orEmpty(),
            weight = body.get("weight")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0,
            slot = optionalNonNegativeInt(body, "slot"),
            page = optionalNonNegativeInt(body, "page"),
            maxUsages = body.get("maxUsages")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 0,
            showDespiteUsage =
                body.get("showDespiteUsage")?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false,
            showDespiteWeight =
                body.get("showDespiteWeight")?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false,
            dropItems = body.get("dropItems")?.takeUnless(JsonElement::isJsonNull)?.asBoolean ?: false,
        ).also {
            require(it.cost >= 0.0) { "cost must be non-negative" }
            require(it.expCost >= 0) { "expCost must be non-negative" }
            require(it.maxUsages >= 0) { "maxUsages must be non-negative" }
        }
    }

    private fun applyDefinition(
        kit: Kit,
        definition: CmiKitDefinition,
    ) {
        kit.setName(definition.name)
        kit.commandName = definition.commandName
        kit.displayName = definition.display
        kit.delay = definition.delay
        kit.isEnabled = definition.enabled
        kit.icon = definition.icon.clone()
        kit.iconOff = definition.icon.clone()
        kit.commands = definition.commands
        kit.conditions = definition.conditions
        kit.description = definition.description
        kit.cost = definition.cost
        kit.expCost = definition.expCost
        kit.group = definition.group
        kit.weight = definition.weight
        kit.slot = definition.slot
        kit.page = definition.page
        kit.maxUsages = definition.maxUsages
        kit.isShowDespiteUsage = definition.showDespiteUsage
        kit.isShowDespiteWeight = definition.showDespiteWeight
        kit.isDropItems = definition.dropItems

        val inventory = MutableList<ItemStack?>(36) { null }
        definition.items.forEach { (slot, item) -> inventory[slot] = item.clone() }
        kit.setItem(inventory)
        for (slot in extraSlots.values.toSet()) {
            kit.setExtraItem(slot, definition.extraItems[slot]?.clone())
        }
    }

    private fun buildItem(spec: JsonObject): ItemStack {
        val preset = spec.get("preset")?.takeUnless(JsonElement::isJsonNull)?.asString?.trim()
        if (!preset.isNullOrEmpty()) {
            val amount = spec.get("amount")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: 1
            val resolved =
                ItemPresets.resolveSpecs(preset, amount).getOrElse {
                    throw IllegalArgumentException(it.message ?: "Unknown preset: $preset")
                }
            require(resolved.size == 1) {
                "preset '$preset' resolves to ${resolved.size} items; kit ItemSpec must resolve to exactly one item"
            }
            return OpsItemSpec.build(resolved.first())
        }
        return OpsItemSpec.build(spec)
    }

    private fun kitToMap(kit: Kit): Map<String, Any?> {
        val items =
            linkedMapOf<String, Any?>().apply {
                kit.items?.forEachIndexed { slot, item ->
                    if (item != null && !item.type.isAir) {
                        put(slot.toString(), OpsItemSpec.toMap(item))
                    }
                }
            }
        val extras =
            linkedMapOf<String, Any?>().apply {
                for ((name, slot) in extraSlots) {
                    if (name in setOf("chest", "legs")) continue
                    val item = kit.getExtraItem(slot)
                    if (item != null && !item.type.isAir) {
                        put(name, OpsItemSpec.toMap(item))
                    }
                }
            }
        return mapOf(
            "name" to kit.configName,
            "commandName" to kit.commandName,
            "display" to kit.displayName,
            "enabled" to kit.isEnabled,
            "delay" to kit.delay,
            "icon" to OpsItemSpec.toMap(kit.icon),
            "items" to items,
            "extraItems" to extras,
            "commands" to (kit.commands ?: emptyList<String>()),
            "conditions" to (kit.conditions ?: emptyList<String>()),
            "description" to (kit.description ?: emptyList<String>()),
            "cost" to kit.cost,
            "expCost" to kit.expCost,
            "group" to kit.group,
            "weight" to kit.weight,
            "slot" to kit.slot,
            "page" to kit.page,
            "maxUsages" to kit.maxUsages,
            "showDespiteUsage" to kit.isShowDespiteUsage,
            "showDespiteWeight" to kit.isShowDespiteWeight,
            "dropItems" to kit.isDropItems,
        )
    }

    private fun definitionToMap(definition: CmiKitDefinition): Map<String, Any?> =
        mapOf(
            "name" to definition.name,
            "commandName" to definition.commandName,
            "display" to definition.display,
            "enabled" to definition.enabled,
            "delay" to definition.delay,
            "icon" to OpsItemSpec.toMap(definition.icon),
            "items" to definition.items.mapKeys { it.key.toString() }.mapValues { OpsItemSpec.toMap(it.value) },
            "extraItems" to
                definition.extraItems.mapKeys { it.key.name }.mapValues { OpsItemSpec.toMap(it.value) },
            "commands" to definition.commands,
            "conditions" to definition.conditions,
            "description" to definition.description,
            "cost" to definition.cost,
            "expCost" to definition.expCost,
            "group" to definition.group,
            "weight" to definition.weight,
            "slot" to definition.slot,
            "page" to definition.page,
            "maxUsages" to definition.maxUsages,
            "showDespiteUsage" to definition.showDespiteUsage,
            "showDespiteWeight" to definition.showDespiteWeight,
            "dropItems" to definition.dropItems,
        )

    private fun manager(): KitsManager {
        val plugin = Bukkit.getPluginManager().getPlugin("CMI")
        check(plugin?.isEnabled == true) { "CMI plugin is not enabled" }
        return CMI.getInstance().kitsManager
    }

    private fun requiredString(
        body: JsonObject,
        field: String,
        maxLength: Int,
    ): String =
        optionalString(body, field, maxLength)
            ?: throw IllegalArgumentException("$field is required")

    private fun optionalString(
        body: JsonObject,
        field: String,
        maxLength: Int,
    ): String? {
        val value = body.get(field)?.takeUnless(JsonElement::isJsonNull)?.asString?.trim() ?: return null
        require(value.isNotEmpty()) { "$field must not be empty" }
        require(value.length <= maxLength) { "$field is longer than $maxLength characters" }
        return value
    }

    private fun stringList(
        body: JsonObject,
        field: String,
        maxEntries: Int,
        maxLength: Int,
    ): List<String> {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return emptyList()
        require(element.isJsonArray) { "$field must be an array of strings" }
        require(element.asJsonArray.size() <= maxEntries) { "$field has more than $maxEntries entries" }
        return element.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "$field[$index] must be a string"
            }
            value.asString.trim().also {
                require(it.isNotEmpty()) { "$field[$index] must not be empty" }
                require(it.length <= maxLength) { "$field[$index] is longer than $maxLength characters" }
            }
        }
    }

    private fun optionalNonNegativeInt(
        body: JsonObject,
        field: String,
    ): Int? {
        val value = body.get(field)?.takeUnless(JsonElement::isJsonNull)?.asInt ?: return null
        require(value >= 0) { "$field must be non-negative" }
        return value
    }
}

internal data class CmiKitDefinition(
    val name: String,
    val commandName: String,
    val display: String,
    val delay: Long,
    val enabled: Boolean,
    val icon: ItemStack,
    val items: Map<Int, ItemStack>,
    val extraItems: Map<CMIInventorySlot, ItemStack>,
    val commands: List<String>,
    val conditions: List<String>,
    val description: List<String>,
    val cost: Double,
    val expCost: Int,
    val group: String,
    val weight: Int,
    val slot: Int?,
    val page: Int?,
    val maxUsages: Int,
    val showDespiteUsage: Boolean,
    val showDespiteWeight: Boolean,
    val dropItems: Boolean,
)
