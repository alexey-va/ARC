package ru.arc.itemcatalog

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.Locale

data class ItemsCatalogSettings(
    val enabled: Boolean,
    val config: Config,
    val hiddenCategoryIds: Set<String>,
    val hiddenItemPatterns: List<String>,
    val groups: List<CatalogGroupDefinition>,
    val showAll: Boolean,
    val allDisplayName: String,
    val allDescription: List<String>,
    val allPermission: String?,
    val allIcon: CatalogIconStyle,
    val categoryFallbackIcon: CatalogIconStyle,
    val pageIndicatorIcon: CatalogIconStyle,
    val loadingMessage: String,
    val unavailableMessage: String,
)

class ItemsCatalogModuleConfig(private val config: Config) {
    fun snapshot(): ItemsCatalogSettings {
        val groups =
            config.keys("groups").map { rawId ->
                val id = rawId.trim().lowercase(Locale.ROOT)
                require(id == rawId && validId(id)) { "Items catalog group id '$rawId' must be normalized" }
                val root = "groups.$id"
                val patterns = config.stringList("$root.categories").map(String::trim).filter(String::isNotEmpty)
                require(patterns.isNotEmpty()) { "Items catalog group '$id' must select at least one category" }
                require(patterns.size <= 256) { "Items catalog group '$id' has too many category patterns" }
                patterns.forEach { require(it.length <= 256) { "Items catalog group '$id' has an oversized pattern" } }
                val patternIssues = mutableListOf<CatalogBuildIssue>()
                patterns.forEach { ItemsCatalogPlanner.matchesPattern(it, "validation-probe", patternIssues, "group:$id") }
                require(patternIssues.isEmpty()) { "Items catalog group '$id' has an invalid category pattern" }
                CatalogGroupDefinition(
                    id = id,
                    order = config.integer("$root.order", 100),
                    displayName = bounded(config.string("$root.name", id), 48, "group '$id' name"),
                    description = boundedLines(config.stringList("$root.description"), "group '$id' description"),
                    categoryPatterns = patterns,
                    icon = icon("$root.icon", Material.CHEST),
                )
            }.sortedWith(compareBy<CatalogGroupDefinition> { it.order }.thenBy { it.id })
        require(groups.size <= 32) { "Items catalog supports at most 32 custom groups" }

        val hidden = config.stringList("hidden-categories").map { it.trim().lowercase(Locale.ROOT) }.filter(::validId).toSet()
        require(hidden.size <= 1_000) { "Items catalog has too many hidden categories" }
        val hiddenItems = config.stringList("hidden-items").map(String::trim).filter(String::isNotEmpty)
        require(hiddenItems.size <= 1_000) { "Items catalog has too many hidden item patterns" }
        hiddenItems.forEach { require(it.length <= 256) { "Items catalog has an oversized hidden item pattern" } }
        val hiddenItemIssues = mutableListOf<CatalogBuildIssue>()
        hiddenItems.forEach { ItemsCatalogPlanner.matchesPattern(it, "validation:probe", hiddenItemIssues, "hidden-items") }
        require(hiddenItemIssues.isEmpty()) { "Items catalog has an invalid hidden item pattern" }

        return ItemsCatalogSettings(
            enabled = config.bool("enabled", false),
            config = config,
            hiddenCategoryIds = hidden,
            hiddenItemPatterns = hiddenItems,
            groups = groups,
            showAll = config.bool("all-items.enabled", true),
            allDisplayName = bounded(config.string("all-items.name", "Все предметы"), 48, "all-items name"),
            allDescription = boundedLines(config.stringList("all-items.description"), "all-items description"),
            allPermission = config.string("all-items.permission", "arc.items-catalog.all").trim().ifEmpty { null },
            allIcon = icon("all-items.icon", Material.CHEST),
            categoryFallbackIcon = icon("gui.category-fallback", Material.CHEST),
            pageIndicatorIcon = icon("gui.page-indicator", Material.PAPER),
            loadingMessage = bounded(config.string("messages.loading", "<gray>Каталог предметов ещё загружается."), 180, "loading message"),
            unavailableMessage = bounded(config.string("messages.unavailable", "<red>Каталог предметов сейчас недоступен."), 180, "unavailable message"),
        )
    }

    private fun icon(path: String, fallback: Material): CatalogIconStyle {
        val rawMaterial = config.string("$path.material", fallback.name).trim().uppercase(Locale.ROOT)
        val material = Material.matchMaterial(rawMaterial)
            ?: throw IllegalArgumentException("Items catalog icon '$path' has invalid material '$rawMaterial'")
        val customModelData = config.integer("$path.customModelData", 0)
        require(customModelData >= 0) { "Items catalog icon '$path' customModelData cannot be negative" }
        return CatalogIconStyle(material.name, customModelData)
    }

    private fun bounded(value: String, limit: Int, field: String): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty()) { "Items catalog $field cannot be blank" }
        require(normalized.codePointCount(0, normalized.length) <= limit) { "Items catalog $field is too long" }
        return normalized
    }

    private fun boundedLines(lines: List<String>, field: String): List<String> {
        require(lines.size <= 8) { "Items catalog $field has too many lines" }
        return lines.map { bounded(it, 100, field) }
    }

    private fun validId(value: String): Boolean =
        value.length in 1..96 && value.all { it.isLetterOrDigit() || it in "_-" }

    companion object {
        fun load(dataPath: Path): ItemsCatalogModuleConfig =
            ItemsCatalogModuleConfig(ConfigManager.ofModule(dataPath, "items-catalog.yml"))
    }
}
