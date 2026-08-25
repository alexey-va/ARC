package ru.arc.itemcatalog

import org.bukkit.Material
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension

data class CategoryScanResult(
    val categories: List<RawItemsAdderCategory>,
    val recipeResultItemIds: Set<String>,
    val issues: List<CatalogBuildIssue>,
    val scannedFiles: Int,
)

class ItemsAdderCategoryScanner(
    private val maxFiles: Int = 5_000,
    private val maxFileBytes: Long = 2L * 1024L * 1024L,
    private val maxTotalBytes: Long = 64L * 1024L * 1024L,
    private val maxCategories: Int = 5_000,
    private val maxPatternsPerCategory: Int = 5_000,
    private val maxTotalPatterns: Int = 10_000,
    private val maxRecipeResults: Int = 20_000,
) {
    fun scan(contentsRoot: Path): CategoryScanResult {
        if (!Files.isDirectory(contentsRoot)) {
            return CategoryScanResult(emptyList(), emptySet(), listOf(CatalogBuildIssue("contents_missing", "contents")), 0)
        }

        val files =
            Files.walk(contentsRoot).use { paths ->
                paths
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .filter { it.extension.equals("yml", ignoreCase = true) || it.extension.equals("yaml", ignoreCase = true) }
                    .sorted()
                    .limit(maxFiles.toLong() + 1L)
                    .toList()
            }
        val issues = mutableListOf<CatalogBuildIssue>()
        if (files.size > maxFiles) issues += CatalogBuildIssue("file_limit_reached", maxFiles.toString())

        val categories = mutableListOf<RawItemsAdderCategory>()
        val recipeResults = linkedSetOf<String>()
        var scannedFiles = 0
        var scannedBytes = 0L
        var acceptedPatterns = 0
        for (path in files.take(maxFiles)) {
            if (categories.size >= maxCategories) {
                issues += CatalogBuildIssue("category_limit_reached", maxCategories.toString())
                break
            }
            val size = runCatching { Files.size(path) }.getOrElse {
                issues += CatalogBuildIssue("file_unreadable", safeRelative(contentsRoot, path))
                continue
            }
            if (size > maxFileBytes) {
                issues += CatalogBuildIssue("file_too_large", safeRelative(contentsRoot, path))
                continue
            }
            if (scannedBytes + size > maxTotalBytes) {
                issues += CatalogBuildIssue("total_file_size_limit_reached", maxTotalBytes.toString())
                break
            }

            val yaml = YamlConfiguration()
            try {
                yaml.load(path.toFile())
            } catch (_: InvalidConfigurationException) {
                issues += CatalogBuildIssue("invalid_yaml", safeRelative(contentsRoot, path))
                continue
            } catch (_: IOException) {
                issues += CatalogBuildIssue("file_unreadable", safeRelative(contentsRoot, path))
                continue
            }
            scannedFiles++
            scannedBytes += size
            collectRecipeResults(yaml, recipeResults, issues)

            val section = yaml.getConfigurationSection("categories")
            if (section != null) {
                for (rawId in section.getKeys(false).sorted()) {
                    if (categories.size >= maxCategories) break
                    val id = rawId.trim().lowercase(Locale.ROOT)
                    if (!validCategoryId(id)) {
                        issues += CatalogBuildIssue("invalid_category_id", safeRelative(contentsRoot, path))
                        continue
                    }
                    val root = "categories.$rawId"
                    val rawPatterns = yaml.getList("$root.items").orEmpty().mapNotNull { it?.toString()?.trim() }.filter(String::isNotEmpty)
                    if (rawPatterns.size > maxPatternsPerCategory) {
                        issues += CatalogBuildIssue("category_pattern_limit_reached", id)
                    }
                    val remainingPatterns = (maxTotalPatterns - acceptedPatterns).coerceAtLeast(0)
                    val accepted = rawPatterns.take(minOf(maxPatternsPerCategory, remainingPatterns))
                    if (accepted.size < rawPatterns.size && remainingPatterns < maxPatternsPerCategory) {
                        issues += CatalogBuildIssue("total_pattern_limit_reached", maxTotalPatterns.toString())
                    }
                    acceptedPatterns += accepted.size
                    categories +=
                        RawItemsAdderCategory(
                            id = id,
                            enabled = yaml.getBoolean("$root.enabled", true),
                            name = yaml.getString("$root.name"),
                            iconId = yaml.getString("$root.icon")?.trim(),
                            permission = yaml.getString("$root.permission")?.trim(),
                            itemPatterns = accepted,
                            showAllItems = yaml.getBoolean("$root.show_all_items", false),
                            source = safeRelative(contentsRoot, path),
                        )
                }
            }
        }
        return CategoryScanResult(categories, recipeResults, issues.distinct(), scannedFiles)
    }

    private fun collectRecipeResults(
        yaml: YamlConfiguration,
        recipeResults: MutableSet<String>,
        issues: MutableList<CatalogBuildIssue>,
    ) {
        if (recipeResults.size >= maxRecipeResults) return
        val namespace = yaml.getString("info.namespace")?.trim()?.lowercase(Locale.ROOT)?.takeIf(::validNamespace)
        val recipes = yaml.getConfigurationSection("recipes") ?: return
        recipeTypes@ for (typeId in recipes.getKeys(false).sorted()) {
            val type = recipes.getConfigurationSection(typeId) ?: continue
            for (recipeId in type.getKeys(false).sorted()) {
                if (recipeResults.size >= maxRecipeResults) {
                    issues += CatalogBuildIssue("recipe_result_limit_reached", maxRecipeResults.toString())
                    break@recipeTypes
                }
                val recipe = type.getConfigurationSection(recipeId) ?: continue
                if (!recipe.getBoolean("enabled", true)) continue
                val rawResult = recipe.getString("result.item")?.trim()?.takeIf(String::isNotEmpty) ?: continue
                normalizeRecipeResult(rawResult, namespace)?.let(recipeResults::add)
            }
        }
    }

    private fun normalizeRecipeResult(rawResult: String, namespace: String?): String? {
        val lowered = rawResult.lowercase(Locale.ROOT)
        val namespaced =
            when {
                ':' in lowered -> lowered
                Material.matchMaterial(rawResult) != null -> return null
                namespace != null -> "$namespace:$lowered"
                else -> return null
            }
        return namespaced.takeIf(::validNamespacedId)
    }

    private fun safeRelative(root: Path, path: Path): String =
        runCatching { root.relativize(path).toString().take(240) }.getOrDefault("unknown")

    private fun validCategoryId(id: String): Boolean =
        id.length in 1..96 && id.all { it.isLetterOrDigit() || it in "_-" }

    private fun validNamespace(value: String): Boolean =
        value.length in 1..64 && value.all { it.isLetterOrDigit() || it in "_.-" }

    private fun validNamespacedId(value: String): Boolean =
        value.length in 3..160 &&
            value.count { it == ':' } == 1 &&
            value.all { it.isLetterOrDigit() || it in "_-./:" }
}
