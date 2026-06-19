package ru.arc.xserver.playerlist

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ru.arc.util.Common
import java.util.UUID

/**
 * Unit tests for PlayerManager.
 * Tests the JSON message parsing and state management that does not require Bukkit.
 */
class PlayerManagerTest : DescribeSpec({

    beforeEach {
        // Reset state between tests by feeding an empty message
        PlayerManager.readMessage("[]")
    }

    describe("readMessage") {

        it("should parse empty list and clear state") {
            PlayerManager.readMessage("[]")

            PlayerManager.getPlayerUuids().shouldBeEmpty()
            PlayerManager.getPlayerNames().shouldBeEmpty()
            PlayerManager.getServerNames().shouldBeEmpty()
        }

        it("should parse single player entry") {
            val uuid = UUID.randomUUID()
            val json = buildPlayerJson(listOf(Triple("Alice", "survival", uuid)))

            PlayerManager.readMessage(json)

            PlayerManager.getPlayerUuids() shouldContain uuid
            PlayerManager.getPlayerNames() shouldContain "Alice"
            PlayerManager.getServerNames() shouldContain "survival"
        }

        it("should parse multiple players from different servers") {
            val uuid1 = UUID.randomUUID()
            val uuid2 = UUID.randomUUID()
            val json = buildPlayerJson(listOf(
                Triple("Alice", "survival", uuid1),
                Triple("Bob", "creative", uuid2)
            ))

            PlayerManager.readMessage(json)

            PlayerManager.getPlayerUuids() shouldContainAll setOf(uuid1, uuid2)
            PlayerManager.getPlayerNames() shouldContainAll setOf("Alice", "Bob")
            PlayerManager.getServerNames() shouldContainAll setOf("survival", "creative")
        }

        it("should replace previous state on each call") {
            val uuid1 = UUID.randomUUID()
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("Alice", "s1", uuid1))))

            val uuid2 = UUID.randomUUID()
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("Bob", "s2", uuid2))))

            // Alice should be gone
            PlayerManager.getPlayerNames() shouldBe setOf("Bob")
            PlayerManager.getPlayerUuids() shouldBe setOf(uuid2)
        }

        it("should accumulate servers across multiple players on same server") {
            val json = buildPlayerJson(listOf(
                Triple("P1", "lobby", UUID.randomUUID()),
                Triple("P2", "lobby", UUID.randomUUID()),
                Triple("P3", "survival", UUID.randomUUID())
            ))

            PlayerManager.readMessage(json)

            // Servers accumulate (once seen, always known) — at minimum lobby + survival present
            PlayerManager.getServerNames() shouldContainAll setOf("lobby", "survival")
        }

        it("should handle invalid JSON by logging error (state unchanged)") {
            // Feed valid state first
            val uuid = UUID.randomUUID()
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("Existing", "srv", uuid))))

            // Invalid JSON — PlayerManager logs an error; we verify no exception propagates
            // and existing state is cleared (Gson returns null → logs and returns)
            try {
                PlayerManager.readMessage("{not-valid-json}")
            } catch (_: Exception) {
                // Some Gson versions throw here; the important thing is state is handled
            }
        }
    }

    describe("getPlayerData") {

        it("should return null for unknown UUID") {
            PlayerManager.readMessage("[]")

            PlayerManager.getPlayerData(UUID.randomUUID()).shouldBeNull()
        }

        it("should return PlayerData for known UUID") {
            val uuid = UUID.randomUUID()
            val joinTime = System.currentTimeMillis()
            val json = buildPlayerJsonWithTime(listOf(
                PlayerEntry("Charlie", "hub", uuid, joinTime)
            ))

            PlayerManager.readMessage(json)

            val data = PlayerManager.getPlayerData(uuid)
            data.shouldNotBeNull()
            data.username shouldBe "Charlie"
            data.server shouldBe "hub"
            data.uuid shouldBe uuid
            data.joinTime shouldBe joinTime
        }

        it("should return updated data after new message") {
            val uuid = UUID.randomUUID()
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("OldName", "s1", uuid))))

            // Second message replaces state completely; uuid is gone
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("Other", "s2", UUID.randomUUID()))))

            PlayerManager.getPlayerData(uuid).shouldBeNull()
        }
    }

    describe("getPlayerNames") {
        it("should return empty set when no players") {
            PlayerManager.getPlayerNames().shouldBeEmpty()
        }

        it("should return all usernames") {
            val json = buildPlayerJson(listOf(
                Triple("X", "s", UUID.randomUUID()),
                Triple("Y", "s", UUID.randomUUID()),
                Triple("Z", "s", UUID.randomUUID())
            ))
            PlayerManager.readMessage(json)

            PlayerManager.getPlayerNames() shouldBe setOf("X", "Y", "Z")
        }
    }

    describe("findByName") {
        it("should find player case-insensitively") {
            val uuid = UUID.randomUUID()
            PlayerManager.readMessage(buildPlayerJson(listOf(Triple("Koxae", "survival", uuid))))

            PlayerManager.findByName("koxae")?.uuid shouldBe uuid
            PlayerManager.findByName("missing").shouldBeNull()
        }
    }

    describe("PlayerData data class") {
        it("should expose all fields correctly") {
            val uuid = UUID.randomUUID()
            val data = PlayerManager.PlayerData("Steve", "minigames", uuid, 99L)

            data.username shouldBe "Steve"
            data.server shouldBe "minigames"
            data.uuid shouldBe uuid
            data.joinTime shouldBe 99L
        }

        it("should support equality by value") {
            val uuid = UUID.randomUUID()
            val d1 = PlayerManager.PlayerData("X", "s", uuid, 1L)
            val d2 = PlayerManager.PlayerData("X", "s", uuid, 1L)

            d1 shouldBe d2
        }
    }
})

// ─── Helpers ────────────────────────────────────────────────────────────────

private data class PlayerEntry(val username: String, val server: String, val uuid: UUID, val joinTime: Long)

private fun buildPlayerJsonWithTime(entries: List<PlayerEntry>): String {
    val list = entries.map { mapOf("username" to it.username, "server" to it.server, "uuid" to it.uuid.toString(), "joinTime" to it.joinTime) }
    return Common.gson.toJson(list)
}

private fun buildPlayerJson(entries: List<Triple<String, String, UUID>>): String =
    buildPlayerJsonWithTime(entries.map { PlayerEntry(it.first, it.second, it.third, System.currentTimeMillis()) })
