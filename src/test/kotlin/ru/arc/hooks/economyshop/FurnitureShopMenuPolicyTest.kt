package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FurnitureShopMenuPolicyTest : StringSpec({
    "requires global shop, allowed gamemode, and item permission before checking requirements" {
        var requirementsChecked = false
        furnitureShopMenuAccess(
            hasGlobalShopPermission = false,
            allowedGameMode = true,
            itemCanPurchase = true,
            requirementsMet = { requirementsChecked = true; true },
        ) shouldBe FurnitureShopMenuAccessDecision.NO_PERMISSION
        requirementsChecked shouldBe false

        furnitureShopMenuAccess(
            hasGlobalShopPermission = true,
            allowedGameMode = false,
            itemCanPurchase = true,
            requirementsMet = { requirementsChecked = true; true },
        ) shouldBe FurnitureShopMenuAccessDecision.NO_PERMISSION
        requirementsChecked shouldBe false

        furnitureShopMenuAccess(
            hasGlobalShopPermission = true,
            allowedGameMode = true,
            itemCanPurchase = false,
            requirementsMet = { requirementsChecked = true; true },
        ) shouldBe FurnitureShopMenuAccessDecision.NO_PERMISSION
        requirementsChecked shouldBe false
    }

    "opens only when the native shop requirement also passes" {
        furnitureShopMenuAccess(true, true, true) { true } shouldBe FurnitureShopMenuAccessDecision.ALLOWED
        furnitureShopMenuAccess(true, true, true) { false } shouldBe
            FurnitureShopMenuAccessDecision.REQUIREMENTS_NOT_MET
    }
})
