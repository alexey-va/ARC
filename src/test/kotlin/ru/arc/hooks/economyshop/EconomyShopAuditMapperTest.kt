package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.audit.EconomyEventStatus
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomySource

class EconomyShopAuditMapperTest : FreeSpec({
    "maps a successful purchase to a Vault burn" {
        EconomyShopAuditMapper.map("BUY_SCREEN", "SUCCESS", 25.0) shouldBe
            EconomyShopAuditMapping(EconomySource.SHOP, EconomyEventStatus.SUCCEEDED, EconomyFlow.BURN, -25.0)
    }

    "maps an automated sale to a distinct mint source" {
        EconomyShopAuditMapper.map("AUTO_SELL_CHEST", "SUCCESS_COMMANDS_EXECUTED", 50.0) shouldBe
            EconomyShopAuditMapping(EconomySource.AUTOSELL, EconomyEventStatus.SUCCEEDED, EconomyFlow.MINT, 50.0)
    }

    "keeps cancellation and failure separate without inventing a currency delta" {
        EconomyShopAuditMapper.map("BUY_SCREEN", "TRANSACTION_CANCELLED", null) shouldBe
            EconomyShopAuditMapping(EconomySource.SHOP, EconomyEventStatus.CANCELLED, EconomyFlow.UNKNOWN, null)
        EconomyShopAuditMapper.map("SELL_SCREEN", "INSUFFICIENT_FUNDS", 10.0) shouldBe
            EconomyShopAuditMapping(EconomySource.SHOP, EconomyEventStatus.FAILED, EconomyFlow.MINT, 10.0)
    }
})
