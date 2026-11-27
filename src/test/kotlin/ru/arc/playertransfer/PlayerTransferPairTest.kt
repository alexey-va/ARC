package ru.arc.playertransfer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class PlayerTransferPairTest : StringSpec({
    val source = "85c79615-ad2b-4c01-ab04-31c61f3092f9"
    val target = "192ed7a6-a8a9-44ed-a336-8498a20ae3b9"

    "accepts two canonical UUIDs and preserves their direction" {
        PlayerTransferPair.parse(source.uppercase(), target) shouldBe
            PlayerTransferPair(UUID.fromString(source), UUID.fromString(target))
    }

    "rejects a name, truncated UUID and the null UUID" {
        for (bad in listOf("ExamplePlayer", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000")) {
            shouldThrow<IllegalArgumentException> { PlayerTransferPair.parse(bad, target) }
        }
    }

    "rejects moving an account to the same UUID" {
        shouldThrow<IllegalArgumentException> { PlayerTransferPair.parse(source, source.uppercase()) }
    }

    "counts only space free in both inventories and includes equipment collisions" {
        transferSlotRoom(setOf(0, 2, 36), setOf(1, 2, 36), 36) shouldBe (2 to 33)
        transferSlotRoom((0 until 27).toSet(), setOf(2, 9), 27) shouldBe (2 to 0)
    }

    "replaces terminal escapes in displayed player-owned names" {
        transferDisplayLabel("home\u001B\u202Eplot") shouldBe "home��plot"
    }
})
