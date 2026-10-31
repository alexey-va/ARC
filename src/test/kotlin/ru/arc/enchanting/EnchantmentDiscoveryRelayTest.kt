package ru.arc.enchanting

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.core.TestTaskScheduler
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity

class EnchantmentDiscoveryRelayTest : FreeSpec({
    fun message(time: Long = 50_000L) = EnchantmentDiscoveryMessage(
        "survival", time,
        EnchantmentMiningDiscoveryState(listOf("survival"), listOf("STONE", "DEEPSLATE"), 0.05),
        EnchantmentWorldCacheDiscoveryState(listOf("survival"), 2_000),
        EnchantmentFarmingDiscoveryState(listOf("survival"), listOf("WHEAT", "CARROTS"), 0.05, 86_400L),
    )

    "fresh registered settings arrive only on the owning thread and detach immutable snapshots" {
        val fixture = DiscoveryRelayFixture()
        var localSupplierCalls = 0
        fixture.relay(
            mining = { localSupplierCalls++; error("A viewer cannot inspect local source settings") },
            worldCaches = { localSupplierCalls++; error("A viewer cannot inspect local source settings") },
        ).use { relay ->
            relay.snapshot() shouldBe null
            fixture.deliver(message(), execute = false)
            relay.snapshot() shouldBe null
            fixture.scheduler.executeImmediate()

            val observed = requireNotNull(relay.snapshot())
            observed.sentAtMillis shouldBe fixture.time
            val mining = requireNotNull(observed.mining)
            mining.survivalBackend shouldBe true
            mining.worldNames shouldBe setOf("survival")
            mining.materials shouldBe setOf(Material.STONE, Material.DEEPSLATE)
            mining.chancePercent shouldBe 0.05
            val caches = requireNotNull(observed.worldCaches)
            caches.survivalBackend shouldBe true
            caches.enabled shouldBe true
            caches.chanceDenominator shouldBe 2_000
            val farming = requireNotNull(observed.farming)
            farming.materials shouldBe setOf(Material.WHEAT, Material.CARROTS)
            farming.chancePercent shouldBe 0.05
            farming.cooldownSeconds shouldBe 86_400L
            shouldThrowAny { (mining.materials as MutableSet).clear() }
            shouldThrowAny { (caches.worldNames as MutableSet).clear() }
            localSupplierCalls shouldBe 0
            relay.publish()
            fixture.redis.getPublishedMessages() shouldBe emptyList()

            fixture.time++
            fixture.deliver(message(fixture.time).copy(mining = null, worldCaches = null, farming = null))
            relay.snapshot() shouldBe EnchantmentDiscoverySnapshot(fixture.time, null, null)
            fixture.deliver(message(fixture.time - 1L))
            fixture.deliver(message(fixture.time)) // Equal timestamps must not resurrect an inactive source.
            relay.snapshot() shouldBe EnchantmentDiscoverySnapshot(fixture.time, null, null)
        }
        fixture.redis.listenerCount(EnchantmentDiscoveryRelay.CHANNEL) shouldBe 0
        fixture.scheduler.timerCount() shouldBe 0
    }

    "expired future forged reordered and invalid observations fail closed without renewing the lease" {
        val fixture = DiscoveryRelayFixture()
        fixture.relay().use { relay ->
            fixture.deliver(message(fixture.time - EnchantmentDiscoveryRelay.STALE_MILLIS))
            fixture.deliver(message(fixture.time + EnchantmentDiscoveryRelay.MAX_FUTURE_SKEW_MILLIS + 1L))
            fixture.deliver(message(), origin = "spawn")
            fixture.deliverRaw(EnchantmentDiscoveryCodec.codec.encode(message()).replace("\"survival\"", "\"spawn\""))
            fixture.deliverRaw("{\"source\":\"survival\"}")
            fixture.deliverRaw(EnchantmentDiscoveryCodec.codec.encode(message()).replace("\"STONE\"", "\"NOT_A_MATERIAL\""))
            fixture.deliverRaw(EnchantmentDiscoveryCodec.codec.encode(message()).replace("\"STONE\"", "\"AIR\""))
            fixture.deliver(message().copy(farming = requireNotNull(message().farming).copy(materials = listOf("STONE"))))
            relay.snapshot() shouldBe null

            fixture.deliver(message())
            relay.snapshot()?.mining?.chancePercent shouldBe 0.05
            fixture.time += 10_000L
            fixture.deliver(message(49_999L).copy(mining = null, worldCaches = null, farming = null))
            fixture.deliverRaw("{}")
            relay.snapshot()?.sentAtMillis shouldBe 50_000L

            fixture.time = 70_000L
            relay.snapshot() shouldBe null
            // The high-water mark survives expiry; an equal or older status cannot renew the lease.
            fixture.time = 50_100L
            fixture.deliver(message())
            relay.snapshot() shouldBe null
            fixture.deliver(message(fixture.time))
            relay.snapshot()?.sentAtMillis shouldBe fixture.time
        }
    }

    "the documented five-second clock skew is bounded and expiry uses the sender timestamp" {
        val fixture = DiscoveryRelayFixture()
        fixture.relay().use { relay ->
            val ahead = fixture.time + EnchantmentDiscoveryRelay.MAX_FUTURE_SKEW_MILLIS
            fixture.deliver(message(ahead))
            relay.snapshot()?.sentAtMillis shouldBe ahead
            fixture.time = ahead + EnchantmentDiscoveryRelay.STALE_MILLIS - 1L
            relay.snapshot()?.sentAtMillis shouldBe ahead
            fixture.time++
            relay.snapshot() shouldBe null
        }
    }

    "Survival publishes live suppliers heartbeat and one inactive shutdown while reload preserves order" {
        val source = DiscoveryRelayFixture("survival")
        val viewer = DiscoveryRelayFixture()
        val mutableWorlds = mutableSetOf("survival")
        var mining: EnchantmentMiningLootSettings? = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 0.05)
        var worldCaches: EnchantmentWorldCacheSettings? = EnchantmentWorldCacheSettings(true, true, mutableWorlds, 2_000)
        val farming = EnchantmentFarmingLootSettings(true, setOf("survival"), setOf(Material.WHEAT), 0.05, 86_400L)
        val original = source.relay(mining = { mining }, worldCaches = { worldCaches }, farming = { farming })
        viewer.relay().use { receiver ->
            try {
                source.redis.getPublishedMessages() shouldBe emptyList()
                source.scheduler.executeImmediate()
                val first = source.lastPublished()
                viewer.deliver(first)
                receiver.snapshot()?.mining?.chancePercent shouldBe 0.05
                receiver.snapshot()?.farming?.cooldownSeconds shouldBe 86_400L
                val detached = requireNotNull(original.snapshot())
                mutableWorlds.clear()
                requireNotNull(detached.worldCaches).worldNames shouldBe setOf("survival")

                mining = EnchantmentMiningLootSettings(true, setOf("survival"), setOf(Material.STONE), 0.125)
                worldCaches = null
                source.time += 5_000L
                source.scheduler.tick(100L)
                source.redis.getPublishedMessages().size shouldBe 2
                source.lastPublished().mining?.chancePercent shouldBe 0.125
                source.lastPublished().worldCaches shouldBe null

                original.close()
                val stop = source.lastPublished()
                stop.mining shouldBe null
                stop.worldCaches shouldBe null
                stop.farming shouldBe null
                source.scheduler.timerCount() shouldBe 0
                source.redis.listenerCount(EnchantmentDiscoveryRelay.CHANNEL) shouldBe 0
                original.close()
                source.redis.getPublishedMessages().size shouldBe 3

                source.relay(mining = { mining }, initialLastSentAt = original.lastPublishedAt).use { replacement ->
                    source.scheduler.executeImmediate()
                    val active = source.lastPublished()
                    (active.sentAtMillis > stop.sentAtMillis) shouldBe true
                    viewer.time = source.time
                    viewer.deliver(active)
                    viewer.deliver(stop)
                    receiver.snapshot()?.mining?.chancePercent shouldBe 0.125
                    replacement.lastPublishedAt shouldBe active.sentAtMillis
                }
            } finally { original.close() }
        }
        source.scheduler.timerCount() shouldBe 0
        viewer.scheduler.timerCount() shouldBe 0
    }

    "closing fences queued delivery and a new lifecycle starts unknown" {
        val fixture = DiscoveryRelayFixture()
        val relay = fixture.relay()
        fixture.deliver(message(), execute = false)
        fixture.scheduler.pendingCount() shouldBe 1
        relay.close()
        fixture.scheduler.executeImmediate()
        relay.snapshot() shouldBe null
        fixture.scheduler.pendingCount() shouldBe 0
        fixture.relay().use { replacement ->
            replacement.snapshot() shouldBe null
            fixture.deliver(message())
            replacement.snapshot()?.sentAtMillis shouldBe fixture.time
        }
        fixture.redis.listenerCount(EnchantmentDiscoveryRelay.CHANNEL) shouldBe 0
    }

    "an unavailable source capture stays unknown and heartbeat recovers with a fresh inactive status" {
        val fixture = DiscoveryRelayFixture("survival")
        var available = false
        fixture.relay(mining = { check(available) { "Source runtime unavailable" }; null }).use { relay ->
            fixture.scheduler.executeImmediate()
            fixture.redis.getPublishedMessages() shouldBe emptyList()
            relay.snapshot() shouldBe null
            available = true
            fixture.time += 5_000L
            fixture.scheduler.tick(100L)
            fixture.lastPublished().mining shouldBe null
            fixture.lastPublished().worldCaches shouldBe null
            relay.snapshot() shouldBe EnchantmentDiscoverySnapshot(fixture.time, null, null)
        }
        fixture.scheduler.timerCount() shouldBe 0
        fixture.redis.listenerCount(EnchantmentDiscoveryRelay.CHANNEL) shouldBe 0
    }

    "the wire contract rejects coercions non-finite chances unknown fields and resource overflows" {
        val valid = message()
        val codec = EnchantmentDiscoveryCodec.codec
        val encoded = codec.encode(valid)
        codec.decode(encoded) shouldBe valid
        for (raw in listOf(
            encoded.dropLast(1) + ",\"command\":\"stop\"}",
            encoded.replace("\"chancePercent\":0.05", "\"chancePercent\":\"0.05\""),
            encoded.replace("\"chancePercent\":0.05", "\"chancePercent\":1e999"),
            encoded.replace("\"chanceDenominator\":2000", "\"chanceDenominator\":0"),
            encoded.replace("\"chanceDenominator\":2000", "\"chanceDenominator\":2000.5"),
            encoded.replace("\"chanceDenominator\":2000", "\"chanceDenominator\":4294967297"),
            encoded.replace("\"cooldownSeconds\":86400", "\"cooldownSeconds\":0"),
            encoded.replace("\"cooldownSeconds\":86400", "\"cooldownSeconds\":31536001"),
            encoded.replace("\"sentAtMillis\":50000", "\"sentAtMillis\":\"50000\""),
            encoded.replace("\"worldNames\":[\"survival\"]", "\"worldNames\":[\"survival\",\"survival\"]"),
        )) shouldThrowAny { codec.decode(raw) }
        for (chance in listOf(-0.1, 100.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            shouldThrowAny { codec.encode(valid.copy(mining = requireNotNull(valid.mining).copy(chancePercent = chance))) }
        }
        shouldThrowAny { codec.encode(valid.copy(source = "spawn")) }
        shouldThrowAny { codec.encode(valid.copy(mining = requireNotNull(valid.mining).copy(worldNames = List(33) { "world$it" }))) }
        shouldThrowAny { codec.encode(valid.copy(mining = requireNotNull(valid.mining).copy(materials = List(257) { "BLOCK_$it" }))) }
        shouldThrowAny { codec.encode(valid.copy(worldCaches = requireNotNull(valid.worldCaches).copy(worldNames = listOf("w".repeat(129))))) }
    }
})

