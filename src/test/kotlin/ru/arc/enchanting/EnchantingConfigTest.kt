package ru.arc.enchanting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import java.nio.file.Files

class EnchantingConfigTest : StringSpec({
    "upgrade adds the shop and loot without replacing operator book copy and is idempotent" {
        val root = Files.createTempDirectory("enchanting-upgrade")
        try {
            val file = root.resolve("modules/enchanting.yml")
            Files.createDirectories(file.parent)
            Files.writeString(file, "book:\n  name-prefix: 'Своя книга'\noperator-note: 'keep'\n")
            ConfigManager.clear()
            val loaded = EnchantingConfig.load(root)
            loaded.bookText.namePrefix shouldBe "Своя книга"
            loaded.shopPriceMinor("SIMPLE", 2) shouldBe 600_000L
            val first = Files.readString(file)
            ConfigManager.ofModule(root, "enchanting.yml").string("operator-note") shouldBe "keep"
            ConfigManager.clear()
            EnchantingConfig.load(root)
            Files.readString(file) shouldBe first
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }

    "all public shop groups use minor units and reject nonpublic or invalid quotes" {
        val root = Files.createTempDirectory("enchanting-prices")
        try {
            ConfigManager.clear()
            val loaded = EnchantingConfig.load(root)
            val prices = mapOf("SIMPLE" to 3000L, "UNIQUE" to 6000L, "ELITE" to 18750L,
                "ULTIMATE" to 37500L, "LEGENDARY" to 60000L, "FABLED" to 150000L)
            prices.forEach { (group, price) ->
                loaded.shopPriceMinor(group, 1) shouldBe price * 100
                loaded.shopPriceMinor(group, 4) shouldBe price * 400
            }
            loaded.shopPriceMinor("CHEATER", 1) shouldBe null
            loaded.shopPriceMinor("SIMPLE", 0) shouldBe null
            val config = ConfigManager.ofModule(root, "enchanting.yml")
            config.setLong("shop.base-prices.SIMPLE", Long.MAX_VALUE)
            loaded.shopPriceMinor("SIMPLE", 2) shouldBe null
            config.setLong("shop.base-prices.SIMPLE", -1)
            loaded.shopPriceMinor("SIMPLE", 1) shouldBe null
        } finally {
            ConfigManager.clear()
            root.toFile().deleteRecursively()
        }
    }
})
