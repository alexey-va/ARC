package ru.arc.itemcatalog

import java.util.Locale

data class RawItemsAdderCategory(
    val id: String,
    val enabled: Boolean,
    val name: String?,
    val iconId: String?,
    val permission: String?,
    val itemPatterns: List<String>,
    val showAllItems: Boolean = false,
    val source: String,
)

data class CatalogCategory(
    val id: String,
    val displayName: String,
    val iconId: String?,
    val permissions: Set<String>,
    val itemIds: List<String>,
)

data class CatalogGroupDefinition(
    val id: String,
    val order: Int,
    val displayName: String,
    val description: List<String>,
    val categoryPatterns: List<String>,
    val icon: CatalogIconStyle,
)

data class CatalogGroup(
    val definition: CatalogGroupDefinition,
    val categories: List<CatalogCategory>,
) {
    val itemCount: Int = categories.flatMapTo(linkedSetOf(), CatalogCategory::itemIds).size
}

data class CatalogIconStyle(
    val material: String,
    val customModelData: Int = 0,
)

data class CatalogBuildIssue(
    val code: String,
    val context: String,
)

data class ItemsCatalogSnapshot(
    val registryItemIds: List<String>,
    val groups: List<CatalogGroup>,
    val ungroupedCategories: List<CatalogCategory>,
    val issues: List<CatalogBuildIssue>,
) {
    val categoryCount: Int = groups.sumOf { it.categories.size } + ungroupedCategories.size
}

data class CatalogPlan(
    val snapshot: ItemsCatalogSnapshot,
)

data class CatalogPage<T>(
    val page: Int,
    val pages: Int,
    val totalEntries: Int,
    val entries: List<T>,
)

