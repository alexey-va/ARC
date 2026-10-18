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

class OnboardingStoreTest : FreeSpec({
    "missing state and offered state never progress without explicit enrollment and acceptance" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val absentRun = UUID.randomUUID().toString()
        store.load(playerId).join() shouldBe null
        store.accept(playerId, absentRun).join() shouldBe null
        store.observe(playerId, absentRun, OnboardingMilestone.FIRST_RTP).join() shouldBe null
        redis.getHash(OnboardingStore.HASH_KEY) shouldBe emptyMap()

        val offered = store.enroll(playerId).join()
        offered.status shouldBe OnboardingStatus.OFFERED
        store.observe(playerId, offered.runId, OnboardingMilestone.FIRST_RTP).join() shouldBe null
        store.accept(playerId, absentRun).join() shouldBe null
        store.load(playerId).join() shouldBe offered
        store.accept(playerId, offered.runId).join()?.status shouldBe OnboardingStatus.ACTIVE
        store.accept(playerId, offered.runId).join() shouldBe null
    }

    "independent stores persist a construction start and only its matching completion survives reopening" {
        val redis = InMemoryRedis()
        val first = OnboardingStore(redis, Gson())
        val second = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val offered = first.enroll(playerId).join()
        second.accept(playerId, offered.runId).join()
        second.observe(playerId, offered.runId, OnboardingMilestone.HOME_CREATED, OnboardingPlace.fromChunk("survival", 0, 0)).join()?.milestones shouldBe emptySet()
        first.observe(playerId, offered.runId, OnboardingMilestone.FIRST_RTP).join()
        val home = OnboardingPlace.fromBlock(" SURVIVAL ", -1, -17)
        second.observe(playerId, offered.runId, OnboardingMilestone.LAND_CLAIMED, home).join()
        first.observe(playerId, offered.runId, OnboardingMilestone.HOME_CREATED, home).join()
        val opened = second.observe(playerId, offered.runId, OnboardingMilestone.BUILD_BOOK_OPENED, home).join()!!
        val operationId = UUID.randomUUID().toString()
        first.observe(playerId, offered.runId, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId).join() shouldBe opened
        runCatching { first.observe(playerId, offered.runId, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = "invalid-operation").join() }.isFailure shouldBe true
        second.load(playerId).join() shouldBe opened
        val started = first.observe(playerId, offered.runId, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = operationId).join()!!
        val reopened = OnboardingStore(redis, Gson())
        reopened.load(playerId).join()?.buildOperationId shouldBe operationId
        reopened.observe(playerId, offered.runId, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = UUID.randomUUID().toString()).join() shouldBe started
        val completed = reopened.observe(playerId, offered.runId, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId).join()!!
        completed.status shouldBe OnboardingStatus.COMPLETED
        completed.milestones shouldBe OnboardingMilestone.entries.toSet()
        reopened.expireActive(playerId, offered.runId).join() shouldBe null
        OnboardingStore(redis, Gson()).load(playerId).join() shouldBe completed
    }

    "playtime expiry stops only the matching active run and cannot expire a new offer" {
        val store = OnboardingStore(InMemoryRedis(), Gson())
        val playerId = UUID.randomUUID()
        val first = store.enroll(playerId).join()
        store.expireActive(playerId, first.runId).join() shouldBe null
        store.accept(playerId, first.runId).join()
        store.expireActive(playerId, first.runId).join()?.status shouldBe OnboardingStatus.STOPPED
        val next = store.enroll(playerId).join()
        store.expireActive(playerId, first.runId).join() shouldBe null
        store.load(playerId).join() shouldBe next
    }

    "restarting a quest does not inherit or complete the preceding construction operation" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val home = OnboardingPlace.fromChunk("survival", 1, 2)
        val operationId = UUID.randomUUID().toString()
        val oldRun = store.enroll(playerId).join()
        store.accept(playerId, oldRun.runId).join()
        store.observe(playerId, oldRun.runId, OnboardingMilestone.FIRST_RTP).join()
        store.observe(playerId, oldRun.runId, OnboardingMilestone.HOME_CREATED, home, verifiedFoothold = true).join()
        store.observe(playerId, oldRun.runId, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = operationId).join()?.buildOperationId shouldBe operationId

        val replacement = store.enroll(playerId).join()
        replacement.buildOperationId shouldBe null
        store.accept(playerId, replacement.runId).join()
        store.observe(playerId, replacement.runId, OnboardingMilestone.FIRST_RTP).join()
        val foothold = store.observe(playerId, replacement.runId, OnboardingMilestone.HOME_CREATED, home, verifiedFoothold = true).join()!!
        store.observe(playerId, oldRun.runId, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId).join() shouldBe null
        store.observe(playerId, replacement.runId, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId).join() shouldBe foothold
        store.load(playerId).join()?.status shouldBe OnboardingStatus.ACTIVE
        store.load(playerId).join()?.buildOperationId shouldBe null
    }

    "version one no-build records allow an omitted or null optional construction operation" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val runId = UUID.randomUUID().toString()
        val legacy = """{"version":1,"runId":"$runId","status":"OFFERED","milestones":[],"homePlaces":[],"landPlaces":[]}"""
        listOf(legacy, legacy.dropLast(1) + ",\"buildOperationId\":null}").forEach { raw ->
            redis.setHash(OnboardingStore.HASH_KEY, mapOf(playerId.toString() to raw))
            store.load(playerId).join() shouldBe OnboardingProgress.offered(runId)
            store.accept(playerId, runId).join()?.buildOperationId shouldBe null
            OnboardingStore(redis, Gson()).load(playerId).join()?.status shouldBe OnboardingStatus.ACTIVE
        }
        val placeJson = """{"world":"survival","chunkX":1,"chunkZ":2}"""
        val activeLegacy = """{"version":1,"runId":"$runId","status":"ACTIVE","milestones":["first-rtp","home-created","land-claimed","foothold-complete","build-book-opened"],"homePlaces":[$placeJson],"landPlaces":[$placeJson]}"""
        redis.setHash(OnboardingStore.HASH_KEY, mapOf(playerId.toString() to activeLegacy))
        val restored = store.load(playerId).join()!!
        restored.buildOperationId shouldBe null
        val operationId = UUID.randomUUID().toString()
        store.observe(playerId, runId, OnboardingMilestone.AUTOBUILD_STARTED, restored.footholdPlaces.single(), operationId = operationId).join()?.buildOperationId shouldBe operationId
        OnboardingStore(redis, Gson()).load(playerId).join()?.buildOperationId shouldBe operationId
    }

    "a restart changes run identity and an in-flight old CAS cannot advance the replacement" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val old = store.enroll(playerId).join()
        store.accept(playerId, old.runId).join()
        val writeEntered = CompletableFuture<Unit>()
        val releaseWrite = CompletableFuture<Unit>()
        val holdNextWrite = AtomicBoolean(true)
        val delayed = object : RedisOperations by redis {
            override fun compareAndSetMapEntry(key: String, mapKey: String, expectedValue: String?, replacementValue: String?): CompletableFuture<Boolean> =
                if (holdNextWrite.compareAndSet(true, false)) {
                    writeEntered.complete(Unit)
                    releaseWrite.thenCompose { redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue) }
                } else redis.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        }
        val oldObservation = OnboardingStore(delayed, Gson()).observe(playerId, old.runId, OnboardingMilestone.FIRST_RTP)
        try {
            writeEntered.get(5, TimeUnit.SECONDS)
            oldObservation.isDone shouldBe false
            val replacement = store.enroll(playerId).join()
            (replacement.runId == old.runId) shouldBe false
            store.accept(playerId, old.runId).join() shouldBe null
            store.stop(playerId, old.runId).join() shouldBe null
            releaseWrite.complete(Unit)
            oldObservation.get(5, TimeUnit.SECONDS) shouldBe null
            store.load(playerId).join() shouldBe replacement
        } finally {
            releaseWrite.complete(Unit)
        }
    }

    "stop retains a network tombstone and later observers or accept callbacks cannot reactivate it" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val run = store.enroll(playerId).join()
        store.accept(playerId, run.runId).join()
        store.observe(playerId, run.runId, OnboardingMilestone.FIRST_RTP).join()
        val stopped = store.stop(playerId, run.runId).join()!!
        stopped.status shouldBe OnboardingStatus.STOPPED
        store.accept(playerId, run.runId).join() shouldBe null
        store.observe(playerId, run.runId, OnboardingMilestone.HOME_CREATED, OnboardingPlace.fromChunk("survival", 0, 0), verifiedFoothold = true).join() shouldBe null
        OnboardingStore(redis, Gson()).load(playerId).join() shouldBe stopped
        store.stop(playerId, run.runId).join() shouldBe stopped
    }

    "malformed null unknown duplicate and inconsistent stored data fail closed without overwriting it" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        val runId = UUID.randomUUID().toString()
        val offered = """{"version":1,"runId":"$runId","status":"OFFERED","milestones":[],"homePlaces":[],"landPlaces":[]}"""
        val activeHome = """{"version":1,"runId":"$runId","status":"ACTIVE","milestones":["first-rtp","home-created"],"homePlaces":[{"world":"survival","chunkX":0,"chunkZ":0}],"landPlaces":[]}"""
        val startedWithoutOperation = """{"version":1,"runId":"$runId","status":"ACTIVE","milestones":["first-rtp","home-created","land-claimed","foothold-complete","autobuild-started"],"homePlaces":[{"world":"survival","chunkX":0,"chunkZ":0}],"landPlaces":[{"world":"survival","chunkX":0,"chunkZ":0}]}"""
        val tooManyHomes = (0..OnboardingProgress.MAX_PLACES).joinToString(",") { """{"world":"survival","chunkX":$it,"chunkZ":0}""" }
        val invalid = listOf(
            "{broken",
            offered.replace("\"version\":1", "\"version\":null"),
            offered.replace("\"version\":1", "\"version\":\"1\""),
            offered.replace("\"version\":1", "\"version\":2"),
            offered.replace("\"$runId\"", "null"),
            offered.replace("\"OFFERED\"", "null"),
            offered.replace("\"OFFERED\"", "\"UNKNOWN\""),
            offered.replace("\"milestones\":[]", "\"milestones\":null"),
            offered.replace("\"homePlaces\":[]", "\"homePlaces\":null"),
            offered.replace("\"landPlaces\":[]", "\"landPlaces\":null"),
            offered.replace("\"milestones\":[]", "\"milestones\":[null]"),
            offered.replace("\"milestones\":[]", "\"milestones\":[\"unknown\"]"),
            offered.replace("\"milestones\":[]", "\"milestones\":[\"first-rtp\"]"),
            offered.replace("\"OFFERED\"", "\"ACTIVE\"").replace("\"milestones\":[]", "\"milestones\":[\"first-rtp\",\"first-rtp\"]"),
            offered.dropLast(1) + ",\"unknown\":true}",
            offered.dropLast(1) + ",\"buildOperationId\":123}",
            offered.dropLast(1) + ",\"buildOperationId\":\"${UUID.randomUUID()}\"}",
            startedWithoutOperation,
            startedWithoutOperation.dropLast(1) + ",\"buildOperationId\":null}",
            startedWithoutOperation.dropLast(1) + ",\"buildOperationId\":\"invalid-operation\"}",
            activeHome.replace("\"first-rtp\",", ""),
            activeHome.replace("\"chunkX\":0", "\"chunkX\":0.5"),
            activeHome.replace("\"survival\"", "\" Survival \""),
            activeHome.replace("\"chunkZ\":0", "\"chunkZ\":0,\"unknown\":true"),
            activeHome.replace("[{\"world\":\"survival\",\"chunkX\":0,\"chunkZ\":0}]", "[$tooManyHomes]"),
        )
        invalid.forEach { raw ->
            redis.setHash(OnboardingStore.HASH_KEY, mapOf(playerId.toString() to raw))
            runCatching { store.load(playerId).join() }.isFailure shouldBe true
            runCatching { store.enroll(playerId).join() }.isFailure shouldBe true
            runCatching { store.accept(playerId, runId).join() }.isFailure shouldBe true
            runCatching { store.observe(playerId, runId, OnboardingMilestone.FIRST_RTP).join() }.isFailure shouldBe true
            redis.getHash(OnboardingStore.HASH_KEY)[playerId.toString()] shouldBe raw
        }
    }

    "Redis failures propagate without reporting or persisting a successful enrollment" {
        val redis = InMemoryRedis()
        val store = OnboardingStore(redis, Gson())
        val playerId = UUID.randomUUID()
        redis.failOnLoad = true
        runCatching { store.load(playerId).join() }.isFailure shouldBe true
        redis.failOnLoad = false
        redis.failOnSave = true
        runCatching { store.enroll(playerId).join() }.isFailure shouldBe true
        redis.getHash(OnboardingStore.HASH_KEY) shouldBe emptyMap()
    }
})
