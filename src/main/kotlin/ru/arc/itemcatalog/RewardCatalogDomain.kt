package ru.arc.itemcatalog

import java.math.BigDecimal
import java.math.RoundingMode

/** A bounded, operator-selected source for one catalogue reward. */
sealed interface RewardCatalogSource {
    data class Treasure(val pool: String, val id: String) : RewardCatalogSource

    data class Preset(val id: String) : RewardCatalogSource

    data class Pouch(val id: String) : RewardCatalogSource

    data class Seal(val categoryId: String) : RewardCatalogSource

    data class ItemsAdder(val id: String) : RewardCatalogSource

    /** Authored future entitlement; rendering must never turn this into a grantable item. */
    data class Planned(val id: String) : RewardCatalogSource

    data class Mount(val id: String) : RewardCatalogSource

    data class FurniturePackage(val id: String) : RewardCatalogSource
}

data class RewardFurniturePackage(val name: String, val items: List<String>)

data class RewardCatalogEntry(
    val id: String,
    val name: String?,
    val description: List<String>,
    val rarity: String?,
    val requires: List<String>,
    val source: RewardCatalogSource,
    val icon: CatalogIconStyle?,
    val weight: Int? = null,
    val enchantments: Map<String, Int> = emptyMap(),
    /** Optional ItemsAdder stack used only for catalogue presentation. */
    val previewItemsAdder: String? = null,
)

data class RewardCatalogCategory(
    val id: String,
    val name: String,
    val description: List<String>,
    val icon: CatalogIconStyle,
    val entries: List<RewardCatalogEntry>,
    val parentId: String? = null,
    val rolls: Int? = null,
) {
    /** One weighted outcome per opening; the same configured weights drive the displayed odds. */
    fun chance(entry: RewardCatalogEntry): String? {
        if (rolls == null || entry.weight == null) return null
        val total = entries.sumOf { it.weight?.toLong() ?: 0L }
        if (total <= 0) return null
        return BigDecimal.valueOf(entry.weight.toLong()).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString().replace('.', ',') + "%"
    }
}

data class RewardCatalogMessages(
    val unavailable: String,
    val inventoryFull: String,
    val given: String,
    val accepted: String,
    val actionFailed: String,
) {
    companion object {
        val DEFAULT = RewardCatalogMessages(
            unavailable = "<gray>Эта награда сейчас недоступна.",
            inventoryFull = "<red>В инвентаре нет места для награды.",
            given = "<#92bed8>Награда добавлена в инвентарь.",
            accepted = "<#92bed8>Награда принята к выполнению.",
            actionFailed = "<red>Награда сейчас недоступна.",
        )
    }
}

data class RewardCatalogSettings(
    val enabled: Boolean,
    val title: String,
    val categories: List<RewardCatalogCategory>,
    val messages: RewardCatalogMessages,
    val rootIcon: CatalogIconStyle = CatalogIconStyle("CHEST"),
    val packages: Map<String, RewardFurniturePackage> = emptyMap(),
    val requireCaseCoverage: Boolean = false,
) {
    val entryCount: Int = categories.sumOf { it.entries.size }

    fun children(parentId: String?): List<RewardCatalogCategory> = categories.filter { it.parentId == parentId }

    /** Finds one concrete outcome in a one-roll case. Non-case catalogue groups are never issuable here. */
    fun caseEntry(categoryId: String, entryId: String): Pair<RewardCatalogCategory, RewardCatalogEntry>? {
        val category = categories.firstOrNull { it.id == categoryId && it.rolls == 1 } ?: return null
        val entry = category.entries.firstOrNull { it.id == entryId } ?: return null
        return category to entry
    }

    fun uncoveredRewards(): List<String> {
        val included = categories.filter { it.rolls != null }.flatMap { it.entries }
            .map { it.source to it.enchantments }.toSet()
        return categories.filter { it.rolls == null }.flatMap { category ->
            category.entries.filter { it.source !is RewardCatalogSource.Planned && (it.source to it.enchantments) !in included }
                .map { "${category.id}/${it.id}" }
        }
    }
}

/** Provider identity captured before a voucher is minted for a delivery. */
internal data class PhysicalRewardMaterialization(
    val sourceKey: String,
    val providerFingerprint: String,
)

/** Resolves current reward state only after current access and provider checks pass. */
internal object RewardCatalogClickGuard {
    fun <T> resolveForGrant(
        active: Boolean,
        hasPermission: () -> Boolean,
        providersEnabled: () -> Boolean,
        resolve: () -> T?,
    ): T? {
        if (!active || !hasPermission() || !providersEnabled()) return null
        return resolve()
    }
}
