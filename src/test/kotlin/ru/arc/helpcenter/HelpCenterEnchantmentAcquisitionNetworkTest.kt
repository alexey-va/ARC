package ru.arc.helpcenter

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.TestTaskScheduler
import ru.arc.helpcenter.HelpCenterEnchantmentAcquisitionWire as Wire
import ru.arc.itemcatalog.RewardCatalogSource
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.treasure.core.AeKind
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HelpCenterEnchantmentAcquisitionNetworkTest : FreeSpec({
    "source callback resolves its fresh catalogue on Paper and remote receives final facts despite a divergent local catalogue" {
        MockBukkitTestRuntime.open().use {
            val sourceRedis = InMemoryRedis(ServerIdentity { "spawn" })
            val remoteRedis = InMemoryRedis(ServerIdentity { "survival" })
            val sourceTasks = TestTaskScheduler()
            val remoteTasks = TestTaskScheduler()
            val localFacts = catalogueFacts(catalogueSnapshot(9, 3))
            var current = catalogueSnapshot(4, 1)
            var captures = 0
            network(sourceRedis, sourceTasks, "spawn", capture = { topic ->
                Bukkit.isPrimaryThread() shouldBe true
                topic shouldBe "magic"
                captures++
                CompletableFuture.completedFuture(catalogueFacts(current))
            }).use {
                network(remoteRedis, remoteTasks, "survival", capture = { error("Remote must not resolve source catalogue references") }).use { remote ->
                    repeat(2) { index ->
                        val pending = remote.describe("magic")
                        remoteTasks.executeImmediate()
                        sourceRedis.simulateExternalMessage(Wire.CHANNEL, remoteRedis.getPublishedMessages().last().message, "survival")
                        captures shouldBe index
                        sourceTasks.executeImmediate()
                        captures shouldBe index + 1
                        sourceTasks.executeImmediate()
                        remoteRedis.simulateExternalMessage(Wire.CHANNEL, sourceRedis.getPublishedMessages().last().message, "spawn")
                        val received = pending.get(1, TimeUnit.SECONDS)!!
                        received shouldBe catalogueFacts(current)
                        (received == localFacts) shouldBe false
                        received.facts.single().values["amount"] shouldBe if (index == 0) "4" else "6"
                        received.facts.single().values["chance"] shouldBe if (index == 0) "12,5" else "25"
                        current = catalogueSnapshot(6, 2)
                    }
                    localFacts.facts.single().values["amount"] shouldBe "9"
                    localFacts.facts.single().values["chance"] shouldBe "37,5"
                }
            }
        }
    }

    "exact request reply source target topic and timestamp are required before consuming pending state" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        network(redis, tasks, "survival").use { remote ->
            val pending = remote.describe("magic")
            tasks.executeImmediate()
            val request = Wire.codec.decode(redis.getPublishedMessages().single().message)
            fun reply() = request.result(catalogueFacts(catalogueSnapshot(4, 1)))
            fun rejected(message: HelpCenterEnchantmentAcquisitionMessage, origin: String = "spawn") {
                redis.simulateExternalMessage(Wire.CHANNEL, Wire.codec.encode(message), origin)
                pending.isDone shouldBe false
            }
            rejected(reply(), "survival")
            rejected(reply().copy(targetServer = "parkour"))
            rejected(reply().copy(replyTo = UUID.randomUUID().toString()))
            rejected(reply().copy(topicId = "whitescroll", facts = emptyList()))
            rejected(reply().copy(createdAtMillis = request.createdAtMillis - 1))
            redis.simulateExternalMessage(Wire.CHANNEL, Wire.codec.encode(reply()), "spawn")
            pending.get(1, TimeUnit.SECONDS) shouldBe catalogueFacts(catalogueSnapshot(4, 1))
        }
    }

    "source ignores replayed spoofed and expired requests and cannot publish after close" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val tasks = TestTaskScheduler()
        var captures = 0
        val capture = CompletableFuture<HelpCenterEnchantmentAcquisition>()
        val source = network(redis, tasks, "spawn", capture = { captures++; capture })
        val request = query()
        val raw = Wire.codec.encode(request)
        redis.simulateExternalMessage(Wire.CHANNEL, raw, "parkour")
        redis.simulateExternalMessage(Wire.CHANNEL, Wire.codec.encode(query().copy(createdAtMillis = 0L)), "survival")
        redis.simulateExternalMessage(Wire.CHANNEL, raw, "survival")
        redis.simulateExternalMessage(Wire.CHANNEL, raw, "survival")
        captures shouldBe 0
        tasks.executeImmediate()
        captures shouldBe 1
        source.close()
        capture.complete(catalogueFacts(catalogueSnapshot(4, 1)))
        tasks.executeImmediate()
        redis.getPublishedMessages() shouldBe emptyList()
        redis.listenerCount(Wire.CHANNEL) shouldBe 0
    }

    "timeout and close retain known local facts and bound queued requests before publication" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        val timeouts = HelpTimeouts()
        val remote = network(redis, tasks, "survival", timeouts = timeouts)
        val known = HelpCenterEnchantmentAcquisition(listOf(HelpCenterEnchantmentSourceFact("natural-dust", mapOf("kill" to "1,25"))))
        val merged = HelpCenterEnchantmentSources.withExternal(known, {
            remote.describe("magic").thenApply { it ?: HelpCenterEnchantmentAcquisition(listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "crate-rewards")))) }
        }, { error("Expected unknown remote state, not an exception") })
        tasks.executeImmediate()
        timeouts.fire()
        merged.get(1, TimeUnit.SECONDS).facts.first() shouldBe known.facts.single()
        merged.join().facts.any { it.key == "crate-source" } shouldBe false
        val queued = List(32) { remote.describe("magic") }
        remote.describe("magic").get(1, TimeUnit.SECONDS) shouldBe null
        remote.close()
        remote.close()
        queued.forEach { it.get(1, TimeUnit.SECONDS) shouldBe null }
        tasks.executeImmediate()
        redis.getPublishedMessages().size shouldBe 1
        redis.listenerCount(Wire.CHANNEL) shouldBe 0
        remote.describe("magic").get(1, TimeUnit.SECONDS) shouldBe null
    }

    "pending published request closes and a fresh lifecycle can answer the next request" {
        val redis = InMemoryRedis(ServerIdentity { "survival" })
        val tasks = TestTaskScheduler()
        val first = network(redis, tasks, "survival")
        val abandoned = first.describe("magic")
        tasks.executeImmediate()
        first.close()
        abandoned.get(1, TimeUnit.SECONDS) shouldBe null
        network(redis, tasks, "survival").use { replacement ->
            val pending = replacement.describe("magic")
            tasks.executeImmediate()
            val request = Wire.codec.decode(redis.getPublishedMessages().last().message)
            redis.simulateExternalMessage(Wire.CHANNEL, Wire.codec.encode(request.result(catalogueFacts(catalogueSnapshot(4, 1)))), "spawn")
            pending.get(1, TimeUnit.SECONDS) shouldBe catalogueFacts(catalogueSnapshot(4, 1))
        }
    }

    "wire is closed to local drop facts malformed metadata noncanonical chances and oversized values" {
        val reply = query().result(catalogueFacts(catalogueSnapshot(4, 1)))
        val raw = Wire.codec.encode(reply)
        Wire.codec.decode(raw) shouldBe reply
        val malformed = listOf(
            raw + "{}", raw.replace("\"chance\":\"12,5\"", "\"chance\":[\"12,5\"]"),
            raw.replace("\"chance\":\"12,5\"", "\"chance\":12.5"),
            raw.replace("\"chance\":\"12,5\"", "\"chance\":null"),
            raw.replace("\"chance\":\"12,5\"", "\"chance\":\"NaN\""),
            raw.replace("\"chance\":\"12,5\"", "\"chance\":\"12.5\""),
            raw.replace("\"chance\":\"12,5\"", "\"chance\":\"101\""),
            raw.replace("\"amount\":\"4\"", "\"amount\":\"0\""),
            raw.replace("\"amount\":\"4\"", "\"amount\":\"4–2\""),
            raw.replace("\"crate-source\"", "\"natural-books\""),
            raw.replace("\"source\":", "\"player\":\"forbidden\",\"source\":"),
            raw.replace("\"crate\":\"Кейс\"", "\"crate\":\"${"x".repeat(257)}\""),
        )
        malformed.forEach { value -> runCatching { Wire.codec.decode(value) }.isFailure shouldBe true }
        runCatching { Wire.codec.encode(reply.copy(topicId = "not-a-guide-topic")) }.isFailure shouldBe true
        runCatching { Wire.codec.encode(reply.copy(facts = List(2049) { reply.facts.single() })) }.isFailure shouldBe true
        runCatching { Wire.codec.encode(reply.copy(id = "not-a-uuid")) }.isFailure shouldBe true
    }

    "overview preserves only source box links and quest metadata without inventing a chance" {
        val facts = HelpCenterEnchantmentAcquisition(listOf(
            HelpCenterEnchantmentSourceFact("crate-source", mapOf("source" to "case_daily", "item" to "ae_lootbox", "amount" to "1–3", "crate" to "Кейс", "chance" to "0,0001")),
            HelpCenterEnchantmentSourceFact("quest-source", mapOf("source" to "daily", "item" to "enchant_supply", "amount" to "1", "quest" to "Задание")),
            HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "item-presets")),
        ), listOf("ae_lootbox", "enchant_supply"))
        val reply = query().copy(topicId = "overview").result(facts)
        val received = Wire.codec.decode(Wire.codec.encode(reply))
        received.facts shouldBe facts.facts
        received.relatedTopics shouldBe facts.relatedTopics
        runCatching { Wire.codec.encode(reply.copy(relatedTopics = listOf("magic"))) }.isFailure shouldBe true
    }
})

