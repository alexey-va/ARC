package ru.arc.itemcatalog

/** A bounded, operator-selected source for one catalogue reward. */
sealed interface RewardCatalogSource {
    data class Treasure(val pool: String, val id: String) : RewardCatalogSource

    data class Preset(val id: String) : RewardCatalogSource

    data class Pouch(val id: String) : RewardCatalogSource
}

data class RewardCatalogEntry(
    val id: String,
    val name: String?,
    val description: List<String>,
    val rarity: String?,
    val requires: List<String>,
    val source: RewardCatalogSource,
    val icon: CatalogIconStyle?,
)

data class RewardCatalogCategory(
    val id: String,
    val name: String,
    val description: List<String>,
    val icon: CatalogIconStyle,
    val entries: List<RewardCatalogEntry>,
)

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
) {
    val entryCount: Int = categories.sumOf { it.entries.size }
}

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
