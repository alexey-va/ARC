package ru.arc.itemcatalog

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.Locale

/** Strict parser for the portable, disabled-by-default reward catalogue. */
class RewardCatalogModuleConfig(private val config: Config) {
    fun snapshot(): RewardCatalogSettings {
        val enabled =
            config.booleanOrNull("enabled")
                ?: if (!config.exists("enabled")) false else throw invalid("enabled", "expected boolean")
        val title = requiredString(config.stringOrNull("title"), "title", TITLE_LIMIT)
        val categoriesMap = mapAt("categories")
        require(categoriesMap.size <= MAX_CATEGORIES) {
            "Reward catalog supports at most $MAX_CATEGORIES categories"
        }
        val categories =
            categoriesMap
                .entries
                .map { (rawId, rawValue) -> parseCategory(rawId, rawValue) }
        val messages = parseMessages()
        val rootIcon = optionalIcon("root-icon", CatalogIconStyle("CHEST"))
        require(categories.sumOf { it.entries.size } <= MAX_ENTRIES) {
            "Reward catalog supports at most $MAX_ENTRIES entries"
        }
        return RewardCatalogSettings(enabled, title, categories, messages, rootIcon)
    }

    private fun parseCategory(rawId: String, rawValue: Any?): RewardCatalogCategory {
        val id = normalizedId(rawId, "category")
        val map = strictMap(rawValue, "categories.$id")
        rejectUnknown(map, CATEGORY_KEYS, "categories.$id")
        val name = requiredString(map.required("name", "categories.$id"), "categories.$id.name", NAME_LIMIT)
        val description = stringList(map.required("description", "categories.$id"), "categories.$id.description")
        val icon = material(map.required("icon", "categories.$id"), "categories.$id.icon")
        val entriesMap = strictMap(map.required("entries", "categories.$id"), "categories.$id.entries")
        require(entriesMap.size <= MAX_ENTRIES_PER_CATEGORY) {
            "Reward catalog category '$id' supports at most $MAX_ENTRIES_PER_CATEGORY entries"
        }
        val entries =
            entriesMap
                .entries
                .map { (entryId, value) -> parseEntry(id, entryId, value) }
        return RewardCatalogCategory(id, name, description, icon, entries)
    }

    private fun parseEntry(categoryId: String, rawId: String, rawValue: Any?): RewardCatalogEntry {
        val id = normalizedId(rawId, "entry in category '$categoryId'")
        val path = "categories.$categoryId.entries.$id"
        val map = strictMap(rawValue, path)
        rejectUnknown(map, ENTRY_KEYS, path)
        val name = if ("name" in map) requiredString(map["name"], "$path.name", NAME_LIMIT) else null
        val description = stringList(map.required("description", path), "$path.description")
        val rarity = if ("rarity" in map) requiredString(map["rarity"], "$path.rarity", RARITY_LIMIT) else null
        val requires =
            if ("requires" in map) pluginList(map["requires"], "$path.requires") else emptyList()
        val sourceKeys = SOURCE_KEYS.filter(map::containsKey)
        require(sourceKeys.size == 1) {
            "$path must contain exactly one of treasure, preset or pouch"
        }
        val source =
            when (val key = sourceKeys.single()) {
                "treasure" -> parseTreasure(map.getValue(key), "$path.treasure")
                "preset" -> RewardCatalogSource.Preset(requiredId(map.getValue(key), "$path.preset", PRESET_ID))
                "pouch" -> RewardCatalogSource.Pouch(requiredId(map.getValue(key), "$path.pouch", POUCH_ID))
                else -> error("unreachable source key")
            }
        val icon = if ("icon" in map) material(map["icon"], "$path.icon") else null
        return RewardCatalogEntry(id, name, description, rarity, requires, source, icon)
    }

    private fun parseTreasure(raw: Any?, path: String): RewardCatalogSource.Treasure {
        val map = strictMap(raw, path)
        rejectUnknown(map, setOf("pool", "id"), path)
        val pool = requiredId(map.required("pool", path), "$path.pool", POOL_ID)
        val id = requiredId(map.required("id", path), "$path.id", TREASURE_ID)
        return RewardCatalogSource.Treasure(pool, id)
    }

    private fun parseMessages(): RewardCatalogMessages {
        val path = "messages"
        if (!config.exists(path)) return RewardCatalogMessages.DEFAULT
        val map = mapAt(path)
        rejectUnknown(map, MESSAGE_KEYS, path)
        fun message(key: String, fallback: String) =
            if (key in map) requiredString(map[key], "$path.$key", MESSAGE_LIMIT)
            else bounded(fallback, "$path.$key", MESSAGE_LIMIT)
        return RewardCatalogMessages(
            unavailable = message("unavailable", RewardCatalogMessages.DEFAULT.unavailable),
            inventoryFull = message("inventory-full", RewardCatalogMessages.DEFAULT.inventoryFull),
            given = message("given", RewardCatalogMessages.DEFAULT.given),
            accepted = message("accepted", RewardCatalogMessages.DEFAULT.accepted),
            actionFailed = message("action-failed", RewardCatalogMessages.DEFAULT.actionFailed),
        )
    }

    private fun mapAt(path: String): Map<String, Any?> =
        try {
            strictMap(config.map<Any?>(path), path)
        } catch (failure: ClassCastException) {
            throw invalid(path, "expected map", failure)
        }

    private fun stringList(raw: Any?, path: String): List<String> {
        val list = raw as? List<*> ?: throw invalid(path, "expected list of strings")
        require(list.size <= MAX_DESCRIPTION_LINES) { "$path supports at most $MAX_DESCRIPTION_LINES lines" }
        return list.mapIndexed { index, value ->
            requiredString(value, "$path[$index]", DESCRIPTION_LIMIT)
        }
    }

