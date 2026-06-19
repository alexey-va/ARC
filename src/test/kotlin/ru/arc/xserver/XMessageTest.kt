package ru.arc.xserver

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.TextComponent
import org.bukkit.Material
import org.bukkit.boss.BarColor
import org.mockbukkit.mockbukkit.entity.PlayerMock
import com.google.gson.Gson
import ru.arc.KotestTestBase
import ru.arc.util.Common
import java.util.UUID

/**
 * Large test suite for XMessage — the cross-server message class.
 *
 * Covers:
 * - Data class construction and nested classes
 * - filteredPlayers() with various XCondition combinations
 * - component() rendering for all SerializationTypes
 * - Gson serialization / deserialization (round-trip)
 * - Type and SerializationType enums
 */
@Suppress("USELESS_CAST")
class XMessageTest : KotestTestBase({

    // ─── Construction and defaults ───────────────────────────────────────────

    describe("XMessage construction") {

        it("should create message with all fields set") {
            val msg = XMessage(
                type = XMessage.Type.CHAT,
                serializedMessage = "Hello world",
                serializationType = XMessage.SerializationType.PLAIN,
                conditions = listOf(XCondition.ofPermission("arc.test")),
                bossBarData = XMessage.BossBarData(name = "bar", color = BarColor.BLUE, seconds = 5, keepFor = 10),
                toastData = XMessage.ToastData(material = Material.DIAMOND, modelData = 3, title = "Toast!"),
                announceData = XMessage.AnnounceData(weight = 2, personal = true),
                actionBarData = XMessage.ActionBarData(seconds = 3)
            )

            msg.type shouldBe XMessage.Type.CHAT
            msg.serializedMessage shouldBe "Hello world"
            msg.serializationType shouldBe XMessage.SerializationType.PLAIN
            msg.conditions!! shouldHaveSize 1
            msg.bossBarData?.name shouldBe "bar"
            msg.bossBarData?.color shouldBe BarColor.BLUE
            msg.bossBarData?.seconds shouldBe 5
            msg.bossBarData?.keepFor shouldBe 10
            msg.toastData?.material shouldBe Material.DIAMOND
            msg.toastData?.modelData shouldBe 3
            msg.toastData?.title shouldBe "Toast!"
            msg.announceData?.weight shouldBe 2
            msg.announceData?.personal shouldBe true
            msg.actionBarData?.seconds shouldBe 3
        }

        it("should use null defaults for optional fields") {
            val msg = XMessage()

            msg.type shouldBe null
            msg.serializedMessage shouldBe null
            msg.conditions shouldBe null
            msg.bossBarData shouldBe null
            msg.toastData shouldBe null
            msg.announceData shouldBe null
            msg.actionBarData shouldBe null
        }
    }

    // ─── Nested data classes ─────────────────────────────────────────────────

    describe("BossBarData") {
        it("should default to zero values") {
            val bbd = XMessage.BossBarData()

            bbd.name shouldBe null
            bbd.color shouldBe null
            bbd.seconds shouldBe 0
            bbd.keepFor shouldBe 0
        }

        it("should support equality") {
            val b1 = XMessage.BossBarData("test", BarColor.GREEN, 5, 10)
            val b2 = XMessage.BossBarData("test", BarColor.GREEN, 5, 10)

            b1 shouldBe b2
        }
    }

    describe("ToastData") {
        it("should default material to STONE") {
            val td = XMessage.ToastData()

            td.material shouldBe Material.STONE
            td.modelData shouldBe 0
            td.title shouldBe null
        }

        it("should allow custom material") {
            val td = XMessage.ToastData(material = Material.GOLD_INGOT, title = "Gold!")

            td.material shouldBe Material.GOLD_INGOT
            td.title shouldBe "Gold!"
        }
    }

    describe("AnnounceData") {
        it("should default weight to 0 and personal to false") {
            val ad = XMessage.AnnounceData()

            ad.weight shouldBe 0
            ad.personal shouldBe false
        }
    }

    describe("ActionBarData") {
        it("should default seconds to 0") {
            val ab = XMessage.ActionBarData()

            ab.seconds shouldBe 0
        }

        it("should store given seconds") {
            val ab = XMessage.ActionBarData(seconds = 15)

            ab.seconds shouldBe 15
        }
    }

    // ─── filteredPlayers() ───────────────────────────────────────────────────

    describe("filteredPlayers()") {

        it("returns all online players when conditions list is null") {
            server.addPlayer("P1")
            server.addPlayer("P2")
            server.addPlayer("P3")
            val totalOnline = server.onlinePlayers.size

            val msg = XMessage(type = XMessage.Type.CHAT, conditions = null)

            msg.filteredPlayers() shouldHaveSize totalOnline
        }

        it("returns all online players when conditions list is empty") {
            server.addPlayer("AA")
            server.addPlayer("BB")
            val totalOnline = server.onlinePlayers.size

            val msg = XMessage(type = XMessage.Type.CHAT, conditions = emptyList())

            msg.filteredPlayers() shouldHaveSize totalOnline
        }

        it("filters to matching player by UUID") {
            val target = server.addPlayer("Target")
            server.addPlayer("Other1")
            server.addPlayer("Other2")

            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(XCondition.ofPlayerUuid(target.uniqueId))
            )

            val filtered = msg.filteredPlayers()
            filtered shouldHaveSize 1
            filtered[0].name shouldBe "Target"
        }

        it("filters to matching player by name") {
            server.addPlayer("Chosen")
            server.addPlayer("NotChosen1")
            server.addPlayer("NotChosen2")

            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(XCondition.ofPlayerName("Chosen"))
            )

            val filtered = msg.filteredPlayers()
            filtered shouldHaveSize 1
            filtered[0].name shouldBe "Chosen"
        }

        it("returns empty list when no player matches the UUID") {
            server.addPlayer("NoMatch1")
            server.addPlayer("NoMatch2")

            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(XCondition.ofPlayerUuid(UUID.randomUUID()))
            )

            msg.filteredPlayers().shouldBeEmpty()
        }

        it("filters by permission — only players with permission pass") {
            val withPerm = server.addPlayer("WithPerm") as PlayerMock
            withPerm.addAttachment(plugin, "arc.board.receive", true)
            server.addPlayer("WithoutPerm")

            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(XCondition.ofPermission("arc.board.receive"))
            )

            val filtered = msg.filteredPlayers()
            filtered shouldHaveSize 1
            filtered[0].name shouldBe "WithPerm"
        }

        it("multiple conditions are ANDed — player must satisfy all") {
            val player = server.addPlayer("MultiCond") as PlayerMock
            player.addAttachment(plugin, "arc.multi", true)
            server.addPlayer("NameOnly") // no permission
            server.addPlayer("PermOnly") as PlayerMock // will not have matching name

            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(
                    XCondition.ofPlayerName("MultiCond"),
                    XCondition.ofPermission("arc.multi")
                )
            )

            val filtered = msg.filteredPlayers()
            filtered shouldHaveSize 1
            filtered[0].name shouldBe "MultiCond"
        }

        it("returns empty list when conditions cannot be simultaneously satisfied") {
            server.addPlayer("Alice")
            server.addPlayer("Bob")

            // Conditions require two different names simultaneously — impossible
            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(
                    XCondition.ofPlayerName("Alice"),
                    XCondition.ofPlayerName("Bob")
                )
            )

            msg.filteredPlayers().shouldBeEmpty()
        }

        it("condition matching UUID of non-existing player returns empty list") {
            val msg = XMessage(
                type = XMessage.Type.CHAT,
                conditions = listOf(XCondition.ofPlayerUuid(UUID.randomUUID()))
            )

            msg.filteredPlayers().shouldBeEmpty()
        }
    }

    // ─── component() rendering ───────────────────────────────────────────────

    describe("component() text rendering") {

        it("renders PLAIN text as plain component") {
            val player = server.addPlayer("PlainPlayer")
            val msg = XMessage(
                serializedMessage = "Hello plain",
                serializationType = XMessage.SerializationType.PLAIN
            )

            val component = msg.component(player)
            val text = (component as? TextComponent)?.content() ?: component.toString()

            text shouldBe "Hello plain"
        }

        it("renders LEGACY text without throwing") {
            val player = server.addPlayer("LegacyPlayer")
            val msg = XMessage(
                serializedMessage = "&aGreen &bBlue",
                serializationType = XMessage.SerializationType.LEGACY
            )

            val component = msg.component(player)
            component shouldNotBe null
        }

        it("renders MINI_MESSAGE text without throwing") {
            val player = server.addPlayer("MMPlayer")
            val msg = XMessage(
                serializedMessage = "<green>Green</green> <red>Red</red>",
                serializationType = XMessage.SerializationType.MINI_MESSAGE
            )

            val component = msg.component(player)
            component shouldNotBe null
        }

        it("falls back to plain when serializationType is null") {
            val player = server.addPlayer("NullTypePlayer")
            val msg = XMessage(
                serializedMessage = "Fallback text",
                serializationType = null
            )

            val component = msg.component(player)
            component shouldNotBe null
        }

        it("handles null serializedMessage without throwing") {
            val player = server.addPlayer("NullMsgPlayer")
            val msg = XMessage(serializedMessage = null, serializationType = XMessage.SerializationType.PLAIN)

            val component = msg.component(player)
            component shouldNotBe null
        }
    }

    // ─── Enums ───────────────────────────────────────────────────────────────

    describe("Type enum") {
        it("contains CHAT, ACTION_BAR, BOSS_BAR, TOAST") {
            val types = XMessage.Type.values()
            types.map { it.name } shouldBe listOf("CHAT", "ACTION_BAR", "BOSS_BAR", "TOAST")
        }
    }

    describe("SerializationType enum") {
        it("contains MINI_MESSAGE, LEGACY, PLAIN") {
            val types = XMessage.SerializationType.values()
            types.map { it.name } shouldBe listOf("MINI_MESSAGE", "LEGACY", "PLAIN")
        }
    }

    // ─── Gson serialization / deserialization ────────────────────────────────

    describe("Gson round-trip") {

        it("should serialize and deserialize XMessage with all fields") {
            val original = XMessage(
                type = XMessage.Type.CHAT,
                serializedMessage = "Test",
                serializationType = XMessage.SerializationType.LEGACY,
                conditions = listOf(XCondition.ofPermission("arc.test")),
                announceData = XMessage.AnnounceData(weight = 3, personal = false)
            )

            val json = Common.gson.toJson(original)
            val restored = Common.gson.fromJson(json, XMessage::class.java)

            restored.type shouldBe XMessage.Type.CHAT
            restored.serializedMessage shouldBe "Test"
            restored.serializationType shouldBe XMessage.SerializationType.LEGACY
            restored.announceData?.weight shouldBe 3
        }

        it("should preserve BossBarData through serialization") {
            val bbd = XMessage.BossBarData(name = "test-bar", color = BarColor.YELLOW, seconds = 8, keepFor = 16)
            val original = XMessage(type = XMessage.Type.BOSS_BAR, bossBarData = bbd)

            val json = Common.gson.toJson(original)
            val restored = Common.gson.fromJson(json, XMessage::class.java)

            restored.bossBarData?.name shouldBe "test-bar"
            restored.bossBarData?.color shouldBe BarColor.YELLOW
            restored.bossBarData?.seconds shouldBe 8
            restored.bossBarData?.keepFor shouldBe 16
        }

        it("should preserve ToastData through serialization") {
            val td = XMessage.ToastData(material = Material.EMERALD, modelData = 5, title = "Emerald!")
            val original = XMessage(type = XMessage.Type.TOAST, toastData = td)

            val json = Common.gson.toJson(original)
            val restored = Common.gson.fromJson(json, XMessage::class.java)

            restored.toastData?.material shouldBe Material.EMERALD
            restored.toastData?.modelData shouldBe 5
            restored.toastData?.title shouldBe "Emerald!"
        }

        it("should produce compact JSON using @SerializedName short names") {
            val msg = XMessage(
                type = XMessage.Type.CHAT,
                serializedMessage = "hi"
            )

            val json = Common.gson.toJson(msg)

            // @SerializedName("t") for type
            json.contains("\"t\"").shouldBeTrue()
            // @SerializedName("m") for serializedMessage
            json.contains("\"m\"").shouldBeTrue()
        }

        it("should deserialize from short @SerializedName keys (plain Gson)") {
            // Plain Gson (no PolymorphismAdapter) can deserialize XMessage directly
            val plainGson = Gson()
            val json = """{"t":"CHAT","m":"hello","st":"PLAIN"}"""

            val msg = plainGson.fromJson(json, XMessage::class.java)

            msg.type shouldBe XMessage.Type.CHAT
            msg.serializedMessage shouldBe "hello"
            msg.serializationType shouldBe XMessage.SerializationType.PLAIN
        }

        it("should handle missing optional fields gracefully (plain Gson)") {
            val plainGson = Gson()
            val json = """{"t":"CHAT","m":"minimal"}"""

            val msg = plainGson.fromJson(json, XMessage::class.java)

            msg.type shouldBe XMessage.Type.CHAT
            msg.bossBarData shouldBe null
            msg.toastData shouldBe null
            msg.conditions shouldBe null
        }

        it("serialized XMessage can be used in a round-trip via Common.gson with type discriminator") {
            // When using Common.gson, the PolymorphismAdapter adds "type":"xmessage"
            val original = XMessage(
                type = XMessage.Type.CHAT,
                serializedMessage = "round-trip-msg",
                serializationType = XMessage.SerializationType.MINI_MESSAGE
            )

            // Serialize as XAction (required for PolymorphismAdapter to add discriminator)
            val json = Common.gson.toJson(original as XAction)
            val restored = Common.gson.fromJson(json, XAction::class.java) as XMessage

            restored.serializedMessage shouldBe "round-trip-msg"
            restored.type shouldBe XMessage.Type.CHAT
        }
    }

    // ─── XAction inheritance ─────────────────────────────────────────────────

    describe("XAction inherited fields") {
        it("should inherit afterTimestamp and async from XAction") {
            val msg = XMessage()
            msg.afterTimestamp shouldBe null
            msg.async shouldBe null

            msg.afterTimestamp = 99999L
            msg.async = true

            msg.afterTimestamp shouldBe 99999L
            msg.async shouldBe true
        }
    }
})
