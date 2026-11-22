package ru.arc.onboarding

import com.google.gson.Gson
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OptionalQuestStoreTest : FreeSpec({
    "each unfinished attempt expires exactly 24 wall-clock hours after acceptance and can be retaken" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val farm = OptionalQuestKind.FARM
        val start = 1_800_000_000_000L
        val day = TimeUnit.DAYS.toMillis(1)
        var now = start
        val store = OptionalQuestStore(redis, Gson()) { now }
        val first = store.accept(player, farm).join()
        first.acceptedAtMillis shouldBe start
        first.expiresAtMillis shouldBe start + day
        val complete = store.accept(player, OptionalQuestKind.CASINO).join()
        OptionalQuestKind.CASINO.steps.forEach { store.observe(player, complete.kind, it).join() }
        redis.setHash("casino.entitlements", mapOf(player.toString() to "settled-once"))
        now += day - 1
        val next = store.accept(player, OptionalQuestKind.ADVENTURE).join()
        val lastMinute = store.observe(player, farm, OptionalQuestStep.FARM_VISITED).join()!!
        lastMinute.expired(now) shouldBe false
        store.accept(player, farm).join() shouldBe lastMinute
        now++
        val restarted = OptionalQuestStore(redis, Gson()) { now }
        restarted.load(player, farm).join()!!.expired(now) shouldBe true
        restarted.observe(player, farm, OptionalQuestStep.FARM_SHIFT_COMPLETE).join() shouldBe null
        restarted.load(player, next.kind).join()!!.expired(now) shouldBe false
        restarted.accept(player, complete.kind).join().completed shouldBe true
        val retaken = restarted.accept(player, farm).join()
        (retaken.runId == first.runId) shouldBe false
        retaken.steps shouldBe emptySet()
        retaken.expiresAtMillis shouldBe now + day
        restarted.observe(player, farm, OptionalQuestStep.FARM_SHIFT_COMPLETE, first.runId).join() shouldBe null
        restarted.observe(player, farm, OptionalQuestStep.FARM_SHIFT_COMPLETE, retaken.runId).join()!!.completed shouldBe true
        now += day
        restarted.accept(player, farm).join().runId shouldBe retaken.runId
        redis.getHash("casino.entitlements")[player.toString()] shouldBe "settled-once"
    }

    "a completion that wins the Redis race at the deadline survives a concurrent retake" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val farm = OptionalQuestKind.FARM
        val beforeDeadline = OptionalQuestStore(redis, Gson()) { 1_800_000_000_000L }
        val original = beforeDeadline.accept(player, farm).join()
        val intercept = AtomicBoolean(true)
        val racing = object : RedisOperations by redis {
            override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?,
                replacementValue: String?): CompletableFuture<Boolean> {
                if (intercept.compareAndSet(true, false)) {
                    beforeDeadline.observe(player, farm, OptionalQuestStep.FARM_SHIFT_COMPLETE, original.runId).join()
                }
                return redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
            }
        }
        val atDeadline = OptionalQuestStore(racing, Gson()) { requireNotNull(original.expiresAtMillis) }
        val winner = atDeadline.accept(player, farm).join()
        winner.runId shouldBe original.runId
        winner.completed shouldBe true
        atDeadline.load(player, farm).join() shouldBe winner
    }

    "unfinished v1 records keep their identity and progress with a single durable grace period" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val id = UUID.randomUUID().toString()
        val raw = """{"version":1,"kind":"farm","runId":"$id","steps":["FARM_VISITED"]}"""
        val field = "$player:farm"
        redis.setHash(OptionalQuestStore.HASH_KEY, mapOf(field to raw))
        val start = 1_800_000_000_000L
        var now = start
        fun store() = OptionalQuestStore(redis, Gson()) { now }
        val imported = store().load(player, OptionalQuestKind.FARM).join()!!
        imported.runId shouldBe id
        imported.steps shouldBe setOf(OptionalQuestStep.FARM_VISITED)
        imported.acceptedAtMillis shouldBe start
        now += TimeUnit.HOURS.toMillis(12)
        store().accept(player, imported.kind).join() shouldBe imported
        now += TimeUnit.HOURS.toMillis(12)
        store().observe(player, imported.kind, OptionalQuestStep.FARM_SHIFT_COMPLETE, id).join() shouldBe null
        redis.getHash(OptionalQuestStore.HASH_KEY)[field] shouldBe raw
        (store().accept(player, imported.kind).join().runId == id) shouldBe false
        val completed = raw.replace("\"FARM_VISITED\"", "\"FARM_VISITED\",\"FARM_SHIFT_COMPLETE\"")
        redis.setHash(OptionalQuestStore.HASH_KEY, mapOf(field to completed))
        val permanent = store().accept(player, imported.kind).join()
        permanent.completed shouldBe true
        permanent.runId shouldBe id
        permanent.expired(now + TimeUnit.DAYS.toMillis(10)) shouldBe false
        redis.getHash(OptionalQuestStore.HASH_KEY)[field] shouldBe completed
    }

    "reset removes all three completed stories and preserves other players and independent ledgers" {
        val redis = InMemoryRedis()
        val store = OptionalQuestStore(redis, Gson())
        val player = UUID.randomUUID()
        val other = UUID.randomUUID()
        for (kind in OptionalQuestKind.entries) {
            store.accept(player, kind).join()
            kind.steps.forEach { store.observe(player, kind, it).join() }
            store.load(player, kind).join()!!.completed shouldBe true
            store.accept(other, kind).join()
        }
        val otherBefore = OptionalQuestKind.entries.associateWith { store.load(other, it).join() }
        val home = OnboardingStore(redis, Gson()).enroll(player).join()
        redis.setHash("casino.entitlements", mapOf(player.toString() to "retained-one-time-receipt"))

        store.reset(player).get(5, TimeUnit.SECONDS)

        OptionalQuestKind.entries.forEach { kind ->
            store.load(player, kind).join() shouldBe null
            store.load(other, kind).join() shouldBe otherBefore.getValue(kind)
        }
        OnboardingStore(redis, Gson()).load(player).join() shouldBe home
        redis.getHash("casino.entitlements") shouldBe mapOf(player.toString() to "retained-one-time-receipt")
        store.reset(player).join()
        redis.getHash(OptionalQuestStore.HASH_KEY).size shouldBe OptionalQuestKind.entries.size
    }

    "reset waits for durable deletion and a storage failure is not reported as success" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val store = OptionalQuestStore(redis, Gson())
        val accepted = store.accept(player, OptionalQuestKind.FARM).join()
        val release = CompletableFuture<Unit>()
        val delayed = object : RedisOperations by redis {
            override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> =
                release.thenCompose { redis.saveMapEntries(key, *keyValuePairs) }
        }
        val resetting = OptionalQuestStore(delayed, Gson()).reset(player)
        resetting.isDone shouldBe false
        store.load(player, OptionalQuestKind.FARM).join() shouldBe accepted
        release.completeExceptionally(IllegalStateException("fixture deletion failed"))
        runCatching { resetting.join() }.isFailure shouldBe true
        store.load(player, OptionalQuestKind.FARM).join() shouldBe accepted
    }

    "a racing observation cannot resurrect a reset or cross into a new run after a reset or timeout" {
        for (change in listOf("reset", "reset and retake", "expire and retake")) {
            val redis = InMemoryRedis()
            val player = UUID.randomUUID()
            var now = 1_800_000_000_000L
            val normal = OptionalQuestStore(redis, Gson()) { now }
            val before = normal.accept(player, OptionalQuestKind.ADVENTURE).join()
            val entered = CompletableFuture<Unit>()
            val release = CompletableFuture<Unit>()
            val hold = AtomicBoolean(true)
            val delayed = object : RedisOperations by redis {
                override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> =
                    if (hold.compareAndSet(true, false)) {
                        entered.complete(Unit)
                        release.thenCompose { redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue) }
                    } else redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
            }
            val late = OptionalQuestStore(delayed, Gson()) { now }.observe(player, OptionalQuestKind.ADVENTURE, OptionalQuestStep.GUILD_VISITED)
            try {
                entered.get(5, TimeUnit.SECONDS)
                if (change == "expire and retake") now += TimeUnit.DAYS.toMillis(1) else normal.reset(player).join()
                val fresh = if (change == "reset") null else normal.accept(player, OptionalQuestKind.ADVENTURE).join()
                (fresh?.runId == before.runId) shouldBe false
                release.complete(Unit)
                late.get(5, TimeUnit.SECONDS) shouldBe null
                normal.load(player, OptionalQuestKind.ADVENTURE).join() shouldBe fresh
                if (fresh != null) normal.observe(player, OptionalQuestKind.ADVENTURE,
                    OptionalQuestStep.PRIMIS_VISITED, before.runId).join() shouldBe null
                normal.load(player, OptionalQuestKind.ADVENTURE).join() shouldBe fresh
            } finally { release.complete(Unit) }
        }
    }

    "real observations do not enroll and acceptance never resets an existing quest" {
        val redis = InMemoryRedis()
        val store = OptionalQuestStore(redis, Gson())
        val player = UUID.randomUUID()
        store.observe(player, OptionalQuestKind.FARM, OptionalQuestStep.FARM_SHIFT_COMPLETE).join() shouldBe null
        redis.getHash(OptionalQuestStore.HASH_KEY) shouldBe emptyMap()
        val accepted = store.accept(player, OptionalQuestKind.FARM).join()
        val completed = store.observe(player, OptionalQuestKind.FARM, OptionalQuestStep.FARM_SHIFT_COMPLETE).join()!!
        completed.completed shouldBe true
        completed.steps shouldBe OptionalQuestKind.FARM.steps
        completed.runId shouldBe accepted.runId
        store.accept(player, OptionalQuestKind.FARM).join() shouldBe completed
        OptionalQuestStore(redis, Gson()).load(player, OptionalQuestKind.FARM).join() shouldBe completed
    }

    "independent optional chains cannot change another chain or the initial home quest" {
        val redis = InMemoryRedis()
        val initial = OnboardingStore(redis, Gson())
        val store = OptionalQuestStore(redis, Gson())
        val player = UUID.randomUUID()
        val home = initial.enroll(player).join()
        initial.accept(player, home.runId).join()
        val expectedHome = initial.load(player).join()
        val casino = store.accept(player, OptionalQuestKind.CASINO).join()
        store.accept(player, OptionalQuestKind.ADVENTURE).join()
        store.observe(player, OptionalQuestKind.ADVENTURE, OptionalQuestStep.PRIMIS_INTRO_COMPLETE).join()?.completed shouldBe false
        val adventure = store.observe(player, OptionalQuestKind.ADVENTURE, OptionalQuestStep.GUILD_VISITED).join()!!
        adventure.completed shouldBe true
        adventure.steps shouldBe OptionalQuestKind.ADVENTURE.steps
        store.load(player, OptionalQuestKind.CASINO).join() shouldBe casino
        initial.load(player).join() shouldBe expectedHome
        runCatching { store.observe(player, OptionalQuestKind.CASINO, OptionalQuestStep.FARM_VISITED) }.isFailure shouldBe true
    }

    "concurrent independent nodes retain both observations instead of overwriting progress" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val normal = OptionalQuestStore(redis, Gson())
        normal.accept(player, OptionalQuestKind.ADVENTURE).join()
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val hold = AtomicBoolean(true)
        val delayed = object : RedisOperations by redis {
            override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> =
                if (hold.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.thenCompose { redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue) }
                } else redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        }
        val first = OptionalQuestStore(delayed, Gson()).observe(player, OptionalQuestKind.ADVENTURE, OptionalQuestStep.GUILD_VISITED)
        try {
            entered.get(5, TimeUnit.SECONDS)
            normal.observe(player, OptionalQuestKind.ADVENTURE, OptionalQuestStep.PRIMIS_INTRO_COMPLETE).join()
            release.complete(Unit)
            first.get(5, TimeUnit.SECONDS)?.completed shouldBe true
            normal.load(player, OptionalQuestKind.ADVENTURE).join()?.steps shouldBe OptionalQuestKind.ADVENTURE.steps
        } finally { release.complete(Unit) }
    }

    "casino location visits and all three durable trials are required exactly once" {
        val redis = InMemoryRedis()
        val store = OptionalQuestStore(redis, Gson())
        val player = UUID.randomUUID()
        store.accept(player, OptionalQuestKind.CASINO).join()
        val steps = OptionalQuestKind.CASINO.steps.toList()
        steps.dropLast(1).forEach { step ->
            store.observe(player, OptionalQuestKind.CASINO, step).join()?.completed shouldBe false
        }
        val completed = store.observe(player, OptionalQuestKind.CASINO, steps.last()).join()!!
        completed.completed shouldBe true
        steps.forEach { step -> store.observe(player, OptionalQuestKind.CASINO, step).join() shouldBe completed }
        store.accept(player, OptionalQuestKind.CASINO).join() shouldBe completed
    }

    "corrupt foreign duplicate and unknown records fail closed without resetting the player" {
        val redis = InMemoryRedis()
        val store = OptionalQuestStore(redis, Gson())
        val player = UUID.randomUUID()
        val key = "$player:farm"
        val valid = """{"version":1,"kind":"farm","runId":"${UUID.randomUUID()}","steps":[]}"""
        listOf("{broken", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"farm\"", "\"casino\""), valid.replace("\"steps\":[]", "\"steps\":[\"FARM_VISITED\",\"FARM_VISITED\"]"),
            valid.replace("\"steps\":[]", "\"steps\":[\"FARM_SHIFT_COMPLETE\"]"),
            valid.replace("\"steps\":[]", "\"steps\":[\"GUILD_VISITED\"]"), valid.dropLast(1) + ",\"extra\":true}").forEach { raw ->
            redis.setHash(OptionalQuestStore.HASH_KEY, mapOf(key to raw))
            runCatching { store.load(player, OptionalQuestKind.FARM).join() }.isFailure shouldBe true
            runCatching { store.accept(player, OptionalQuestKind.FARM).join() }.isFailure shouldBe true
            redis.getHash(OptionalQuestStore.HASH_KEY)[key] shouldBe raw
        }
    }
})
