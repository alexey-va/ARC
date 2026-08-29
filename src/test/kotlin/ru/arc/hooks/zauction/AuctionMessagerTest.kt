package ru.arc.hooks.zauction

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations

class AuctionMessagerTest : FreeSpec({
    "publishes the scheduled auction feed as a complete replacement snapshot" {
        val redis = RecordingRedis()
        val messager = AuctionMessager("items", "all", "sales", redis)
        val item =
            AuctionItemDto(
                display = "Алмаз",
                seller = "Seller",
                price = "1 000",
                uuid = "11111111-1111-1111-1111-111111111111",
                exist = true,
            )

        messager.send(listOf(item))

        redis.publications.size shouldBe 1
        val (channel, payload) = redis.publications.single()
        channel shouldBe "all"
        payload shouldContain "\"display\":\"Алмаз\""
        payload shouldContain "\"seller\":\"Seller\""
    }

    "publishes a typed sale event on its dedicated channel" {
        val redis = RecordingRedis()
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

        redis.publications.size shouldBe 1
        val (channel, payload) = redis.publications.single()
        channel shouldBe "sales"
        payload shouldContain "\"listingId\":\"42\""
        payload shouldContain "\"sellerName\":\"Seller\""
        payload shouldContain "\"buyerName\":\"Buyer\""
    }
})

private class RecordingRedis(
    private val delegate: InMemoryRedis = InMemoryRedis(),
) : RedisOperations by delegate {
    val publications = mutableListOf<Pair<String, String>>()

    override fun publish(channel: String, message: String) {
        publications += channel to message
    }
}
