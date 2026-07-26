package ru.arc.xserver

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import ru.arc.KotestTestBase

class XMessageTest :
    KotestTestBase({

        describe("XMessage") {

            it("should detect blank chat content") {
                val blank =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "   ",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                    )
                val player = server.addPlayer("blanktest")

                blank.hasVisibleContent(player) shouldBe false
                blank.skipReason(player) shouldBe "resolved-empty"
            }

            it("should detect visible chat content") {
                val message =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "<gray>Hello",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                    )
                val player = server.addPlayer("visibletest")

                message.hasVisibleContent(player) shouldBe true
            }

            it("should filter by target server") {
                val spawnOnly =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "<gray>spawn tip",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                        announceData = XMessage.AnnounceData(weight = 1, targetServers = setOf("spawn")),
                    )
                val all =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "<gray>all tip",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                        announceData = XMessage.AnnounceData(weight = 1),
                    )

                spawnOnly.appliesToServer("spawn") shouldBe true
                spawnOnly.appliesToServer("survival") shouldBe false
                all.appliesToServer("survival") shouldBe true
            }

            it("should treat an explicit empty target set as no servers") {
                val disabled =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "<gray>disabled tip",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                        announceData = XMessage.AnnounceData(weight = 1, targetServers = emptySet()),
                    )

                disabled.appliesToServer("spawn") shouldBe false
            }

            it("should format log summary with text") {
                val message =
                    XMessage(
                        type = XMessage.Type.CHAT,
                        serializedMessage = "<gray>/quest",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                        announceData = XMessage.AnnounceData(weight = 3),
                    )

                message.logSummary() shouldContain "type=CHAT"
                message.logSummary() shouldContain "weight=3"
                message.logSummary() shouldContain "/quest"
            }

            it("should deliver an action bar without requiring CMI") {
                val player =
                    mockk<Player>(relaxed = true) {
                        every { name } returns "actionbar-test"
                    }
                val message =
                    XMessage(
                        type = XMessage.Type.ACTION_BAR,
                        serializedMessage = "<green>Ready",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                    )

                message.deliverTo(player)

                verify(exactly = 1) { player.sendActionBar(any<Component>()) }
            }

            it("should deliver a title with configured timing") {
                val player =
                    mockk<Player>(relaxed = true) {
                        every { name } returns "title-test"
                    }
                val message =
                    XMessage(
                        type = XMessage.Type.TITLE,
                        serializedMessage = "<gold>Treasure",
                        serializationType = XMessage.SerializationType.MINI_MESSAGE,
                        titleData =
                            XMessage.TitleData(
                                subtitle = "<gray>Found",
                                fadeInTicks = 5,
                                stayTicks = 40,
                                fadeOutTicks = 10,
                            ),
                    )

                message.deliverTo(player)

                verify(exactly = 1) { player.showTitle(any<Title>()) }
            }
        }
    })
