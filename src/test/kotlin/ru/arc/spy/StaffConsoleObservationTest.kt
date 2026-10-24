package ru.arc.spy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class StaffConsoleObservationTest : FreeSpec({
    "separate console wire is strict, bounded and contains no execution instruction" {
        val message = StaffConsoleObservation(UUID.randomUUID(), "list", "CONSOLE", 1234L)
        val encoded = StaffConsoleObservationCodec.encode(message)
        StaffConsoleObservationCodec.CHANNEL shouldBe "arc.staff-console.v1"
        StaffConsoleObservationCodec.decode(encoded) shouldBe message
        StaffConsoleObservationCodec.decode(StaffConsoleObservationCodec.encode(message.copy(actor = UUID.randomUUID().toString()))).command shouldBe "list"
        listOf(
            encoded + "{}", encoded.replace("\"v\":1", "\"v\":2"),
            encoded.replace("\"createdAt\":1234", "\"createdAt\":1234.1"),
            encoded.dropLast(1) + ",\"dispatch\":true}",
            encoded.replace("\"CONSOLE\"", "\"AdminName\""),
        ).forEach { shouldThrow<IllegalArgumentException> { StaffConsoleObservationCodec.decode(it) } }
        shouldThrow<IllegalArgumentException> { StaffConsoleObservationCodec.encode(message.copy(command = "list\nstop")) }
        shouldThrow<IllegalArgumentException> { StaffConsoleObservationCodec.encode(message.copy(command = "x".repeat(513))) }
    }
})
