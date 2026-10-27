package ru.arc.itemcatalog

import java.math.BigDecimal
import java.math.RoundingMode

/** A bounded, operator-selected source for one catalogue reward. */
sealed interface RewardCatalogSource {
    data class Treasure(val pool: String, val id: String) : RewardCatalogSource

    data class Preset(val id: String) : RewardCatalogSource

    /** A transferable certificate that permanently unlocks one named particle preset. */
    data class ParticlePreset(val id: String) : RewardCatalogSource

    /** A transferable certificate for a fixed number of VeinMiner days on Survival. */
    data class VeinMinerAccess(val days: Int) : RewardCatalogSource {
        init {
            require(days in VeinMinerAccessEntitlement.DURATIONS) { "Unsupported VeinMiner duration: $days" }
        }
    }

    data class Pouch(val id: String) : RewardCatalogSource

    data class Seal(val categoryId: String) : RewardCatalogSource

    data class ItemsAdder(val id: String) : RewardCatalogSource

    /** Authored future entitlement; rendering must never turn this into a grantable item. */
    data class Planned(val id: String) : RewardCatalogSource

    /** Display-only outcome: the native crate engine owns key issuance and persistence. */
    data class NativeCrateKey(val id: String) : RewardCatalogSource

    data class Mount(val id: String) : RewardCatalogSource

    data class FurniturePackage(val id: String) : RewardCatalogSource

    /** A transferable voucher that rolls the named EliteMobs dungeon case when redeemed. */
    data class DungeonCase(val id: String) : RewardCatalogSource

    /** A transferable voucher that binds personal travel anchors to its redeemer. */
    data class TravelAnchors(val amount: Int) : RewardCatalogSource

    /** A physical bearer voucher that offers three stable choices from a frozen pool. */
    data class Choice(val options: List<RewardCatalogChoiceRef>) : RewardCatalogSource

    /** An owner-bound map whose frozen prize is claimed at a safe Survival location discovered on activation. */
    data class PersonalMap(
        val rewardCategoryId: String,
        val rewardEntryId: String,
        val searchPolicy: PersonalTreasureMapSearchPolicy,
        val prizeRolls: Int = 1,
        /** Retained only while reading older authored configs; new archives store the dynamic policy. */
        val legacyDestinations: List<PersonalTreasureMapDestination> = emptyList(),
    ) : RewardCatalogSource
}

/** Stable reference within one configured choice pool. */
data class RewardCatalogChoiceRef(val id: String, val categoryId: String, val entryId: String)

/** Fixed grantable PlayerParticles entitlements; never accept an arbitrary permission suffix. */
internal object ParticlePresetEntitlements {
    const val PERMISSION_PREFIX = "arc.cosmetics.particles."
    val IDS = setOf("arc_raincloud", "arc_angel", "arc_rainbow")

    fun permission(id: String): String {
        require(id in IDS) { "Unsupported particle preset: $id" }
        return PERMISSION_PREFIX + id
    }
}

/** The only permission and durations a VeinMiner certificate may grant. */
internal object VeinMinerAccessEntitlement {
    const val PERMISSION = "veinminer.veinmine.*"
    const val SERVER = "survival"
    val DURATIONS = setOf(1, 3, 7)
}

data class RewardFurniturePackage(
    val name: String,
    val items: List<String>,
    /** Missing weights give every listed ItemsAdder id the same chance. */
    val weights: Map<String, Int> = emptyMap(),
    val minRolls: Int = DEFAULT_MIN_ROLLS,
    val maxRolls: Int = DEFAULT_MAX_ROLLS,
) {
    init {
        require(items.size in 1..216 && items.distinct().size == items.size) {
            "Furniture package items must be unique and contain 1..216 ids"
        }
        require(weights.keys.all { it in items } && weights.values.all { it in 1..MAX_ITEM_WEIGHT }) {
            "Furniture package weights must reference listed items and be positive"
        }
        require(minRolls in MIN_ROLLS..MAX_ROLLS && maxRolls in minRolls..MAX_ROLLS) {
            "Furniture package rolls must be in $MIN_ROLLS..$MAX_ROLLS"
        }
    }

    fun weightFor(itemId: String): Int = weights[itemId] ?: 1

    companion object {
        const val MIN_ROLLS = 8
        const val MAX_ROLLS = 12
        const val DEFAULT_MIN_ROLLS = 8
        const val DEFAULT_MAX_ROLLS = 12
        const val MAX_ITEM_WEIGHT = 1_000_000
    }
}

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
    /** Optional resource-pack tooltip frame; never replaces native reward metadata. */
    val tooltipStyle: String? = null,
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
    /** Entries with a selectable weight; raw entries remain addressable for queued case rewards. */
    val selectableCaseEntries: List<RewardCatalogEntry>
        get() = if (rolls == 1) entries.filter { (it.weight ?: 0) > 0 } else emptyList()

    /** Entries players may see in this category; retired case outcomes stay out of the catalogue. */
    val visibleEntries: List<RewardCatalogEntry>
        get() = if (rolls == 1) selectableCaseEntries else entries

    /** One weighted outcome per opening; the same configured weights drive the displayed odds. */
    fun chance(entry: RewardCatalogEntry): String? {
        if (rolls != 1 || entry !in selectableCaseEntries || entry.weight == null) return null
        val total = selectableCaseEntries.sumOf { it.weight?.toLong() ?: 0L }
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
    val visibleEntryCount: Int = categories.sumOf { it.visibleEntries.size }

    fun children(parentId: String?): List<RewardCatalogCategory> = categories.filter { it.parentId == parentId }

    /** Finds one concrete outcome in a one-roll case. Non-case catalogue groups are never issuable here. */
    fun caseEntry(categoryId: String, entryId: String): Pair<RewardCatalogCategory, RewardCatalogEntry>? {
        val category = categories.firstOrNull { it.id == categoryId && it.rolls == 1 } ?: return null
        val entry = category.entries.firstOrNull { it.id == entryId } ?: return null
        return category to entry
    }

    fun entry(categoryId: String, entryId: String): Pair<RewardCatalogCategory, RewardCatalogEntry>? {
        val category = categories.firstOrNull { it.id == categoryId } ?: return null
        val entry = category.entries.firstOrNull { it.id == entryId } ?: return null
        return category to entry
    }

    fun uncoveredRewards(): List<String> {
        val included = categories.filter { it.rolls != null }.flatMap { it.selectableCaseEntries }
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
