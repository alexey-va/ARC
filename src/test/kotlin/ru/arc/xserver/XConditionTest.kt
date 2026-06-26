package ru.arc.xserver

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.KotestTestBase
import ru.arc.xaction.XCondition
import ru.arc.xserver.matches
import java.util.UUID

/**
 * Comprehensive tests for XCondition — the predicate that controls which players
 * receive cross-server messages.
 *
 * Covers:
 * - Factory constructors (ofPermission, ofServerName, ofPlayerName, ofPlayerUuid)
 * - test() logic for each condition type
 * - Case-insensitive matching
 * - Multiple conditions combined (all must pass)
 * - Null / empty condition (always passes)
 * - Server-name mismatch (player on wrong server is excluded)
 * - Data class equality and copy
 */
@Suppress("USELESS_CAST")
class XConditionTest : KotestTestBase({

    // ─── Factory constructors ────────────────────────────────────────────────

    describe("XCondition factory methods") {

        it("ofPermission creates condition with only permission set") {
            val cond = XCondition.ofPermission("arc.board.receive")
            cond.permission shouldBe "arc.board.receive"
            cond.playerName shouldBe null
            cond.playerUuid shouldBe null
            cond.serverName shouldBe null
            cond.placeholders shouldBe null
        }

        it("ofServerName creates condition with only serverName set") {
            val cond = XCondition.ofServerName("lobby")
            cond.serverName shouldBe "lobby"
            cond.permission shouldBe null
        }

        it("ofPlayerName creates condition with only playerName set") {
            val cond = XCondition.ofPlayerName("Steve")
            cond.playerName shouldBe "Steve"
            cond.permission shouldBe null
        }

        it("ofPlayerUuid creates condition with only playerUuid set") {
            val uuid = UUID.randomUUID()
            val cond = XCondition.ofPlayerUuid(uuid)
            cond.playerUuid shouldBe uuid
            cond.playerName shouldBe null
        }
    }

    // ─── Permission check ────────────────────────────────────────────────────

    describe("permission condition") {

        it("passes when player has the required permission") {
            val player = server.addPlayer("Notch") as PlayerMock
            player.addAttachment(plugin, "arc.test.perm", true)
            val cond = XCondition.ofPermission("arc.test.perm")

            cond.matches(player).shouldBeTrue()
        }

        it("fails when player lacks the required permission") {
            val player = server.addPlayer("Herobrine") as PlayerMock
            val cond = XCondition.ofPermission("arc.test.perm")

            cond.matches(player).shouldBeFalse()
        }

        it("passes for op player (ops have all permissions)") {
            val player = server.addPlayer("Op") as PlayerMock
            player.isOp = true
            val cond = XCondition.ofPermission("any.perm")

            cond.matches(player).shouldBeTrue()
        }
    }

    // ─── Player-name check ───────────────────────────────────────────────────

    describe("playerName condition") {

        it("passes when player name matches exactly") {
            val player = server.addPlayer("Alice")
            val cond = XCondition.ofPlayerName("Alice")

            cond.matches(player).shouldBeTrue()
        }

        it("passes when player name matches case-insensitively") {
            val player = server.addPlayer("Alice")
            val cond = XCondition.ofPlayerName("ALICE")

            cond.matches(player).shouldBeTrue()
        }

        it("fails when player name does not match") {
            val player = server.addPlayer("Alice")
            val cond = XCondition.ofPlayerName("Bob")

            cond.matches(player).shouldBeFalse()
        }

        it("fails when name differs by single char") {
            val player = server.addPlayer("Alice")
            val cond = XCondition.ofPlayerName("Alic")

            cond.matches(player).shouldBeFalse()
        }
    }

    // ─── Player-UUID check ───────────────────────────────────────────────────

    describe("playerUuid condition") {

        it("passes when UUID matches") {
            val player = server.addPlayer("UuidPlayer")
            val cond = XCondition.ofPlayerUuid(player.uniqueId)

            cond.matches(player).shouldBeTrue()
        }

        it("fails when UUID does not match") {
            val player = server.addPlayer("UuidPlayer2")
            val cond = XCondition.ofPlayerUuid(UUID.randomUUID())

            cond.matches(player).shouldBeFalse()
        }

        it("matches specific player among many") {
            val target = server.addPlayer("Target")
            server.addPlayer("Other1")
            server.addPlayer("Other2")
            val cond = XCondition.ofPlayerUuid(target.uniqueId)

            cond.matches(target).shouldBeTrue()
        }
    }

    // ─── Serverside (no-check) conditions ────────────────────────────────────

    describe("serverName condition") {

        it("passes when server name matches configured server") {
            // KotestTestBase sets ARC.serverName = 'test-server' and the misc.yml
            // redis.server-name defaults. We set serverName to match 'test-server'.
            val player = server.addPlayer("ServerPlayer")
            val cond = XCondition.ofServerName("test-server")

            cond.matches(player).shouldBeTrue()
        }

        it("fails when server name does not match") {
            val player = server.addPlayer("ServerPlayer2")
            val cond = XCondition.ofServerName("other-server")

            cond.matches(player).shouldBeFalse()
        }

        it("passes case-insensitively for server name") {
            val player = server.addPlayer("ServerPlayer3")
            val cond = XCondition.ofServerName("TEST-SERVER")

            cond.matches(player).shouldBeTrue()
        }
    }

    // ─── Empty / null condition (no fields set) ──────────────────────────────

    describe("empty condition") {

        it("passes for any player when no filter fields are set") {
            val player = server.addPlayer("Anybody")
            val cond = XCondition()

            cond.matches(player).shouldBeTrue()
        }
    }

    // ─── PAPI / placeholder condition ────────────────────────────────────────

    describe("placeholder condition when PAPI hook is absent") {

        it("fails when placeholders are set but PAPI hook is null") {
            val player = server.addPlayer("PAPIPlayer")
            val cond = XCondition(placeholders = mapOf("%some_placeholder%" to "value"))

            // No PAPI hook registered → should return false and log warning
            cond.matches(player).shouldBeFalse()
        }

        it("passes when placeholders map is empty") {
            val player = server.addPlayer("NoPAPIPlayer")
            val cond = XCondition(placeholders = emptyMap())

            cond.matches(player).shouldBeTrue()
        }

        it("passes when placeholders is null") {
            val player = server.addPlayer("NullPAPIPlayer")
            val cond = XCondition(placeholders = null)

            cond.matches(player).shouldBeTrue()
        }
    }

    // ─── Combining multiple conditions ───────────────────────────────────────

    describe("multiple conditions combined") {

        it("all conditions must pass — both permission and name") {
            val player = server.addPlayer("MultiPlayer") as PlayerMock
            player.addAttachment(plugin, "arc.multi", true)

            // name matches AND permission granted → should pass
            val condName = XCondition.ofPlayerName("MultiPlayer")
            val condPerm = XCondition.ofPermission("arc.multi")

            condName.matches(player).shouldBeTrue()
            condPerm.matches(player).shouldBeTrue()
        }

        it("fails if name matches but permission is missing") {
            val player = server.addPlayer("ComboFail") as PlayerMock
            // NOT granting arc.multi

            val condName = XCondition.ofPlayerName("ComboFail")
            val condPerm = XCondition.ofPermission("arc.combo")

            condName.matches(player).shouldBeTrue()
            condPerm.matches(player).shouldBeFalse()
        }

        it("both uuid and name can be checked independently on same player") {
            val player = server.addPlayer("DualCheck")
            val condUuid = XCondition.ofPlayerUuid(player.uniqueId)
            val condName = XCondition.ofPlayerName(player.name)

            condUuid.matches(player).shouldBeTrue()
            condName.matches(player).shouldBeTrue()
        }
    }

    // ─── Data class equality ─────────────────────────────────────────────────

    describe("equality and copy") {

        it("two conditions with same fields are equal") {
            val perm = "arc.foo"
            val c1 = XCondition.ofPermission(perm)
            val c2 = XCondition.ofPermission(perm)

            c1 shouldBe c2
        }

        it("two conditions with different fields are not equal") {
            val c1 = XCondition.ofPermission("arc.a")
            val c2 = XCondition.ofPermission("arc.b")

            c1 shouldNotBe c2
        }

        it("copy with changed field produces new condition") {
            val original = XCondition.ofPlayerName("Alice")
            val copy = original.copy(playerName = "Bob")

            copy.playerName shouldBe "Bob"
            original.playerName shouldBe "Alice"
        }
    }
})
