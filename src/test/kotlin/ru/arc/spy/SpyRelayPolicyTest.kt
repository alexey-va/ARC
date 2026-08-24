package ru.arc.spy

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class SpyRelayPolicyTest : FreeSpec({
    "command relay fails closed for hidden senders and every sensitive prefix" {
        SpyRelayPolicy.shouldPublishCommand(
            command = "/warp market",
            senderHidden = true,
            cmiBlacklisted = emptyList(),
            sensitiveCommands = emptyList(),
        ) shouldBe false

        listOf("/login secret", "/minecraft:register hunter2", "/cmi usermeta Alex token").forEach { command ->
            SpyRelayPolicy.shouldPublishCommand(
                command = command,
                senderHidden = false,
                cmiBlacklisted = listOf("cmi usermeta"),
                sensitiveCommands = setOf("login", "register"),
            ) shouldBe false
        }
        SpyRelayPolicy.shouldPublishCommand(
            command = "/warp market",
            senderHidden = false,
            cmiBlacklisted = listOf("login"),
            sensitiveCommands = setOf("register"),
        ) shouldBe true
    }

    "CMI CommandList is prefix-aware for restricted viewers" {
        val commandList = listOf("cmi spawn", "cmi tp")
        SpyRelayPolicy.commandVisibleToRestrictedViewer("/cmi spawn", commandList) shouldBe true
        SpyRelayPolicy.commandVisibleToRestrictedViewer("/cmi tp Alex", commandList) shouldBe true
        SpyRelayPolicy.commandVisibleToRestrictedViewer("/cmi tpa Alex", commandList) shouldBe false
        SpyRelayPolicy.commandVisibleToRestrictedViewer("/warp market", commandList) shouldBe false
    }

    "private-message parser understands direct, namespaced CMI and reply commands" {
        val direct = setOf("msg", "tell")
        val replies = setOf("reply", "r")
        SpyRelayPolicy.parsePrivateMessage("/msg Bob hello there", direct, replies) { null } shouldBe
            PrivateSpyMessage("Bob", "hello there")
        SpyRelayPolicy.parsePrivateMessage("/cmi msg Bob !clean text", direct, replies) { null } shouldBe
            PrivateSpyMessage("Bob", "clean text")
        SpyRelayPolicy.parsePrivateMessage("/msg Bob !!!literal", direct, replies) { null } shouldBe
            PrivateSpyMessage("Bob", "!literal")
        SpyRelayPolicy.parsePrivateMessage("/cmi:cmi reply answer", direct, replies) { "Bob" } shouldBe
            PrivateSpyMessage("Bob", "answer")
        SpyRelayPolicy.parsePrivateMessage("/r answer", direct, replies) { null } shouldBe null
        SpyRelayPolicy.parsePrivateMessage("/msg bad-name! hello", direct, replies) { null } shouldBe null
        SpyRelayPolicy.parsePrivateMessage("/warp Bob", direct, replies) { null } shouldBe null
    }

    "recipient policy requires the matching live CMI state and excludes sender and PM target" {
        val sender = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val target = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val viewer = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val chat =
            SpyRelayMessage(
                id = UUID.randomUUID(),
                type = SpyRelayType.CHAT,
                senderUuid = sender,
                senderName = "Sender",
                targetUuid = target,
                targetName = "Target",
                content = "hello",
                createdAt = 1,
            )
        SpyRelayPolicy.shouldDeliver(chat, viewer, "Viewer", true, false, false, emptyList()) shouldBe true
        SpyRelayPolicy.shouldDeliver(chat, viewer, "Viewer", false, true, true, emptyList()) shouldBe false
        SpyRelayPolicy.shouldDeliver(chat, sender, "Sender", true, false, false, emptyList()) shouldBe false
        SpyRelayPolicy.shouldDeliver(chat, target, "Target", true, false, false, emptyList()) shouldBe false
        SpyRelayPolicy.shouldDeliver(chat, viewer, "Target", true, false, false, emptyList()) shouldBe false

        val command = chat.copy(type = SpyRelayType.COMMAND, targetUuid = null, targetName = null, content = "/warp market")
        SpyRelayPolicy.shouldDeliver(command, viewer, "Viewer", false, true, false, listOf("cmi spawn")) shouldBe false
        SpyRelayPolicy.shouldDeliver(command, viewer, "Viewer", false, true, true, listOf("cmi spawn")) shouldBe true
        SpyRelayPolicy.shouldDeliver(command.copy(content = "/cmi spawn"), viewer, "Viewer", false, true, false, listOf("cmi spawn")) shouldBe true
    }
})
