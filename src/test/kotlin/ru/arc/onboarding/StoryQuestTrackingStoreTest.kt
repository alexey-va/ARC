package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import java.util.UUID

class StoryQuestTrackingStoreTest : FreeSpec({
    "selection survives a new store and explicitly clearing it differs from never choosing" {
        val redis = InMemoryRedis()
        val id = UUID.randomUUID()
        val store = StoryQuestTrackingStore(redis)
        store.load(id).join() shouldBe null
        store.save(id, "adventure").join()
        StoryQuestTrackingStore(redis).load(id).join() shouldBe "adventure"
        store.save(id, null).join()
        StoryQuestTrackingStore(redis).load(id).join() shouldBe ""
        redis.getHash(OptionalQuestStore.HASH_KEY) shouldBe emptyMap()
    }

    "unknown persisted selections fail visibly without overwriting them or any quest progress" {
        val redis = InMemoryRedis()
        val id = UUID.randomUUID()
        redis.setHash(StoryQuestTrackingStore.KEY, mapOf(id.toString() to "foreign_story"))
        val store = StoryQuestTrackingStore(redis)
        runCatching { store.load(id).join() }.isFailure shouldBe true
        runCatching { store.save(id, "foreign_story").join() }.isFailure shouldBe true
        redis.getHash(StoryQuestTrackingStore.KEY)[id.toString()] shouldBe "foreign_story"
    }

    "explicit adventure wins over first home and untracking never silently chooses another story" {
        storyQuestSelection("adventure", true, listOf("adventure", "casino")) shouldBe "adventure"
        storyQuestSelection("", true, listOf("adventure")) shouldBe null
        storyQuestSelection("adventure", true, listOf("casino")) shouldBe null
        storyQuestSelection(null, true, listOf("adventure")) shouldBe "first_home"
        storyQuestSelection(null, false, listOf("adventure", "casino")) shouldBe "adventure"
    }
})
