package ru.arc.contracts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ContractsConfigTest : StringSpec({
    "loads exact decimal money and explicit resource-order windows" {
        val root = Files.createTempDirectory("arc-contract-config")
        val modules = root.resolve("modules")
        Files.createDirectories(modules)
        Files.writeString(
            modules.resolve("contracts.yml"),
            """
            enabled: true
            mode: observe
            leader-server: spawn
            server-weekly-budget: "250000.00"
            orders:
              road_stone:
                enabled: true
                kind: resource
                display-name: "Камень для тракта"
                item: STONE
                funding: server_envelope
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "2.50"
                budget: "50000.00"
                target-quantity: 20000
                per-player-quantity-cap: 4000
                min-submission-quantity: 16
                max-submission-quantity: 2304
            """.trimIndent(),
        )

        val config = ContractsConfig.fromFile(root)
        config.enabled shouldBe true
        config.mode shouldBe ContractsMode.OBSERVE
        config.serverWeeklyBudgetMinor shouldBe 25_000_000L
        config.validated() shouldBe config
        val order = config.resourceOrders().shouldHaveSize(1).single()
        order.itemKey shouldBe "minecraft:stone"
        order.payoutMinorPerUnit shouldBe 250L
        order.budgetMinor shouldBe 5_000_000L
        order.targetQuantity shouldBe 20_000L
    }

    "rejects aggregate order budgets above the network envelope" {
        val root = Files.createTempDirectory("arc-contract-config")
        val modules = root.resolve("modules")
        Files.createDirectories(modules)
        Files.writeString(
            modules.resolve("contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "10.00"
            orders:
              road_stone:
                enabled: true
                item: minecraft:stone
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "1.00"
                budget: "10.01"
                target-quantity: 100
                per-player-quantity-cap: 20
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Configured contract budgets 1001 exceed server weekly envelope 1000 minor units"
    }

    "rejects fractional money below one minor unit" {
        shouldThrow<IllegalArgumentException> {
            ContractsConfig.moneyMinor("0.001", "payout")
        }.message shouldBe "Contract money 'payout' must have at most two decimals"
    }

    "rejects non-normalized contract ids before disabled entries can hide them" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "0.00"
            orders:
              Road_Stone:
                enabled: false
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Contract id 'Road_Stone' must already be normalized lowercase ASCII"
    }

    "rejects a resource order without a concrete namespaced item" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: observe
            server-weekly-budget: "100.00"
            orders:
              road_stone:
                enabled: true
                item: ""
                window-starts-at: "2026-08-18T00:00:00Z"
                window-ends-at: "2026-08-25T00:00:00Z"
                payout-per-unit: "1.00"
                budget: "100.00"
                target-quantity: 100
                per-player-quantity-cap: 20
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).resourceOrders()
        }.message shouldBe "Invalid namespaced contract itemKey: "
    }

    "rejects misspelled policy modes instead of silently falling back" {
        val root = Files.createTempDirectory("arc-contract-config")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/contracts.yml"),
            """
            enabled: true
            mode: enfore
            server-weekly-budget: "0.00"
            orders: {}
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> {
            ContractsConfig.fromFile(root).validated()
        }.message shouldBe "Contract enum 'mode' must be one of disabled, observe, enforce"
    }
})
