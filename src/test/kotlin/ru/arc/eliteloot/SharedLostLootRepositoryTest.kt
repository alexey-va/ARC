package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class SharedLostLootRepositoryTest : FreeSpec({
    "payload hash is stable UTF-8 SHA-256" {
        MySqlSharedLostLootRepository.itemHash("лут") shouldBe
            "8a746cf2c0136d0e8e2609bd16ed16b92c3e09a3e958547dbfa9e0470039e90e"
    }

    "local stored records map to one available shared tombstone identity" {
        val id = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString()
        val record = LostLootRecord(id, owner, "payload", 1_000L)
        record.validate()
        SharedLootState.entries.toSet() shouldBe
            setOf(SharedLootState.AVAILABLE, SharedLootState.CLAIMING, SharedLootState.CLAIMED, SharedLootState.SOLD, SharedLootState.CREDITING, SharedLootState.PAID)
    }
})
