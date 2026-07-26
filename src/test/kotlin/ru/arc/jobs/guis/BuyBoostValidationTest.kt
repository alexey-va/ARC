package ru.arc.jobs.guis

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.jobs.BoostType

class BuyBoostValidationTest :
    FreeSpec({
        fun boost(
            price: Double = 100.0,
            amount: Double = 0.5,
            seconds: Long = 60,
            id: String = "valid",
        ) = BuyBoostGuiFactory.Boost(
            display = "Boost",
            lore = emptyList(),
            price = price,
            boostAmount = amount,
            seconds = seconds,
            permission = "",
            material = Material.GOLD_INGOT,
            modelData = 0,
            currency = BuyBoostGuiFactory.BuyCurrency.MONEY,
            id = id,
            jobs = listOf("all"),
            types = listOf(BoostType.MONEY),
        )

        "valid boost config is accepted" {
            boost().isValid().shouldBeTrue()
        }

        "negative and non-finite prices are rejected" {
            boost(price = -1.0).isValid().shouldBeFalse()
            boost(price = Double.NaN).isValid().shouldBeFalse()
            boost(price = Double.POSITIVE_INFINITY).isValid().shouldBeFalse()
        }

        "invalid boost amount duration and id are rejected" {
            boost(amount = 0.0).isValid().shouldBeFalse()
            boost(amount = Double.NaN).isValid().shouldBeFalse()
            boost(seconds = 0).isValid().shouldBeFalse()
            boost(seconds = Long.MAX_VALUE).isValid().shouldBeFalse()
            boost(id = "").isValid().shouldBeFalse()
            boost(id = "none").isValid().shouldBeFalse()
            boost(id = " NONE ").isValid().shouldBeFalse()
        }

        "economy check reports the actual missing amount" {
            val check = calculateEconomyCheck(balance = 100.0, price = 145.5)

            check.hasEnough.shouldBeFalse()
            check.currencyNeeded.shouldBeExactly(45.5)
        }

        "economy check rejects invalid monetary values" {
            calculateEconomyCheck(balance = 100.0, price = -5.0).hasEnough.shouldBeFalse()
            calculateEconomyCheck(balance = Double.NaN, price = 5.0).hasEnough.shouldBeFalse()
        }

        "experience prices must be whole values in the supported range" {
            boost(price = 10.0)
                .copy(currency = BuyBoostGuiFactory.BuyCurrency.EXP)
                .isValid()
                .shouldBeTrue()
            boost(price = 10.5)
                .copy(currency = BuyBoostGuiFactory.BuyCurrency.EXP)
                .isValid()
                .shouldBeFalse()
            boost(price = Int.MAX_VALUE.toDouble() + 1.0)
                .copy(currency = BuyBoostGuiFactory.BuyCurrency.EXP)
                .isValid()
                .shouldBeFalse()
        }

        "expiration calculation rejects overflow" {
            calculateBoostExpiration(now = 1_000L, seconds = 60) shouldBe 61_000L
            calculateBoostExpiration(now = Long.MAX_VALUE, seconds = 1) shouldBe null
            calculateBoostExpiration(now = 0L, seconds = 0) shouldBe null
        }
    })
