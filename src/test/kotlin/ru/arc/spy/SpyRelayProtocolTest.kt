package ru.arc.spy

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.util.UUID

class SpyRelayProtocolTest : FreeSpec({
    val now = 1_780_000_000_000L

    "codec round-trips bounded command and chat messages" {
        val command = commandMessage(now)
        SpyRelayCodec.decode(SpyRelayCodec.encode(command), 4096, 1000) shouldBe command

        val chat =
            command.copy(
                id = UUID.fromString("30000000-0000-0000-0000-000000000003"),
                type = SpyRelayType.CHAT,
                targetUuid = UUID.fromString("40000000-0000-0000-0000-000000000004"),
                targetName = "TargetPlayer",
                content = "привет <red>&c",
            )
        SpyRelayCodec.decode(SpyRelayCodec.encode(chat), 4096, 1000) shouldBe chat
        SpyRelayCodec.decode(SpyRelayCodec.encode(chat.copy(targetUuid = null)), 4096, 1000) shouldBe
            chat.copy(targetUuid = null)
    }

    "codec rejects unknown fields, invalid versions, control characters and oversized payloads" {
        val valid = SpyRelayCodec.encode(commandMessage(now))
        val unknown = JsonParser.parseString(valid).asJsonObject.apply { addProperty("execute", "op attacker") }.toString()
        SpyRelayCodec.decode(unknown, 4096, 1000) shouldBe null

        val wrongVersion = JsonParser.parseString(valid).asJsonObject.apply { addProperty("v", 2) }.toString()
        SpyRelayCodec.decode(wrongVersion, 4096, 1000) shouldBe null

        val invalidTarget =
            JsonParser.parseString(
                SpyRelayCodec.encode(
                    commandMessage(now).copy(type = SpyRelayType.CHAT, targetName = "Target", targetUuid = null),
                ),
            ).asJsonObject.apply { addProperty("targetUuid", "not-a-uuid") }.toString()
        SpyRelayCodec.decode(invalidTarget, 4096, 1000) shouldBe null

        val control = SpyRelayCodec.encode(commandMessage(now).copy(content = "/say first\nsecond"))
        SpyRelayCodec.decode(control, 4096, 1000) shouldBe null
        SpyRelayCodec.decode(valid, 8, 1000) shouldBe null
        SpyRelayCodec.decode("[]", 4096, 1000) shouldBe null
    }

    "codec never interprets player markup" {
        val message = commandMessage(now).copy(content = "/msg Bob <click:run_command:'/op me'>hello</click>")
        val decoded = SpyRelayCodec.decode(SpyRelayCodec.encode(message), 4096, 1000)
        decoded shouldNotBe null
        decoded!!.content shouldBe message.content
    }

    "ingress accepts one fresh remote event and rejects echo, unknown origin, stale, future and replay" {
        val settings = testSettings()
        val ingress = SpyRelayIngress("spawn", settings)
        val message = commandMessage(now)
        val encoded = SpyRelayCodec.encode(message)

        ingress.accept("survival", encoded, now) shouldNotBe null
        ingress.accept("survival", encoded, now) shouldBe null
        ingress.accept("spawn", SpyRelayCodec.encode(message.copy(id = UUID.randomUUID())), now) shouldBe null
        ingress.accept("unknown", SpyRelayCodec.encode(message.copy(id = UUID.randomUUID())), now) shouldBe null
        ingress.accept(
            "parkour",
            SpyRelayCodec.encode(message.copy(id = UUID.randomUUID(), createdAt = now - settings.maxMessageAgeMillis - 1)),
            now,
        ) shouldBe null
        ingress.accept(
            "parkour",
            SpyRelayCodec.encode(message.copy(id = UUID.randomUUID(), createdAt = now + settings.maxFutureSkewMillis + 1)),
            now,
        ) shouldBe null
    }

    "content sanitizer removes controls before applying the bound" {
        SpyRelayCodec.sanitizeContent("  hello\nworld\u0000  ", 9) shouldBe "hello wor"
    }

    "configuration clamps bounds and preserves compound sensitive command rules" {
        val folder = Files.createTempDirectory("cross-server-spy-config")
        val config = ConfigManager.create(folder, "test.yml", "cross-server-spy-test")
        config.setInt("limits.max-payload-bytes", 99_999)
        config.setInt("limits.max-content-length", 1)
        config.setString("channel", "invalid channel")
        config.setStringList("security.sensitive-commands", listOf("CMI UserMeta", "bad\nrule"))

        val settings = CrossServerSpyConfig(config).settings
        settings.maxPayloadBytes shouldBe 16_384
        settings.maxContentLength shouldBe 64
        settings.channel shouldBe CrossServerSpyConfig.DEFAULT_CHANNEL
        settings.sensitiveCommands shouldBe setOf("cmi usermeta")
    }

    "renderer keeps remote content literal and identifies the source server" {
        val message =
            commandMessage(now).copy(
                type = SpyRelayType.CHAT,
                targetName = "Target",
                content = "<click:run_command:'/op me'>hello</click>",
            )
        val rendered = PlainTextComponentSerializer.plainText().serialize(SpyMessageRenderer.render(message, "Выживание"))
        rendered shouldBe "[Выживание] ЛС • SourcePlayer → Target: <click:run_command:'/op me'>hello</click>"
    }
})

private fun commandMessage(now: Long): SpyRelayMessage =
    SpyRelayMessage(
        id = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        type = SpyRelayType.COMMAND,
        senderUuid = UUID.fromString("20000000-0000-0000-0000-000000000002"),
        senderName = "SourcePlayer",
        targetUuid = null,
        targetName = null,
        content = "/warp market",
        createdAt = now,
    )

internal fun testSettings(): CrossServerSpySettings =
    CrossServerSpySettings(
        enabled = true,
        channel = "arc.spy.v1",
        allowedServers = setOf("spawn", "survival", "parkour"),
        serverLabels = mapOf("spawn" to "Спавн", "survival" to "Выживание", "parkour" to "Паркур"),
        maxPayloadBytes = 4096,
        maxContentLength = 1000,
        maxMessageAgeMillis = 15_000,
        maxFutureSkewMillis = 2000,
        privateMessageCommands = setOf("msg", "tell"),
        replyCommands = setOf("reply", "r"),
        sensitiveCommands = CrossServerSpyConfig.DEFAULT_SENSITIVE_COMMANDS,
    )
