package ru.arc.ai

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

/** Pure unit tests for the Conversation data class. */
class ConversationTest : DescribeSpec({

    describe("Conversation") {

        describe("defaults") {
            it("should have sensible defaults") {
                val conv = Conversation()

                conv.playerUuid shouldBe null
                conv.location shouldBe null
                conv.radius shouldBe 0.0
                conv.gptId shouldBe null
                conv.archetype shouldBe null
                conv.lastMessageTime shouldBe 0L
                conv.lifeTime shouldBe 0L
                conv.talkerName shouldBe null
                conv.npcId shouldBe null
                conv.endMessage shouldBe null
                conv.privateConversation shouldBe true
            }
        }

        describe("construction with values") {
            it("should store all provided values") {
                val uuid = UUID.randomUUID()
                val conv = Conversation(
                    playerUuid = uuid,
                    radius = 10.0,
                    gptId = "npc-1",
                    archetype = "friendly",
                    lastMessageTime = 999L,
                    lifeTime = 60_000L,
                    talkerName = "Bob",
                    npcId = 42,
                    endMessage = "Goodbye!",
                    privateConversation = false
                )

                conv.playerUuid shouldBe uuid
                conv.radius shouldBe 10.0
                conv.gptId shouldBe "npc-1"
                conv.archetype shouldBe "friendly"
                conv.lastMessageTime shouldBe 999L
                conv.lifeTime shouldBe 60_000L
                conv.talkerName shouldBe "Bob"
                conv.npcId shouldBe 42
                conv.endMessage shouldBe "Goodbye!"
                conv.privateConversation shouldBe false
            }
        }

        describe("mutability") {
            it("should allow updating lastMessageTime") {
                val conv = Conversation(lastMessageTime = 0L)

                conv.lastMessageTime = 12345L

                conv.lastMessageTime shouldBe 12345L
            }
        }

        describe("copy semantics") {
            it("should create independent copy via copy()") {
                val original = Conversation(gptId = "entity-1", radius = 5.0)
                val copy = original.copy(radius = 20.0)

                copy.radius shouldBe 20.0
                copy.gptId shouldBe "entity-1"
                original.radius shouldBe 5.0 // unchanged
            }
        }

        describe("equality") {
            it("should be equal when all fields match") {
                val uuid = UUID.randomUUID()
                val c1 = Conversation(playerUuid = uuid, gptId = "x", lifeTime = 100L)
                val c2 = Conversation(playerUuid = uuid, gptId = "x", lifeTime = 100L)

                c1 shouldBe c2
            }

            it("should differ when any field differs") {
                val c1 = Conversation(gptId = "a")
                val c2 = Conversation(gptId = "b")

                (c1 == c2) shouldBe false
                c1 shouldNotBe c2
            }
        }
    }
})
