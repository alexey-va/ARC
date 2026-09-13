package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.worldcontent.BreweryTableDialogs

class OriginDiningLayoutTest : FreeSpec({
    "every authored chair has a unique block and table anchor" {
        OriginDiningLayout.seats.size shouldBe 8
        OriginDiningLayout.seats.map { it.clickedBlock }.distinct().size shouldBe 8
        OriginDiningLayout.seats.map { it.id }.distinct().size shouldBe 8
    }

    "brewery exposes several tables and only the fire seat opens drinks" {
        OriginDiningLayout.seats.count { it.id.startsWith("brewery_") } shouldBe 6
        OriginDiningLayout.seats.single { it.id == "brewery_fire" }.menu shouldBe BreweryTableDialogs.Menu.DRINKS
        OriginDiningLayout.seats.single { it.id == "brewery_rina" }.menu shouldBe BreweryTableDialogs.Menu.COURTYARD
        OriginDiningLayout.seats.count { it.menu == BreweryTableDialogs.Menu.RESTAURANT } shouldBe 2
        OriginDiningLayout.waiterIds shouldBe setOf(410, 411, 431, 432)
    }

    "only the four restaurant service NPCs are managed as waiters" {
        OriginDiningLayout.waiterIds shouldBe setOf(410, 411, 431, 432)
    }

    "meal displays and their hitboxes are served exactly one block above authored anchors" {
        OriginDiningLayout.servingAnchorY(71.1) shouldBe 72.1
        OriginDiningLayout.servingAnchorY(73.1) shouldBe 74.1

        listOf("egg", "fish", "steak", "herbal_tea").forEach { dish ->
            val visualY = OriginDiningLayout.visibleModelY(OriginDiningLayout.servingAnchorY(71.1), dish)
            (visualY in 72.13..72.15) shouldBe true
        }
    }

    "every menu dish resolves to the production ItemsAdder item" {
        OriginDiningLayout.itemId("egg") shouldBe "elitecreatures:restaurant_food_eggbeacon"
        OriginDiningLayout.itemId("fish") shouldBe "elitecreatures:restaurant_food_halffish"
        OriginDiningLayout.itemId("steak") shouldBe "elitecreatures:restaurant_food_steak"
        listOf("herbal_tea", "berry_kvass", "spiced_mead").forEach { dish ->
            OriginDiningLayout.itemId(dish) shouldBe "elitecreatures:restaurant_drink"
        }
    }

    "small authored models and meal targeting are enlarged" {
        OriginDiningLayout.displayScale("steak") shouldBe 2.6f
        OriginDiningLayout.displayScale("egg") shouldBe 1.3f
        OriginDiningLayout.displayScale("herbal_tea") shouldBe 1.3f
        OriginDiningLayout.displayScale("fish") shouldBe 0.65f
        OriginDiningLayout.MEAL_HITBOX_SIZE shouldBe 1.8f
    }

    "chair block fallback resolves the real clicked stair" {
        OriginDiningLayout.seatForBlock(-8, 70, 37)?.id shouldBe "brewery_south_west"
        OriginDiningLayout.seatForBlock(-14, 70, 47)?.id shouldBe "brewery_rina"
        OriginDiningLayout.seatForBlock(-55, 72, 48)?.id shouldBe "restaurant_a"
        OriginDiningLayout.seatForBlock(-55, 72, 47) shouldBe null
    }
})