internal fun <T> catalogPage(
    entries: List<T>,
    requestedPage: Int,
    pageSize: Int,
): CatalogPage<T> {
    require(pageSize > 0) { "Catalog page size must be positive" }
    val pages = ((entries.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val page = requestedPage.coerceIn(0, pages - 1)
    return CatalogPage(
        page = page,
        pages = pages,
        totalEntries = entries.size,
        entries = entries.drop(page * pageSize).take(pageSize),
    )
}

object ItemsCatalogPlanner {
    const val MAX_REGISTRY_ITEMS = 20_000
    private const val MAX_PATTERN_LENGTH = 256

    fun plan(
        rawCategories: List<RawItemsAdderCategory>,
        registryIds: Set<String>,
        groupDefinitions: List<CatalogGroupDefinition>,
        hiddenCategoryIds: Set<String> = emptySet(),
        hiddenItemPatterns: List<String> = emptyList(),
    ): CatalogPlan {
        val issues = mutableListOf<CatalogBuildIssue>()
        val hiddenItemMatchers =
            hiddenItemPatterns.mapNotNull { compileMatcher(it, issues, "hidden-items") }
        val normalizedRegistry =
            registryIds
                .asSequence()
                .map(String::trim)
                .filter(::validNamespacedId)
                .filterNot { itemId -> hiddenItemMatchers.any { matcher -> matcher(itemId) } }
                .distinct()
                .sorted()
                .take(MAX_REGISTRY_ITEMS)
                .toList()
        if (registryIds.size > normalizedRegistry.size) {
            issues += CatalogBuildIssue("registry_items_skipped", (registryIds.size - normalizedRegistry.size).toString())
        }
        val registrySet = normalizedRegistry.toHashSet()

        val categories =
            rawCategories
                .filter(RawItemsAdderCategory::enabled)
                .filterNot { it.id in hiddenCategoryIds }
                .groupBy { it.id.lowercase(Locale.ROOT) }
                .mapNotNull { (id, definitions) ->
                    val patterns = definitions.flatMap(RawItemsAdderCategory::itemPatterns).distinct()
                    val itemIds =
                        if (definitions.any(RawItemsAdderCategory::showAllItems)) {
                            normalizedRegistry
                        } else {
                            expandPatterns(patterns, normalizedRegistry, registrySet, id, issues)
                        }
                    if (itemIds.isEmpty()) return@mapNotNull null

                    val names = definitions.mapNotNull(RawItemsAdderCategory::name).map(String::trim).filter(String::isNotEmpty)
                    val icons = definitions.mapNotNull(RawItemsAdderCategory::iconId).map(String::trim).filter(::validNamespacedId)
                    val permissions = definitions.mapNotNull(RawItemsAdderCategory::permission).map(String::trim).filter(String::isNotEmpty)
                    if (permissions.any { !validPermission(it) }) {
                        issues += CatalogBuildIssue("invalid_category_permission", id)
                        return@mapNotNull null
                    }
                    if (permissions.distinct().size > 1) {
                        issues += CatalogBuildIssue("category_permission_conflict", id)
                    }
                    CatalogCategory(
                        id = id,
                        displayName = ItemsCatalogText.categoryName(names.firstOrNull(), id),
                        iconId = icons.firstOrNull { it in registrySet } ?: itemIds.firstOrNull(),
                        permissions = permissions.toSet(),
                        itemIds = itemIds,
                    )
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CatalogCategory::displayName).thenBy(CatalogCategory::id))

        val assignments = linkedMapOf<String, MutableList<CatalogCategory>>()
        groupDefinitions.forEach { assignments[it.id] = mutableListOf() }
        val ungrouped = mutableListOf<CatalogCategory>()
        for (category in categories) {
            val matched = groupDefinitions.filter { definition ->
                definition.categoryPatterns.any { pattern -> matchesPattern(pattern, category.id, issues, "group:${definition.id}") }
            }
            if (matched.size > 1) {
                issues += CatalogBuildIssue("category_multiple_groups", category.id)
            }
            val target = matched.minWithOrNull(compareBy<CatalogGroupDefinition> { it.order }.thenBy { it.id })
            if (target == null) {
                ungrouped += category
            } else {
                assignments.getValue(target.id) += category
            }
        }

        val groups =
            groupDefinitions
                .sortedWith(compareBy<CatalogGroupDefinition> { it.order }.thenBy { it.id })
                .mapNotNull { definition ->
                    assignments.getValue(definition.id).takeIf(List<CatalogCategory>::isNotEmpty)?.let {
                        CatalogGroup(definition, it.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CatalogCategory::displayName)))
                    }
                }

        return CatalogPlan(
            ItemsCatalogSnapshot(
                registryItemIds = normalizedRegistry,
                groups = groups,
                ungroupedCategories = ungrouped,
                issues = issues.distinct(),
            ),
        )
    }

    internal fun matchesPattern(
        rawPattern: String,
        value: String,
        issues: MutableList<CatalogBuildIssue> = mutableListOf(),
        context: String = "pattern",
    ): Boolean {
        return compileMatcher(rawPattern, issues, context)?.invoke(value) ?: false
    }

    private fun compileMatcher(
        rawPattern: String,
        issues: MutableList<CatalogBuildIssue>,
        context: String,
    ): ((String) -> Boolean)? {
        val pattern = rawPattern.trim()
        if (pattern.isEmpty() || pattern.length > MAX_PATTERN_LENGTH) {
            issues += CatalogBuildIssue("invalid_pattern", context)
            return null
        }
        return try {
            when {
                pattern.any { it in "\\()[]{}+?|^$" } -> {
                    if (unsafeRegex(pattern)) {
                        issues += CatalogBuildIssue("unsafe_pattern", context)
                        null
                    } else {
                        Regex(pattern)::matches
                    }
                }
                '*' in pattern -> {
                    val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }
                    Regex("^$regex$", RegexOption.IGNORE_CASE)::matches
                }
                else -> { candidate: String -> pattern.equals(candidate, ignoreCase = true) }
            }
        } catch (_: IllegalArgumentException) {
            issues += CatalogBuildIssue("invalid_pattern", context)
            null
        }
    }

    private fun unsafeRegex(pattern: String): Boolean {
        if (Regex("\\\\[1-9]").containsMatchIn(pattern)) return true
        if ("(?" in pattern) return true
        return Regex("\\)(?:[+*?]|\\{)").containsMatchIn(pattern)
    }

    private fun expandPatterns(
        patterns: List<String>,
        registry: List<String>,
        registrySet: Set<String>,
        categoryId: String,
        issues: MutableList<CatalogBuildIssue>,
    ): List<String> {
        val result = linkedSetOf<String>()
        for (rawPattern in patterns) {
            val pattern = rawPattern.trim()
            if (validNamespacedId(pattern) && '*' !in pattern) {
                if (pattern in registrySet) result += pattern
                continue
            }
            val matcher = compileMatcher(pattern, issues, "category:$categoryId") ?: continue
            registry.filterTo(result, matcher)
        }
        return result.sorted()
    }

    private fun validNamespacedId(value: String): Boolean =
        value.length in 3..160 &&
            value.count { it == ':' } == 1 &&
            value.all { it.isLetterOrDigit() || it in "_-. /:" } &&
            ' ' !in value

    private fun validPermission(value: String): Boolean =
        value.length in 1..160 && value.all { it.isLetterOrDigit() || it in "._-*" }
}

object ItemsCatalogText {
    private val legacyCode = Regex("(?i)[&§][0-9A-FK-ORX]")
    private val control = Regex("[\\p{Cc}&&[^\\n\\t]]")
    private val whitespace = Regex("\\s+")

    fun categoryName(rawName: String?, id: String): String {
        val candidate =
            rawName
                ?.let { legacyCode.replace(it, "") }
                ?.let { control.replace(it, "") }
                ?.let { whitespace.replace(it, " ").trim() }
                ?.takeIf(String::isNotEmpty)
                ?.takeUnless { it.startsWith("display-", ignoreCase = true) }
                ?: humanize(id)
        return takeCodePoints(candidate, 48)
    }

    private fun humanize(id: String): String {
        val normalized = id.replace('_', ' ').replace('-', ' ').trim()
        return normalized.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("ru")) else it.toString() }
    }

    private fun takeCodePoints(value: String, maxCodePoints: Long): String {
        val points = value.codePoints().limit(maxCodePoints).toArray()
        return String(points, 0, points.size)
    }
}
