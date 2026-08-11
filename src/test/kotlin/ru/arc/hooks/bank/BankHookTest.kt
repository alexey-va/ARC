package ru.arc.hooks.bank

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BankHookTest :
    StringSpec({
        "uses the UUID string for offline Bank account reads" {
            val playerId = "f56ad52c-0adf-3672-9d47-2b0f3dbedaef"
            val identifiers = mutableListOf<String>()
            val hook =
                BankHook { identifier ->
                    identifiers += identifier
                    BankHook.Account(balance = 125.5, pendingInterest = 4.25)
                }

            hook.account(playerId, "CachedName") shouldBe BankHook.Account(balance = 125.5, pendingInterest = 4.25)
            identifiers shouldBe listOf(playerId)
        }
    })