    private fun pluginList(raw: Any?, path: String): List<String> {
        val values = stringList(raw, path)
        require(values.size <= MAX_REQUIRED_PLUGINS) { "$path supports at most $MAX_REQUIRED_PLUGINS plugins" }
        return values.mapIndexed { index, value ->
            require(PLUGIN_ID.matches(value)) { "$path[$index] has an invalid plugin name" }
            value
        }.distinct()
    }

    private fun material(raw: Any?, path: String): CatalogIconStyle =
        when (raw) {
            is String -> materialStyle(raw, path)
            is Map<*, *> -> {
                val map = strictMap(raw, path)
                rejectUnknown(map, ICON_KEYS, path)
                val material = materialStyle(requiredString(map.required("material", path), "$path.material", 64), "$path.material")
                val customModelData =
                    if ("custom-model-data" in map) {
                        integer(map["custom-model-data"], "$path.custom-model-data")
                    } else {
                        0
                    }
                CatalogIconStyle(material.material, customModelData)
            }
            else -> throw invalid(path, "expected material string or icon map")
        }

    private fun materialStyle(raw: String, path: String): CatalogIconStyle {
        val rawMaterial = requiredString(raw, path, 64).uppercase(Locale.ROOT).removePrefix("MINECRAFT:")
        val material = Material.matchMaterial(rawMaterial)
            ?: throw invalid(path, "unknown material '$rawMaterial'")
        require(!material.isAir) { "$path cannot use AIR" }
        return CatalogIconStyle(material.name)
    }

    private fun integer(raw: Any?, path: String): Int {
        val value = raw as? Number ?: throw invalid(path, "expected integer")
        val long = value.toLong()
        require(value.toDouble() == long.toDouble() && long in 0..Int.MAX_VALUE) {
            "$path must be a non-negative integer"
        }
        return long.toInt()
    }

    private fun optionalIcon(path: String, fallback: CatalogIconStyle): CatalogIconStyle {
        if (!config.exists(path)) return fallback
        return try {
            material(config.map<Any?>(path), path)
        } catch (_: ClassCastException) {
            material(config.stringOrNull(path), path)
        }
    }

    private fun requiredId(raw: Any?, path: String, pattern: Regex): String {
        val value = requiredString(raw, path, ID_LIMIT)
        require(pattern.matches(value)) { "$path has an invalid identifier" }
        return value
    }

    private fun normalizedId(raw: String, kind: String): String {
        require(raw == raw.trim() && ID.matches(raw)) { "Invalid $kind id '$raw'" }
        return raw
    }

    private fun requiredString(raw: Any?, path: String, limit: Int): String {
        require(raw is String) { "$path must be a string" }
        return bounded(raw, path, limit)
    }

    private fun bounded(raw: Any, path: String, limit: Int): String {
        val value = raw.toString().trim()
        require(value.isNotEmpty()) { "$path cannot be blank" }
        require(value.codePointCount(0, value.length) <= limit) { "$path is too long" }
        return value
    }

    private fun strictMap(raw: Any?, path: String): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: throw invalid(path, "expected map")
        return map.entries.associate { (key, value) ->
            require(key is String) { "$path has a non-string key" }
            key to value
        }
    }

    private fun rejectUnknown(map: Map<String, Any?>, allowed: Set<String>, path: String) {
        val unknown = map.keys - allowed
        require(unknown.isEmpty()) { "$path has unknown key(s): ${unknown.sorted().joinToString(", ")}" }
    }

    private fun invalid(path: String, reason: String, cause: Throwable? = null): IllegalArgumentException =
        IllegalArgumentException("Invalid reward catalog $path: $reason", cause)

    private fun Map<String, Any?>.required(key: String, path: String): Any =
        this[key] ?: throw invalid("$path.$key", "is required")

    companion object {
        const val MAX_CATEGORIES = 64
        const val MAX_ENTRIES_PER_CATEGORY = 300
        const val MAX_ENTRIES = 2_000
        private const val TITLE_LIMIT = 160
        private const val NAME_LIMIT = 120
        private const val DESCRIPTION_LIMIT = 180
        private const val RARITY_LIMIT = 48
        private const val MESSAGE_LIMIT = 180
        private const val ID_LIMIT = 96
        private const val MAX_DESCRIPTION_LINES = 12
        private const val MAX_REQUIRED_PLUGINS = 16
        private val ID = Regex("[a-z][a-z0-9_-]{0,95}")
        private val POOL_ID = Regex("[a-z][a-z0-9_-]{0,63}")
        private val TREASURE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
        private val PRESET_ID = Regex("[a-z][a-z0-9_]{0,63}")
        private val POUCH_ID = PRESET_ID
        private val PLUGIN_ID = Regex("[A-Za-z0-9._-]{1,64}")
        private val CATEGORY_KEYS = setOf("name", "description", "icon", "entries")
        private val ENTRY_KEYS = setOf("name", "description", "rarity", "requires", "treasure", "preset", "pouch", "icon")
        private val ICON_KEYS = setOf("material", "custom-model-data")
        private val SOURCE_KEYS = setOf("treasure", "preset", "pouch")
        private val MESSAGE_KEYS = setOf("unavailable", "inventory-full", "given", "accepted", "action-failed")

        fun load(dataPath: Path): RewardCatalogModuleConfig =
            RewardCatalogModuleConfig(ConfigManager.ofModule(dataPath, "reward-catalog.yml"))
    }
}
