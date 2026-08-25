package ru.arc.hooks.zauction

import io.kotest.core.spec.style.FreeSpec
import io.mockk.mockk
import io.mockk.verify
import ru.arc.redis.RedisOperations

class AuctionMessagerTest : FreeSpec({
    "publishes a typed sale event on its dedicated channel" {
        val redis = mockk<RedisOperations>(relaxed = true)
        val messager = AuctionMessager("items", "all", "sales", redis)
        val sale =
            AuctionSaleEventDto(
                listingId = "42",
                sellerUuid = "11111111-1111-1111-1111-111111111111",
                sellerName = "Seller",
                buyerName = "Buyer",
                itemDisplay = "Алмаз",
                amount = 2,
                price = "1 000",
                occurredAt = 123,
            )

        messager.sendSale(sale)

        verify(exactly = 1) {
            redis.publish(
                "sales",
                match { payload ->
                    payload.contains("\"listingId\":\"42\"") &&
                        payload.contains("\"sellerName\":\"Seller\"") &&
                        payload.contains("\"buyerName\":\"Buyer\"")
                },
            )
        }
    }
})
