package ru.arc.hooks.economyshop

import org.bukkit.GameMode

internal enum class FurnitureShopMenuAccessDecision {
    ALLOWED,
    NO_PERMISSION,
    REQUIREMENTS_NOT_MET,
}

/** Mirrors ESG's banned-game-mode setting and per-player bypass permission. */
internal fun furnitureShopGameModeAllowed(
    gameMode: GameMode,
    bannedGameModes: Collection<GameMode>,
    hasBypassPermission: Boolean,
): Boolean = gameMode !in bannedGameModes || hasBypassPermission

/** Mirrors the normal ESG shop click gate and evaluates requirements only after access passes. */
internal fun furnitureShopMenuAccess(
    hasGlobalShopPermission: Boolean,
    allowedGameMode: Boolean,
    itemCanPurchase: Boolean,
    requirementsMet: () -> Boolean,
): FurnitureShopMenuAccessDecision {
    if (!hasGlobalShopPermission || !allowedGameMode || !itemCanPurchase) {
        return FurnitureShopMenuAccessDecision.NO_PERMISSION
    }
    return if (requirementsMet()) {
        FurnitureShopMenuAccessDecision.ALLOWED
    } else {
        FurnitureShopMenuAccessDecision.REQUIREMENTS_NOT_MET
    }
}
