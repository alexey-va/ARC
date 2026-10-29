package ru.arc.onboarding

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.redis.safety.JsonObjectContract
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OnboardingPlaytimeTest : FreeSpec({
    "counter wire remains readable by original strict v1 readers even when Gson serializes nulls" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, GsonBuilder().serializeNulls().create())
        val originalReader = JsonObjectContract(setOf("version", "servers"))
        store.sample(player, run, "spawn", 1_000).join()
        originalReader.validate(JsonParser.parseString(redis.getHash(OnboardingPlaytimeStore.HASH_KEY).getValue("$player:$run")))
        store.sample(player, run, "spawn", 1_000, suggestBuild = true).join().buildSuggestedAtTicks shouldBe 0L
        val record = JsonParser.parseString(redis.getHash(OnboardingPlaytimeStore.HASH_KEY).getValue("$player:$run"))
        originalReader.validate(record)
        record.asJsonObject.keySet() shouldBe setOf("version", "servers")
        OnboardingPlaytimeStore(redis, Gson()).load(player, run).join()?.buildSuggestedAtTicks shouldBe 0L
    }

    "acceptance and backend arrival start baselines while only observed play ticks consume three hours" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.load(player, run).join() shouldBe null
        val accepted = store.sample(player, run, "spawn", 1_000).join()
        accepted.playedTicks shouldBe 0L
        accepted.remainingTicks shouldBe 216_000L
        store.sample(player, run, "spawn", 61_000).join().playedTicks shouldBe 60_000L
        store.sample(player, run, "survival", 20_000).join().playedTicks shouldBe 60_000L

        val resumed = OnboardingPlaytimeStore(redis, Gson())
        resumed.load(player, run).join()?.playedTicks shouldBe 60_000L
        // Offline time and repeated delivery leave the native counter unchanged.
        resumed.sample(player, run, "survival", 20_000).join().playedTicks shouldBe 60_000L
        val lastTick = resumed.sample(player, run, "survival", 175_999).join()
        lastTick.remainingTicks shouldBe 1L
        lastTick.expired shouldBe false
        val expired = resumed.sample(player, run, "survival", 176_000).join()
        expired.playedTicks shouldBe 216_000L
        expired.remainingTicks shouldBe 0L
        expired.expired shouldBe true
        resumed.sample(player, run, "survival", 176_100).join().remainingTicks shouldBe 0L
    }

    "BUILD invitation starts once at transition and persists across refreshes reconnects and backends" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.sample(player, run, "spawn", 1_000).join()
        val beforeBuild = store.sample(player, run, "spawn", 121_000).join()
        beforeBuild.buildSuggestedAtTicks shouldBe null
        beforeBuild.remainingTicks shouldBe 96_000L
        val offered = store.sample(player, run, "spawn", 121_000, suggestBuild = true).join()
        offered.buildSuggestedAtTicks shouldBe 120_000L
        offered.remainingTicks(awaitingBuild = true) shouldBe 18_000L
        store.sample(player, run, "spawn", 127_000, suggestBuild = true).join().remainingTicks(awaitingBuild = true) shouldBe 12_000L

        val resumed = OnboardingPlaytimeStore(redis, Gson())
        resumed.load(player, run).join()?.buildSuggestedAtTicks shouldBe 120_000L
        // Neither an unchanged offline counter nor a new backend grants a new invitation.
        resumed.sample(player, run, "spawn", 127_000, suggestBuild = true).join().remainingTicks(awaitingBuild = true) shouldBe 12_000L
        resumed.sample(player, run, "survival", 50_000, suggestBuild = true).join().remainingTicks(awaitingBuild = true) shouldBe 12_000L
        val lastTick = resumed.sample(player, run, "survival", 61_999, suggestBuild = true).join()
        lastTick.remainingTicks(awaitingBuild = true) shouldBe 1L
        val expiredInvitation = resumed.sample(player, run, "survival", 62_000, suggestBuild = true).join()
        expiredInvitation.buildSuggestedAtTicks shouldBe 120_000L
        expiredInvitation.remainingTicks(awaitingBuild = true) shouldBe 0L
        // A genuine construction still receives the existing overall completion window.
        expiredInvitation.remainingTicks(awaitingBuild = false) shouldBe 78_000L
        expiredInvitation.expired shouldBe false
    }

    "BUILD invitation cannot extend an almost expired overall allowance" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.sample(player, run, "spawn", 1_000).join()
        val offered = store.sample(player, run, "spawn", 211_000, suggestBuild = true).join()
        offered.buildSuggestedAtTicks shouldBe 210_000L
        offered.remainingTicks(awaitingBuild = true) shouldBe 6_000L
        val expired = store.sample(player, run, "spawn", 217_000, suggestBuild = true).join()
        expired.remainingTicks(awaitingBuild = true) shouldBe 0L
        expired.remainingTicks(awaitingBuild = false) shouldBe 0L
        expired.expired shouldBe true
    }

    "legacy playtime initializes the optional BUILD invitation only on its first BUILD sample" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        val legacy = """{"version":1,"servers":[{"server":"spawn","baseline":1000,"maximum":2000}]}"""
        redis.setHash(OnboardingPlaytimeStore.HASH_KEY, mapOf("$player:$run" to legacy))
        val restored = store.load(player, run).join()!!
        restored.buildSuggestedAtTicks shouldBe null
        restored.remainingTicks(awaitingBuild = true) shouldBe 18_000L
        store.sample(player, run, "spawn", 2_000).join().buildSuggestedAtTicks shouldBe null
        redis.getHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY) shouldBe emptyMap()
        val offered = store.sample(player, run, "spawn", 3_000, suggestBuild = true).join()
        offered.buildSuggestedAtTicks shouldBe 2_000L
        offered.remainingTicks(awaitingBuild = true) shouldBe 18_000L
        val resumed = OnboardingPlaytimeStore(redis, Gson()).sample(player, run, "spawn", 4_000, suggestBuild = true).join()
        resumed.buildSuggestedAtTicks shouldBe 2_000L
        resumed.remainingTicks(awaitingBuild = true) shouldBe 17_000L
    }

    "a sample composes later old-node counters after committing its separate BUILD anchor" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        OnboardingPlaytimeStore(redis, Gson()).sample(player, run, "spawn", 1_000).join()
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val hold = AtomicBoolean(true)
        val delayed = object : RedisOperations by redis {
            override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> =
                if (key == OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY && hold.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.thenCompose { redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue) }
                } else redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        }
        val pending = OnboardingPlaytimeStore(delayed, Gson()).sample(player, run, "spawn", 2_000, suggestBuild = true)
        try {
            entered.get(5, TimeUnit.SECONDS)
            pending.isDone shouldBe false
            val before = redis.getHash(OnboardingPlaytimeStore.HASH_KEY).getValue("$player:$run")
            val oldRecord = JsonParser.parseString(before).asJsonObject
            JsonObjectContract(setOf("version", "servers")).validate(oldRecord)
            oldRecord.getAsJsonArray("servers").single().asJsonObject.addProperty("maximum", 3_000)
            redis.compareAndSetMapEntry(OnboardingPlaytimeStore.HASH_KEY, "$player:$run", before, Gson().toJson(oldRecord)).join() shouldBe true
            release.complete(Unit)
            val composed = pending.get(5, TimeUnit.SECONDS)
            composed.playedTicks shouldBe 2_000L
            composed.buildSuggestedAtTicks shouldBe 1_000L
            composed.remainingTicks(awaitingBuild = true) shouldBe 17_000L
            OnboardingPlaytimeStore(redis, Gson()).load(player, run).join() shouldBe composed
        } finally {
            release.complete(Unit)
        }
    }

    "competing nodes initialize one BUILD anchor and later samples cannot replace its winner" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val second = OnboardingPlaytimeStore(redis, Gson())
        second.sample(player, run, "spawn", 1_000).join()
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val hold = AtomicBoolean(true)
        val delayed = object : RedisOperations by redis {
            override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> =
                if (key == OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY && hold.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.thenCompose { redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue) }
                } else redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        }
        val first = OnboardingPlaytimeStore(delayed, Gson()).sample(player, run, "spawn", 2_000, suggestBuild = true)
        try {
            entered.get(5, TimeUnit.SECONDS)
            val winner = second.sample(player, run, "spawn", 3_000, suggestBuild = true).join()
            winner.buildSuggestedAtTicks shouldBe 2_000L
            val anchor = redis.getHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY)
            release.complete(Unit)
            first.get(5, TimeUnit.SECONDS) shouldBe winner
            val later = second.sample(player, run, "spawn", 4_000, suggestBuild = true).join()
            later.buildSuggestedAtTicks shouldBe 2_000L
            later.remainingTicks(awaitingBuild = true) shouldBe 17_000L
            redis.getHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY) shouldBe anchor
        } finally {
            release.complete(Unit)
        }
    }

    "loading the anchor before the final counter snapshot survives another node starting BUILD" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val second = OnboardingPlaytimeStore(redis, Gson())
        second.sample(player, run, "spawn", 1_000).join()
        val entered = CompletableFuture<Unit>()
        val release = CompletableFuture<Unit>()
        val hold = AtomicBoolean(true)
        val delayed = object : RedisOperations by redis {
            override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> =
                if (key == OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY && hold.compareAndSet(true, false)) {
                    entered.complete(Unit)
                    release.thenCompose { redis.loadMapEntries(key, *mapKeys) }
                } else redis.loadMapEntries(key, *mapKeys)
        }
        val loading = OnboardingPlaytimeStore(delayed, Gson()).load(player, run)
        try {
            entered.get(5, TimeUnit.SECONDS)
            val current = second.sample(player, run, "spawn", 3_000, suggestBuild = true).join()
            release.complete(Unit)
            loading.get(5, TimeUnit.SECONDS) shouldBe current
        } finally {
            release.complete(Unit)
        }
    }

    "a delayed old-run sample cannot spend or reset the replacement run allowance" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val oldRun = UUID.randomUUID().toString()
        val newRun = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.sample(player, oldRun, "spawn", 1_000).join()
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
        val pending = OnboardingPlaytimeStore(delayed, Gson()).sample(player, oldRun, "spawn", 41_000, suggestBuild = true)
        try {
            entered.get(5, TimeUnit.SECONDS)
            pending.isDone shouldBe false
            val replacement = store.sample(player, newRun, "spawn", 41_000).join()
            replacement.playedTicks shouldBe 0L
            replacement.buildSuggestedAtTicks shouldBe null
            release.complete(Unit)
            val oldSample = pending.get(5, TimeUnit.SECONDS)
            oldSample.playedTicks shouldBe 40_000L
            oldSample.buildSuggestedAtTicks shouldBe 40_000L
            store.sample(player, newRun, "spawn", 41_100).join().playedTicks shouldBe 100L
            redis.getHash(OnboardingPlaytimeStore.HASH_KEY).keys shouldBe setOf("$player:$oldRun", "$player:$newRun")
            redis.getHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY).keys shouldBe setOf("$player:$oldRun")
        } finally {
            release.complete(Unit)
        }
    }

    "a backwards or negative native counter fails without changing durable progress" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.sample(player, run, " SPAWN ", 1_000).join()
        val before = store.sample(player, run, "spawn", 2_000).join()
        val raw = redis.getHash(OnboardingPlaytimeStore.HASH_KEY)
        listOf(0, 1_999, -1).forEach { counter ->
            val rollback = shouldThrow<OnboardingPlaytime.CounterMovedBackwards> { before.sample("spawn", counter) }
            rollback.serverId shouldBe "spawn"
            rollback.previousCounter shouldBe 2_000
            rollback.counter shouldBe counter
            val persistedFailure = shouldThrow<CompletionException> { store.sample(player, run, "spawn", counter).join() }
            (persistedFailure.cause is OnboardingPlaytime.CounterMovedBackwards) shouldBe true
            store.load(player, run).join() shouldBe before
            redis.getHash(OnboardingPlaytimeStore.HASH_KEY) shouldBe raw
        }
        before.servers.keys shouldBe setOf("spawn")
        store.sample(player, run, "survival", 100).join().playedTicks shouldBe 1_000L
    }

    "invalid stored counters and wire shapes fail closed without replacing them" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        val entry = """{"server":"spawn","baseline":1000,"maximum":2000}"""
        val valid = """{"version":1,"servers":[$entry]}"""
        val invalid = listOf(
            "{broken", valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("[$entry]", "null"), valid.replace("[$entry]", "[$entry,$entry]"),
            valid.replace("\"spawn\"", "\" Spawn \""), valid.replace("\"spawn\"", "null"),
            valid.replace("\"baseline\":1000", "\"baseline\":-1"),
            valid.replace("\"maximum\":2000", "\"maximum\":999"),
            valid.replace("\"maximum\":2000", "\"maximum\":2000.5"),
            valid.replace("\"maximum\":2000", "\"maximum\":2147483648"),
            valid.replace("\"maximum\":2000", "\"maximum\":null"),
            valid.replace("\"maximum\":2000", "\"maximum\":\"2000\""),
            valid.replace("\"maximum\":2000", "\"maximum\":2000,\"unknown\":1"),
            valid.dropLast(1) + ",\"buildSuggestedAtTicks\":null}",
            valid.dropLast(1) + ",\"unknown\":true}",
        )
        invalid.forEach { raw ->
            redis.setHash(OnboardingPlaytimeStore.HASH_KEY, mapOf("$player:$run" to raw))
            runCatching { store.load(player, run).join() }.isFailure shouldBe true
            runCatching { store.sample(player, run, "spawn", 3_000).join() }.isFailure shouldBe true
            redis.getHash(OnboardingPlaytimeStore.HASH_KEY) shouldBe mapOf("$player:$run" to raw)
        }
    }

    "malformed separate BUILD anchors fail closed without replacing the anchor or counters" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        store.sample(player, run, "spawn", 1_000).join()
        store.sample(player, run, "spawn", 2_000).join()
        val counters = redis.getHash(OnboardingPlaytimeStore.HASH_KEY)
        val invalid = listOf(
            "{broken", "null", "0", "\"0\"", "[]", "{}", """{"ticks":null}""",
            """{"ticks":-1}""", """{"ticks":1001}""", """{"ticks":0.5}""",
            """{"ticks":"0"}""", """{"ticks":true}""", """{"ticks":9223372036854775808}""",
            """{"ticks":0,"unknown":1}""", """{"ticks":0,"ticks":0}""",
        )
        invalid.forEach { raw ->
            redis.setHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY, mapOf("$player:$run" to raw))
            runCatching { store.load(player, run).join() }.isFailure shouldBe true
            runCatching { store.sample(player, run, "spawn", 2_000, suggestBuild = true).join() }.isFailure shouldBe true
            redis.getHash(OnboardingPlaytimeStore.HASH_KEY) shouldBe counters
            redis.getHash(OnboardingPlaytimeStore.BUILD_SUGGESTION_HASH_KEY) shouldBe mapOf("$player:$run" to raw)
        }
    }

    "Redis failures and bounded backend identities never fabricate a fresh allowance" {
        val redis = InMemoryRedis()
        val player = UUID.randomUUID()
        val run = UUID.randomUUID().toString()
        val store = OnboardingPlaytimeStore(redis, Gson())
        val before = store.sample(player, run, "spawn", 100).join()
        redis.failOnLoad = true
        runCatching { store.load(player, run).join() }.isFailure shouldBe true
        runCatching { store.sample(player, run, "spawn", 200).join() }.isFailure shouldBe true
        redis.failOnLoad = false
        redis.failOnSave = true
        runCatching { store.sample(player, run, "spawn", 200).join() }.isFailure shouldBe true
        redis.failOnSave = false
        store.load(player, run).join() shouldBe before
        runCatching { store.sample(player, "invalid", "spawn", 200).join() }.isFailure shouldBe true
        runCatching { before.sample("invalid/backend", 200) }.isFailure shouldBe true
        val full = (1..OnboardingPlaytime.MAX_SERVERS).fold(OnboardingPlaytime.empty()) { state, index -> state.sample("server_$index", 1) }
        runCatching { full.sample("extra", 1) }.isFailure shouldBe true
        full.playedTicks shouldBe 0L
    }
})
