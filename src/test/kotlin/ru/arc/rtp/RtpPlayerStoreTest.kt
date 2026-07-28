package ru.arc.rtp

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class RtpPlayerStoreTest : FreeSpec({

    "RtpPlayerStore" - {
        "stores UUIDs globally and by normalized world" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("data/rtp-players.json")
            val playerId = UUID.randomUUID()
            val store = RtpPlayerStore.open(path)

            store.state(playerId, "survival") shouldBe PlayerRtpState(false, false)
            store.markTeleported(playerId, " Survival ").shouldBeTrue()
            store.markTeleported(playerId, "survival").shouldBeFalse()

            val restored = RtpPlayerStore.open(path)
            restored.state(playerId, "SURVIVAL") shouldBe PlayerRtpState(true, true)
            restored.state(playerId, "mining") shouldBe PlayerRtpState(true, false)
            restored.playerCount() shouldBe 1
            restored.worldCount() shouldBe 1
        }

        "keeps global and per-world migration state independent" {
            val globalOnly = UUID.randomUUID()
            val worldOnly = UUID.randomUUID()
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("rtp-players.json")
            Files.writeString(
                path,
                """
                {
                  "version": 1,
                  "teleportedPlayers": ["$globalOnly"],
                  "teleportedByWorld": {
                    "survival": ["$worldOnly"]
                  }
                }
                """.trimIndent(),
            )

            val store = RtpPlayerStore.open(path)

            store.state(globalOnly, "survival") shouldBe PlayerRtpState(true, false)
            store.state(worldOnly, "survival") shouldBe PlayerRtpState(false, true)
        }

        "resets only one world when a world is specified" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("data/rtp-players.json")
            val playerId = UUID.randomUUID()
            val store = RtpPlayerStore.open(path)
            store.markTeleported(playerId, "survival")
            store.markTeleported(playerId, "vanilla")

            val result = store.reset(playerId, " Survival ")

            result shouldBe PlayerRtpResetResult(globalRemoved = false, worldsRemoved = listOf("survival"))
            store.state(playerId, "survival") shouldBe PlayerRtpState(true, false)
            store.state(playerId, "vanilla") shouldBe PlayerRtpState(true, true)
            RtpPlayerStore.open(path).state(playerId, "survival") shouldBe PlayerRtpState(true, false)
        }

        "resets global and every world marker when no world is specified" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("data/rtp-players.json")
            val playerId = UUID.randomUUID()
            val store = RtpPlayerStore.open(path)
            store.markTeleported(playerId, "survival")
            store.markTeleported(playerId, "vanilla")

            val result = store.reset(playerId)

            result shouldBe
                PlayerRtpResetResult(
                    globalRemoved = true,
                    worldsRemoved = listOf("survival", "vanilla"),
                )
            store.state(playerId, "survival") shouldBe PlayerRtpState(false, false)
            store.state(playerId, "vanilla") shouldBe PlayerRtpState(false, false)
            store.playerCount() shouldBe 0
            store.worldCount() shouldBe 0
            RtpPlayerStore.open(path).state(playerId, "survival") shouldBe PlayerRtpState(false, false)
        }

        "returns a no-op result without creating a file" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("data/rtp-players.json")
            val store = RtpPlayerStore.open(path)

            store.reset(UUID.randomUUID()) shouldBe
                PlayerRtpResetResult(globalRemoved = false, worldsRemoved = emptyList())
            Files.exists(path).shouldBeFalse()
        }

        "rejects malformed UUID instead of silently starting from empty state" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("rtp-players.json")
            Files.writeString(
                path,
                """{"version":1,"teleportedPlayers":["not-a-uuid"],"teleportedByWorld":{}}""",
            )

            shouldThrow<IllegalArgumentException> {
                RtpPlayerStore.open(path)
            }
        }

        "rejects unknown file version" {
            val path = Files.createTempDirectory("arc-rtp-store-").resolve("rtp-players.json")
            Files.writeString(
                path,
                """{"version":2,"teleportedPlayers":[],"teleportedByWorld":{}}""",
            )

            shouldThrow<IllegalArgumentException> {
                RtpPlayerStore.open(path)
            }
        }
    }
})
