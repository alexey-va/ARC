package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.worldcontent.BreweryTableDialogs
import kotlin.math.abs

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

    "every dish is centered one block in front of its chair" {
        val expected =
            mapOf(
                "brewery_south_west" to Triple(-6.5, 71.1, 37.5),
                "brewery_south_east" to Triple(-4.5, 71.1, 37.5),
                "brewery_fire" to Triple(-8.5, 70.75, 47.5),
                "brewery_rina" to Triple(-14.5, 71.1, 47.5),
                "brewery_west" to Triple(-14.5, 71.1, 53.5),
                "brewery_east" to Triple(-3.5, 71.1, 53.5),
                "restaurant_a" to Triple(-54.5, 73.1, 47.5),
                "restaurant_b" to Triple(-47.5, 73.1, 51.5),
            )

        OriginDiningLayout.seats.forEach { seat ->
            val target = expected.getValue(seat.id)
            (abs(seat.dish.x - target.first) < 0.000_001) shouldBe true
            (abs(seat.dish.y - target.second) < 0.000_001) shouldBe true
            (abs(seat.dish.z - target.third) < 0.000_001) shouldBe true
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

    "model bounds define the scale and table-surface lift" {
        OriginDiningLayout.displayScale("steak") shouldBe 1.3f
        OriginDiningLayout.displayScale("egg") shouldBe 0.65f
        OriginDiningLayout.displayScale("herbal_tea") shouldBe 0.65f
        OriginDiningLayout.displayScale("fish") shouldBe 0.65f
        OriginDiningLayout.displayLift("steak") shouldBe 0.2025f
        OriginDiningLayout.displayLift("egg") shouldBe 0.12125f
        OriginDiningLayout.displayLift("herbal_tea") shouldBe 0.0205f
        OriginDiningLayout.mealHitboxSize shouldBe 1.8f
    }

    "chair block fallback resolves the real clicked stair" {
        OriginDiningLayout.seatForBlock(-8, 70, 37)?.id shouldBe "brewery_south_west"
        OriginDiningLayout.seatForBlock(-14, 70, 47)?.id shouldBe "brewery_rina"
        OriginDiningLayout.seatForBlock(-55, 72, 48)?.id shouldBe "restaurant_a"
        OriginDiningLayout.seatForBlock(-55, 72, 47) shouldBe null
    }
})
