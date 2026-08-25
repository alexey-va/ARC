package ru.arc.itemcatalog

import org.bukkit.Material
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path
import java.util.Locale

data class ItemsCatalogSettings(
    val enabled: Boolean,
    val config: Config,
    val categoryOverrides: Map<String, CatalogCategoryOverride>,
    val hiddenCategoryIds: Set<String>,
    val hiddenItemPatterns: List<String>,
    val groups: List<CatalogGroupDefinition>,
    val givePermission: String,
    val recipeClicksEnabled: Boolean,
    val showAll: Boolean,
    val allDisplayName: String,
    val allDescription: List<String>,
    val allPermission: String?,
    val allIcon: CatalogIconStyle,
    val categoryFallbackIcon: CatalogIconStyle,
    val pageIndicatorIcon: CatalogIconStyle,
    val loadingMessage: String,
    val unavailableMessage: String,
    val givenMessage: String,
    val inventoryFullMessage: String,
    val noActionMessage: String,
    val actionFailedMessage: String,
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
                    itemAction = clickAction("$root.item-action", "group '$id' item action"),
                )
            }.sortedWith(compareBy<CatalogGroupDefinition> { it.order }.thenBy { it.id })
        require(groups.size <= 32) { "Items catalog supports at most 32 custom groups" }

        val configuredCategoryIds = config.keys("categories")
        require(configuredCategoryIds.size <= 1_000) { "Items catalog has too many configured categories" }
        val categoryOverrides =
            configuredCategoryIds.associate { rawId ->
                val id = rawId.trim().lowercase(Locale.ROOT)
                require(id == rawId && validId(id)) { "Items catalog category id '$rawId' must be normalized" }
                id to
                    CatalogCategoryOverride(
                        hidden = config.bool("categories.$id.hidden", false),
                        itemAction = clickAction("categories.$id.item-action", "category '$id' item action"),
                    )
            }
        val configuredHidden = categoryOverrides.filterValues(CatalogCategoryOverride::hidden).keys
        val legacyHidden =
            config.stringList("hidden-categories")
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter(::validId)
                .toSet()
        val hidden = configuredHidden + legacyHidden
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
            categoryOverrides = categoryOverrides,
            hiddenCategoryIds = hidden,
            hiddenItemPatterns = hiddenItems,
            groups = groups,
            givePermission = permission("clicks.give-permission", "arc.items-catalog.give"),
            recipeClicksEnabled = config.bool("clicks.recipes-enabled", true),
            showAll = config.bool("all-items.enabled", true),
            allDisplayName = bounded(config.string("all-items.name", "Все предметы"), 48, "all-items name"),
            allDescription = boundedLines(config.stringList("all-items.description"), "all-items description"),
            allPermission = config.string("all-items.permission", "arc.items-catalog.all").trim().ifEmpty { null },
            allIcon = icon("all-items.icon", Material.CHEST),
            categoryFallbackIcon = icon("gui.category-fallback", Material.CHEST),
            pageIndicatorIcon = icon("gui.page-indicator", Material.PAPER),
            loadingMessage = bounded(config.string("messages.loading", "<gray>Каталог предметов ещё загружается."), 180, "loading message"),
            unavailableMessage = bounded(config.string("messages.unavailable", "<red>Каталог предметов сейчас недоступен."), 180, "unavailable message"),
            givenMessage = bounded(config.string("messages.given", "<#92bed8>Предмет <white>%item% <#92bed8>добавлен в инвентарь."), 180, "given message"),
            inventoryFullMessage = bounded(config.string("messages.inventory-full", "<red>В инвентаре нет свободного места."), 180, "inventory-full message"),
            noActionMessage = bounded(config.string("messages.no-action", "<gray>У этого предмета нет доступного рецепта или действия."), 180, "no-action message"),
            actionFailedMessage = bounded(config.string("messages.action-failed", "<red>Действие сейчас недоступно."), 180, "action-failed message"),
        )
    }

    private fun clickAction(path: String, field: String): CatalogClickAction? {
        val type = config.string("$path.type", "").trim().lowercase(Locale.ROOT)
        if (type.isEmpty()) return null
        require(type == "player-command") { "Items catalog $field has unsupported type '$type'" }
        val command = config.string("$path.command", "").trim().removePrefix("/")
        require(validCatalogPlayerCommand(command)) {
            "Items catalog $field has an unsafe player command"
        }
        val hint = bounded(config.string("$path.hint", "выполнить действие"), 80, "$field hint")
        return CatalogClickAction.PlayerCommand(command, hint)
    }

    private fun permission(path: String, fallback: String): String {
        val value = config.string(path, fallback).trim()
        require(value.length in 1..160 && value.all { it.isLetterOrDigit() || it in "._-*" }) {
            "Items catalog permission '$path' is invalid"
        }
        return value
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
