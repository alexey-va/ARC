package ru.arc.util

import org.bukkit.Material
import ru.arc.configs.Config

/**
 * Unified YAML/JSON item spec — same shape as [ru.arc.ops.OpsItemSpec] and `item-presets.yml`.
 *
 * ```yaml
 * locale.profile-menu.balance:
 *   material: STICK
 *   display: <gold>Баланс
 *   lore:
 *     - <gray>Баланс: <balance>
 *   customModelData: 11138
 * ```
 */
data class ConfigItemSpec(
    val material: Material? = null,
    val display: String? = null,
    val lore: List<String>? = null,
    val modelData: Int? = null,
) {
    fun hasContent(): Boolean =
        material != null || display != null || lore != null || modelData != null

    /** Config values override [fallback] where present. */
    fun overlayWithConfig(fallback: ConfigItemSpec): ConfigItemSpec =
        ConfigItemSpec(
            material = material ?: fallback.material,
            display = display ?: fallback.display,
            lore = lore ?: fallback.lore,
            modelData = modelData ?: fallback.modelData,
        )

    fun toConfigMap(): Map<String, Any> {
        val map = linkedMapOf<String, Any>()
        material?.let { map["material"] = it.name.lowercase() }
        display?.let { map["display"] = it }
        lore?.takeIf { it.isNotEmpty() }?.let { map["lore"] = it }
        modelData?.takeIf { it != 0 }?.let { map["customModelData"] = it }
        return map
    }

    fun applyTo(target: ItemConfigTarget) {
        material?.let { target.applyMaterial(it) }
        display?.let { target.applyDisplay(it) }
        lore?.let { target.applyLore(it) }
        modelData?.let { target.applyModelData(it) }
    }

    companion object {
        val EMPTY = ConfigItemSpec()

        fun fromTarget(target: ItemConfigTarget): ConfigItemSpec =
            ConfigItemSpec(
                material = target.peekMaterial(),
                display = target.peekDisplay(),
                lore = target.peekLore(),
                modelData = target.peekModelData(),
            )

        fun readFromConfig(
            config: Config,
            path: String,
        ): ConfigItemSpec? = readNested(config, path)

        fun fromMap(map: Map<String, Any>): ConfigItemSpec {
            val material =
                stringField(map, "material")?.let { raw ->
                    Material.matchMaterial(raw) ?: Material.matchMaterial(raw.uppercase())
                }
            val display = stringField(map, "display", "display-name", "displayName", "name")
            val lore = listField(map, "lore")
            val modelData =
                intField(map, "customModelData", "modelData", "model-data", "custom-model-data")
            if (material == null && display == null && lore == null && modelData == null) {
                return EMPTY
            }
            return ConfigItemSpec(material, display, lore, modelData)
        }

        /** Parse JSON / ops item object fields into [ConfigItemSpec]. */
        fun fromJsonFields(map: Map<String, Any>): ConfigItemSpec = fromMap(map)

        private fun readNested(
            config: Config,
            path: String,
        ): ConfigItemSpec? {
            if (!config.exists(path)) return null
            @Suppress("UNCHECKED_CAST")
            val map = config.map(path, emptyMap<String, Any>())
            if (map.isEmpty() || !looksLikeItemSpec(map)) return null
            val spec = fromMap(map)
            return spec.takeIf { it.hasContent() }
        }

        private fun looksLikeItemSpec(map: Map<String, Any>): Boolean =
            map.containsKey("display") ||
                map.containsKey("display-name") ||
                map.containsKey("displayName") ||
                map.containsKey("name") ||
                map.containsKey("lore") ||
                map.containsKey("material") ||
                map.containsKey("customModelData") ||
                map.containsKey("modelData") ||
                map.containsKey("model-data")

        private fun stringField(
            map: Map<String, Any>,
            vararg keys: String,
        ): String? {
            for (key in keys) {
                val value = map[key] ?: continue
                if (value is String && value.isNotBlank()) return value.trim()
            }
            return null
        }

        private fun intField(
            map: Map<String, Any>,
            vararg keys: String,
        ): Int? {
            for (key in keys) {
                val value = map[key] ?: continue
                when (value) {
                    is Number -> return value.toInt()
                    is String -> value.toIntOrNull()?.let { return it }
                }
            }
            return null
        }

        @Suppress("UNCHECKED_CAST")
        private fun listField(
            map: Map<String, Any>,
            key: String,
        ): List<String>? {
            val value = map[key] ?: return null
            return when (value) {
                is List<*> -> value.filterIsInstance<String>().takeIf { it.isNotEmpty() }
                is String -> listOf(value)
                else -> null
            }
        }
    }
}

internal fun applyItemFromConfig(
    config: Config,
    path: String,
    target: ItemConfigTarget,
) {
    val codeDefaults = ConfigItemSpec.fromTarget(target)
    val fromFile = ConfigItemSpec.readFromConfig(config, path)

    if (fromFile == null && codeDefaults.hasContent()) {
        ItemConfigTagComment.applyOnInject(
            config,
            path,
            target.peekRegisteredTags(),
            codeDefaults.display,
            codeDefaults.lore,
        )
        config.injectDeepKey(path, codeDefaults.toConfigMap())
    }

    fromFile?.overlayWithConfig(codeDefaults)?.applyTo(target) ?: codeDefaults.applyTo(target)
}