private class DiscoveryRelayFixture(val server: String = "spawn") {
    var time = 50_000L
    val scheduler = TestTaskScheduler()
    val redis = InMemoryRedis(ServerIdentity { server })

    fun relay(
        mining: () -> EnchantmentMiningLootSettings? = { null },
        worldCaches: () -> EnchantmentWorldCacheSettings? = { null },
        initialLastSentAt: Long = 0L,
        farming: () -> EnchantmentFarmingLootSettings? = { null },
    ) = EnchantmentDiscoveryRelay(redis, scheduler, server, mining, worldCaches, { time }, initialLastSentAt, farming,
        isFarmingMaterial = { it == Material.WHEAT || it == Material.CARROTS })

    fun deliver(message: EnchantmentDiscoveryMessage, origin: String = "survival", execute: Boolean = true) =
        deliverRaw(EnchantmentDiscoveryCodec.codec.encode(message), origin, execute)

    fun deliverRaw(raw: String, origin: String = "survival", execute: Boolean = true) {
        redis.simulateExternalMessage(EnchantmentDiscoveryRelay.CHANNEL, raw, origin)
        if (execute) scheduler.executeImmediate()
    }

    fun lastPublished(): EnchantmentDiscoveryMessage = EnchantmentDiscoveryCodec.codec.decode(redis.getPublishedMessages().last().message)
}
