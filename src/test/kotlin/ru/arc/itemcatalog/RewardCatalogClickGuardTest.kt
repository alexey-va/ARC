package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RewardCatalogClickGuardTest : StringSpec({
    "stale menus do not query permissions providers or reward factories" {
        RewardCatalogClickGuard.resolveForGrant<String>(
            active = false,
            hasPermission = { error("stale menu queried access") },
            providersEnabled = { error("stale menu queried providers") },
            resolve = { error("stale menu created a reward") },
        ) shouldBe null
    }

    "revoked access and disabled providers never resolve a reward" {
        for ((permission, provider) in listOf(false to true, true to false)) {
            RewardCatalogClickGuard.resolveForGrant<String>(
                active = true,
                hasPermission = { permission },
                providersEnabled = { provider },
                resolve = { error("denied click created a reward") },
            ) shouldBe null
        }
    }

    "each accepted click resolves current source and observes removal" {
        var current: String? = "first revision"
        var resolutions = 0
        fun click() = RewardCatalogClickGuard.resolveForGrant(
            active = true,
            hasPermission = { true },
            providersEnabled = { true },
            resolve = { resolutions++; current },
        )
        click() shouldBe "first revision"
        current = "replacement revision"
        click() shouldBe "replacement revision"
        current = null
        click() shouldBe null
        resolutions shouldBe 3
    }
})
