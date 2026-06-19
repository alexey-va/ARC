package ru.arc.ai

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Pure unit tests — no Bukkit dependency. */
class ChatHistoryTest : DescribeSpec({

    describe("ChatHistory") {

        describe("initial state") {
            it("should start with empty entries") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)

                history.entries().shouldBeEmpty()
            }

            it("should store playerUuid and maxLength") {
                val uuid = UUID.randomUUID()
                val history = ChatHistory(uuid, maxLength = 5)

                history.playerUuid shouldBe uuid
                history.maxLength shouldBe 5
            }
        }

        describe("addPlayerMessage") {
            it("should add a player entry") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("hello")

                val entries = history.entries().toList()
                entries shouldHaveSize 1
                entries[0].text shouldBe "hello"
                entries[0].isPlayer.shouldBeTrue()
            }

            it("should set a recent timestamp") {
                val before = System.currentTimeMillis()
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("hi")
                val after = System.currentTimeMillis()

                val ts = history.entries().first().timestamp
                (ts >= before && ts <= after).shouldBeTrue()
            }

            it("should accumulate multiple messages in order") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("first")
                history.addPlayerMessage("second")
                history.addPlayerMessage("third")

                val texts = history.entries().map { it.text }
                texts shouldBe listOf("first", "second", "third")
            }
        }

        describe("addBotMessage") {
            it("should add a bot entry with isPlayer = false") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addBotMessage("reply")

                val entries = history.entries().toList()
                entries shouldHaveSize 1
                entries[0].text shouldBe "reply"
                entries[0].isPlayer.shouldBeFalse()
            }

            it("should interleave player and bot messages correctly") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("question")
                history.addBotMessage("answer")
                history.addPlayerMessage("follow-up")

                val entries = history.entries().toList()
                entries shouldHaveSize 3
                entries[0].isPlayer.shouldBeTrue()
                entries[1].isPlayer.shouldBeFalse()
                entries[2].isPlayer.shouldBeTrue()
            }
        }

        describe("clean") {
            it("should remove entries older than cutoff") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("old")
                Thread.sleep(5)
                val cutoff = System.currentTimeMillis()
                history.addPlayerMessage("new")

                history.clean(cutoff)

                val remaining = history.entries().toList()
                remaining shouldHaveSize 1
                remaining[0].text shouldBe "new"
            }

            it("should not remove entries newer than cutoff") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("recent")

                history.clean(System.currentTimeMillis() - 10_000)

                history.entries() shouldHaveSize 1
            }

            it("should prune oldest when size exceeds maxLength") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 3)
                history.addPlayerMessage("a")
                history.addPlayerMessage("b")
                history.addPlayerMessage("c")
                history.addPlayerMessage("d")

                // clean with a distant past — only size constraint should trigger
                history.clean(0L)

                history.entries() shouldHaveSize 3
                history.entries().map { it.text } shouldBe listOf("b", "c", "d")
            }

            it("should handle empty history without errors") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 5)

                history.clean(System.currentTimeMillis())

                history.entries().shouldBeEmpty()
            }

            it("should clear all entries when all are older than cutoff") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 10)
                history.addPlayerMessage("msg1")
                history.addPlayerMessage("msg2")

                history.clean(System.currentTimeMillis() + 10_000)

                history.entries().shouldBeEmpty()
            }
        }

        describe("Entry data class") {
            it("should have correct properties") {
                val ts = 1_000_000L
                val entry = ChatHistory.Entry("text", isPlayer = true, timestamp = ts)

                entry.text shouldBe "text"
                entry.isPlayer shouldBe true
                entry.timestamp shouldBe ts
            }

            it("should support equality comparison") {
                val ts = 12345L
                val e1 = ChatHistory.Entry("hi", isPlayer = true, timestamp = ts)
                val e2 = ChatHistory.Entry("hi", isPlayer = true, timestamp = ts)

                e1 shouldBe e2
            }

            it("should differentiate player vs bot entries") {
                val playerEntry = ChatHistory.Entry("msg", isPlayer = true, timestamp = 0)
                val botEntry = ChatHistory.Entry("msg", isPlayer = false, timestamp = 0)

                (playerEntry == botEntry).shouldBeFalse()
            }
        }

        describe("concurrent safety") {
            it("should not throw when multiple threads write simultaneously") {
                val history = ChatHistory(UUID.randomUUID(), maxLength = 1000)
                val threads = (1..10).map { i ->
                    Thread { repeat(20) { history.addPlayerMessage("msg-$i-$it") } }
                }
                threads.forEach { it.start() }
                threads.forEach { it.join() }

                history.entries().size shouldBe 200
            }
        }
    }
})
