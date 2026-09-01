package ru.arc.spy

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.SignedMessage
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.chat.ChatMode
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations

class CrossServerSpyBridgeTest :
    FreeSpec({
        "a non-cancelled local chat message is published for remote SocialSpy viewers" {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val player = paper.addPlayer("LocalPlayer")
                    val redis = RecordingRedis()
                    CrossServerSpyBridge(
                        plugin = paper.createSimplePlugin("SpyBridgeTest"),
                        redis = redis,
                        localServer = "spawn",
                        settings = testSettings(),
                        cmi = VisibleSpyState,
                        now = { 1_780_000_000_000L },
                    ).use { bridge ->
                        bridge.start()

                        paper.callEvent(chatEvent(player, "рядом с кузницей"))

                        redis.publications shouldHaveSize 1
                        val (channel, payload) = redis.publications.single()
                        channel shouldBe "arc.spy.v1"
                        val decoded = requireNotNull(SpyRelayCodec.decode(payload, 4096, 1000))
                        decoded shouldBe
                            SpyRelayMessage(
                                id = decoded.id,
                                type = SpyRelayType.CHAT,
                                senderUuid = player.uniqueId,
                                senderName = "LocalPlayer",
                                targetUuid = null,
                                targetName = null,
                                content = "рядом с кузницей",
                                createdAt = 1_780_000_000_000L,
                            )
                    }
                }
            }
        }

        "a shout-prefixed chat message is not published as local chat" {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val redis = RecordingRedis()
                    CrossServerSpyBridge(
                        plugin = paper.createSimplePlugin("SpyBridgeTest"),
                        redis = redis,
                        localServer = "spawn",
                        settings = testSettings(),
                        cmi = VisibleSpyState,
                        now = { 1_780_000_000_000L },
                    ).use { bridge ->
                        bridge.start()

                        paper.callEvent(chatEvent(paper.addPlayer("GlobalPlayer"), "!всем привет"))

                        redis.publications shouldHaveSize 0
                    }
                }
            }
        }

        "chat from a player in global mode is not published as local chat" {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val redis = RecordingRedis()
                    CrossServerSpyBridge(
                        plugin = paper.createSimplePlugin("SpyBridgeTest"),
                        redis = redis,
                        localServer = "spawn",
                        settings = testSettings(),
                        cmi = VisibleSpyState,
                        now = { 1_780_000_000_000L },
                        chatMode = { ChatMode.GLOBAL },
                    ).use { bridge ->
                        bridge.start()

                        paper.callEvent(chatEvent(paper.addPlayer("GlobalPlayer"), "всем привет"))

                        redis.publications shouldHaveSize 0
                    }
                }
            }
        }

        "local chat publishes the final filtered message instead of the original input" {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val player = paper.addPlayer("FilteredPlayer")
                    val redis = RecordingRedis()
                    CrossServerSpyBridge(
                        plugin = paper.createSimplePlugin("SpyBridgeTest"),
                        redis = redis,
                        localServer = "spawn",
                        settings = testSettings(),
                        cmi = VisibleSpyState,
                        now = { 1_780_000_000_000L },
                    ).use { bridge ->
                        bridge.start()

                        paper.callEvent(
                            chatEvent(
                                player = player,
                                message = "сообщение прошло фильтр",
                                originalMessage = "неотфильтрованный ввод",
                            ),
                        )

                        redis.publications shouldHaveSize 1
                        SpyRelayCodec.decode(redis.publications.single().second, 4096, 1000)!!.content shouldBe
                            "сообщение прошло фильтр"
                    }
                }
            }
        }

        "asynchronous chat marshals CMI state access to the main scheduler" {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val scheduler = TestTaskScheduler()
                    Tasks.withScheduler(scheduler) {
                        val redis = RecordingRedis()
                        CrossServerSpyBridge(
                            plugin = paper.createSimplePlugin("SpyBridgeTest"),
                            redis = redis,
                            localServer = "spawn",
                            settings = testSettings(),
                            cmi = VisibleSpyState,
                            now = { 1_780_000_000_000L },
                        ).use { bridge ->
                            bridge.start()

                            bridge.onPlayerChat(
                                chatEvent(
                                    player = paper.addPlayer("AsyncPlayer"),
                                    message = "асинхронный чат",
                                    asynchronous = true,
                                ),
                            )

                            redis.publications shouldHaveSize 0
                            scheduler.executeImmediate()
                            redis.publications shouldHaveSize 1
                        }
                    }
                }
            }
        }
    })

private fun chatEvent(
    player: Player,
    message: String,
    originalMessage: String = message,
    asynchronous: Boolean = false,
): AsyncChatEvent {
    val component = Component.text(message)
    return AsyncChatEvent(
        asynchronous,
        player,
        mutableSetOf<Audience>(player),
        ChatRenderer.defaultRenderer(),
        component,
        Component.text(originalMessage),
        mockk<SignedMessage>(),
    )
}

private class RecordingRedis(
    private val delegate: InMemoryRedis = InMemoryRedis(),
) : RedisOperations by delegate {
    val publications = mutableListOf<Pair<String, String>>()

    override fun publish(channel: String, message: String) {
        publications += channel to message
    }
}

private object VisibleSpyState : SpyStateAccess {
    override fun isChatSpy(player: Player): Boolean = true

    override fun isCommandSpy(player: Player): Boolean = true

    override fun senderHidesChatSpy(player: Player): Boolean = false

    override fun senderHidesCommandSpy(player: Player): Boolean = false

    override fun canSeeUnlistedCommands(player: Player): Boolean = true

    override fun commandBlacklist(): List<String> = emptyList()

    override fun commandList(): List<String> = emptyList()

    override fun replyTarget(playerName: String): String? = null

    override fun targetUuid(playerName: String): java.util.UUID? = null
}
