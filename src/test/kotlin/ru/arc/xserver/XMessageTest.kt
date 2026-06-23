package ru.arc.xserver

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
        }
    })
