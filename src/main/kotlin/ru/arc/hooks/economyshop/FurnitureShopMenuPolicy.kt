package ru.arc.hooks.economyshop

internal enum class FurnitureShopMenuAccessDecision {
    ALLOWED,
    NO_PERMISSION,
    REQUIREMENTS_NOT_MET,
}

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
