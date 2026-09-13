package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.worldcontent.BreweryTableDialogs
import java.nio.file.Files
import kotlin.math.abs

class OriginDiningLayoutTest : FreeSpec({
    beforeSpec {
        OriginDiningLayout.load(Files.createTempDirectory("origin-dining-layout-test"))
    }
    "every authored chair has a unique block and table anchor" {
        OriginDiningLayout.seats.size shouldBe 6
        OriginDiningLayout.seats.map { it.clickedBlock }.distinct().size shouldBe 6
        OriginDiningLayout.seats.map { it.id }.distinct().size shouldBe 6
    }

    "brewery exposes several tables and only the fire seat opens drinks" {
        OriginDiningLayout.seats.count { it.id.startsWith("brewery_") } shouldBe 4
        OriginDiningLayout.seats.single { it.id == "brewery_fire" }.menu shouldBe BreweryTableDialogs.Menu.DRINKS
        OriginDiningLayout.seats.single { it.id == "brewery_west" }.menu shouldBe BreweryTableDialogs.Menu.COURTYARD
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
                "brewery_west" to Triple(-14.5, 71.1, 53.5),
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

    "authored coordinates are loaded from the reloadable module config" {
        OriginDiningLayout.seats.single { it.id == "brewery_south_west" }.clickedBlock shouldBe Triple(-8, 70, 37)
        OriginDiningLayout.seats.single { it.id == "restaurant_a" }.clickedBlock shouldBe Triple(-55, 72, 48)
        OriginDiningLayout.waiterHome(410) shouldBe OriginDiningPoint(1.5, 70.0, 57.5, 180f)
    }

    "ambient intervals and theft cooldown are loaded from the reloadable module config" {
        OriginDiningLayout.ambientDialogueMillis shouldBe 42_000L
        OriginDiningLayout.ambientRetryMillis shouldBe 5_000L
        OriginDiningLayout.guestReconcileMillis shouldBe 5_000L
        OriginDiningLayout.theftCooldownMillis shouldBe 90_000L
        OriginDiningLayout.waiterPlayerRange shouldBe 2.8
        OriginDiningLayout.navigatorDistanceMargin shouldBe 0.35
        OriginDiningLayout.navigatorPathDistanceMargin shouldBe 0.35
    }

    "dynamic seats are limited to the two restaurant territories" {
        val brewery = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val restaurant = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val outside = io.mockk.mockk<org.bukkit.Location>(relaxed = true)
        val world = io.mockk.mockk<org.bukkit.World>(relaxed = true)
        io.mockk.every { world.name } returns OriginDiningLayout.WORLD
        listOf(brewery, restaurant, outside).forEach { io.mockk.every { it.world } returns world }
        io.mockk.every { brewery.x } returns BreweryTableDialogs.BREWERY_X
        io.mockk.every { brewery.y } returns BreweryTableDialogs.BREWERY_Y
        io.mockk.every { brewery.z } returns BreweryTableDialogs.BREWERY_Z
        io.mockk.every { restaurant.x } returns BreweryTableDialogs.RESTAURANT_X
        io.mockk.every { restaurant.y } returns BreweryTableDialogs.RESTAURANT_Y
        io.mockk.every { restaurant.z } returns BreweryTableDialogs.RESTAURANT_Z
        io.mockk.every { outside.x } returns 200.0
        io.mockk.every { outside.y } returns 70.0
        io.mockk.every { outside.z } returns 200.0

        OriginDiningLayout.dynamicMenu(brewery) shouldBe BreweryTableDialogs.Menu.COURTYARD
        OriginDiningLayout.dynamicMenu(restaurant) shouldBe BreweryTableDialogs.Menu.RESTAURANT
        OriginDiningLayout.dynamicMenu(outside) shouldBe null
    }
})
