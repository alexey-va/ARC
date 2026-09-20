package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OriginDiningReliabilityPolicyTest : FreeSpec({
    "delivery route retries once and then exhausts" {
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(0) shouldBe true
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(1) shouldBe false
        OriginDiningReliabilityPolicy.shouldRetryDeliveryRoute(2) shouldBe false
    }

    "ambient rest starts at confirmed arrival" {
        OriginDiningReliabilityPolicy.ambientAvailableAtArrival(
            arrivalAt = 42_000L,
            restMillis = 18_000L,
        ) shouldBe 60_000L
    }

    "stale waiter callbacks lose ownership after a replacement operation" {
        val oldToken = UUID.randomUUID()
        val newToken = UUID.randomUUID()

        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, oldToken) shouldBe true
        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, newToken) shouldBe false
        OriginDiningReliabilityPolicy.ownsWaiterCallback(oldToken, null) shouldBe false
    }
})
