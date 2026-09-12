package ru.arc.hooks.economyshop

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration

class FurnitureShopDialogControllerTest :
    FreeSpec({
        "catalog pages keep twelve offers and clamp the requested page" {
            val offers = (1..25).map { offer(it.toString(), it.toDouble()) }

            val page = FurnitureShopDialogController.pageOf(offers, requestedPage = 99)

            page.page shouldBe 2
            page.pages shouldBe 3
            page.entries.map(FurnitureShopOffer::itemPath) shouldBe listOf("25")
        }

        "confirmation rejects a changed live price or SKU" {
            val quoted = offer("Furniture.pages.page1.items.2", 10_000.0)

            FurnitureShopDialogController.sameQuote(quoted, offer(quoted.itemPath, 10_000.0)) shouldBe true
            FurnitureShopDialogController.sameQuote(quoted, offer(quoted.itemPath, 20_000.0)) shouldBe false
            FurnitureShopDialogController.sameQuote(quoted, offer("Furniture.pages.page1.items.3", 10_000.0)) shouldBe false
        }

        "renders a native readable price instead of provider legacy formatting" {
            val quoted = FurnitureShopOffer(
                itemPath = "Furniture.pages.page1.items.2",
                furnitureId = "elitecreatures:furniture_table",
                category = "Мебель",
                displayName = "Мебель",
                amount = 1,
                totalPrice = 12_345.67,
                formattedPrice = "12 345,67§f������",
            )
            val rendered = FurnitureShopDialogController.formatPrice(quoted.totalPrice)

            rendered shouldBe "12345.67 монет"
            rendered.contains('§') shouldBe false
            rendered.contains('�') shouldBe false
        }

        "furniture detection is scoped to the exact item in a shared config" {
            val config = YamlConfiguration()
            config.loadFromString(
                """
                items:
                  chair:
                    behaviours:
                      furniture:
                        solid: true
                  sword:
                    resource:
                      material: DIAMOND_SWORD
                """.trimIndent(),
            )

            hasFurnitureBehaviour(config, "chair") shouldBe true
            hasFurnitureBehaviour(config, "sword") shouldBe false
            hasFurnitureBehaviour(config, "missing") shouldBe false
        }
    }) {
    companion object {
        private fun offer(path: String, price: Double) =
            FurnitureShopOffer(path, "elitecreatures:furniture_$path", "Мебель", "Мебель", 1, price, price.toString())
    }
}
