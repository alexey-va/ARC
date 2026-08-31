package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import org.bukkit.inventory.ItemStack
import ru.arc.hooks.zauction.AuctionShowcaseListing

class AuctionShowcasePlannerTest :
    FreeSpec({
        "cycles six distinct pedestals through the active listing ring" {
            val listings = (1..8).map(::listing)

            AuctionShowcasePlanner.select(listings, 6, 0).map { it?.id }
                .shouldContainExactly(1, 2, 3, 4, 5, 6)
            AuctionShowcasePlanner.select(listings, 6, 6).map { it?.id }
                .shouldContainExactly(7, 8, 1, 2, 3, 4)
        }

        "does not repeat a listing when fewer than six are active" {
            val listings = listOf(listing(1), listing(2), listing(2), listing(3))

            AuctionShowcasePlanner.select(listings, 6, 0).map { it?.id }
                .shouldContainExactly(1, 2, 3, null, null, null)
        }

        "renders a fully empty state when no listing is active" {
            AuctionShowcasePlanner.select(emptyList(), 6, 0).map { it?.id }
                .shouldContainExactly(null, null, null, null, null, null)
        }
    })

private fun listing(id: Int): AuctionShowcaseListing =
    AuctionShowcaseListing(
        id = id,
        item = mockk<ItemStack>(relaxed = true),
        itemName = "Камень $id",
        sellerName = "Seller$id",
        price = "$id₽",
    )