private class HelpTimeouts : RedisRequestTimeoutScheduler {
    private val pending = linkedSetOf<() -> Unit>()
    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        delayMillis shouldBe 10_000L
        pending += action
        return RedisRequestTimeoutHandle { pending.remove(action) }
    }
    fun fire() { pending.toList().also { pending.clear() }.forEach { it() } }
}

private fun network(
    redis: InMemoryRedis, tasks: TestTaskScheduler, server: String,
    capture: (String) -> CompletableFuture<HelpCenterEnchantmentAcquisition> = { error("Remote must not capture local sources") },
    timeouts: HelpTimeouts = HelpTimeouts(),
) = HelpCenterEnchantmentAcquisitionNetwork(redis, server, capture, { 10_001L }, tasks, timeouts)

private fun query() = HelpCenterEnchantmentAcquisitionMessage(
    id = UUID.randomUUID().toString(), type = "QUERY", originServer = "survival", targetServer = "spawn", topicId = "magic", createdAtMillis = 10_001L,
)

private fun HelpCenterEnchantmentAcquisitionMessage.result(acquisition: HelpCenterEnchantmentAcquisition) = copy(
    id = UUID.randomUUID().toString(), replyTo = id, type = "RESULT", originServer = targetServer, targetServer = originServer,
    facts = acquisition.facts, relatedTopics = acquisition.relatedTopics,
)

private fun catalogueSnapshot(amount: Int, weight: Int) = HelpCenterEnchantmentSourceSnapshot(listOf(
    TreasurePool("catalogue", listOf(Treasure.SubPool("contents", id = "same_recipe"))),
    TreasurePool("contents", listOf(Treasure.Ae(AeKind.ITEM, "magic", amount = amount, weight = weight), Treasure.Money(weight = 4 - weight))),
), emptyList(), null)

private fun catalogueFacts(snapshot: HelpCenterEnchantmentSourceSnapshot) = HelpCenterEnchantmentExternalSources.summarize("magic", listOf(
    HelpCenterExternalEnchantmentReward("crate", "case_daily", "Кейс", 0.5,
        HelpCenterEnchantmentSources.rewardOutcomes(RewardCatalogSource.Treasure("catalogue", "same_recipe"), snapshot)),
))
