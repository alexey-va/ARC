package ru.arc.ops

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.util.Common

/**
 * Named item presets loaded from `modules/item-presets.yml`.
 * Used by `/arc give` and documented for MCP Item Ops presets.
 */
object ItemPresets {
    private const val MAX_AMOUNT = 64
    private const val CONFIG_FILE = "item-presets.yml"
    private val metaKeys = setOf("description")

    private val config: Config
        get() = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), CONFIG_FILE)

    fun normalize(name: String): String = name.trim().lowercase().replace('-', '_').replace(' ', '_')

    fun allNames(): List<String> = (presetKeys() + bundleKeys()).sorted()

    fun describe(name: String): String? {
        val key = normalize(name)
        return config.stringOrNull("presets.$key.description")
            ?: config.stringOrNull("bundles.$key.description")
    }

    fun resolveStacks(
        name: String,
        amount: Int,
    ): Result<List<ItemStack>> =
        resolveSpecs(name, amount).map { specs -> specs.map(::buildStack) }

    fun resolveSpecs(
        name: String,
        amount: Int,
    ): Result<List<JsonObject>> {
        val key = normalize(name)
        val qty = amount.coerceIn(1, MAX_AMOUNT)

        if (config.exists("bundles.$key")) {
            return resolveBundle(key, qty)
        }

        return resolveSingle(key, qty)
    }

    private fun resolveBundle(
        bundleKey: String,
        scaledAmount: Int,
    ): Result<List<JsonObject>> {
        val entries =
            runCatching { config.list<Map<String, Any>>("bundles.$bundleKey.items") }
                .getOrElse { emptyList() }

        if (entries.isEmpty()) {
            return Result.failure(IllegalArgumentException("Bundle '$bundleKey' has no items"))
        }

        val specs = mutableListOf<JsonObject>()
        for (entry in entries) {
            val presetName = entry["preset"]?.toString()?.trim().orEmpty()
            if (presetName.isEmpty()) {
                return Result.failure(IllegalArgumentException("Bundle '$bundleKey' entry missing preset"))
            }
            val giveQty = parseBundleAmount(entry["amount"], scaledAmount)
            val itemSpecs = resolveSingle(normalize(presetName), giveQty).getOrElse { return Result.failure(it) }
            specs.addAll(itemSpecs)
        }
        return Result.success(specs)
    }

    private fun resolveSingle(
        key: String,
        amount: Int,
    ): Result<List<JsonObject>> {
        val template = loadPresetSpec(key)
            ?: return Result.failure(IllegalArgumentException("Unknown preset: $key"))

        val json = JsonParser.parseString(template.toString()).asJsonObject
        json.addProperty("amount", amount.coerceIn(1, MAX_AMOUNT))
        return Result.success(listOf(json))
    }

    private fun loadPresetSpec(key: String): JsonObject? {
        val base = "presets.$key"
        if (!config.exists(base)) return null

        @Suppress("UNCHECKED_CAST")
        val section = config.map<Any>(base).toMutableMap()
        metaKeys.forEach { section.remove(it) }
        if (section.isEmpty()) return null

        return Common.gson.toJsonTree(section).asJsonObject
    }

    private fun parseBundleAmount(
        raw: Any?,
        scaledAmount: Int,
    ): Int =
        when (raw) {
            null -> 1
            is Number -> raw.toInt().coerceIn(1, MAX_AMOUNT)
            is String ->
                when (raw.trim().lowercase()) {
                    "amount", "scaled" -> scaledAmount
                    else -> raw.toIntOrNull()?.coerceIn(1, MAX_AMOUNT) ?: 1
                }
            else -> 1
        }

    private fun presetKeys(): Set<String> = config.keys("presets")

    private fun bundleKeys(): Set<String> = config.keys("bundles")

    private fun buildStack(json: JsonObject): ItemStack = OpsItemSpec.build(json)
}
