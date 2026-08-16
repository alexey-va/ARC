package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class OnboardingStoreTest : FreeSpec({
    val homeChunk = OnboardingPlace.fromChunk("survival", 10, 20)

    "persists home chunks and completes only after a matching claim" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v2.json")
        val playerId = UUID.randomUUID()
        val store = OnboardingStore.open(path)

        store.observe(playerId, OnboardingMilestone.FIRST_RTP, 10L).changed.shouldBeTrue()
        store.nextHint(playerId) shouldBe OnboardingHint.FIRST_RTP
        store.markDelivered(playerId, OnboardingHint.FIRST_RTP, 20L).shouldBeTrue()
        store.observe(playerId, OnboardingMilestone.HOME_CREATED, 30L, homeChunk).changed.shouldBeTrue()

        val restored = OnboardingStore.open(path)
        restored.playerCount() shouldBe 1
        restored.nextHint(playerId) shouldBe OnboardingHint.HOME_CREATED
        restored.observe(playerId, OnboardingMilestone.HOME_CREATED, 40L, homeChunk).changed.shouldBeFalse()

        val completed = restored.observe(playerId, OnboardingMilestone.LAND_CLAIMED, 50L, homeChunk)
        completed.addedMilestones shouldBe
            setOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)
        OnboardingStore.open(path).nextHint(playerId) shouldBe OnboardingHint.FOOTHOLD_COMPLETE
    }

    "does not create state for an event before first RTP" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v2.json")
        val store = OnboardingStore.open(path)

        store.observe(UUID.randomUUID(), OnboardingMilestone.LAND_CLAIMED, 10L, homeChunk).changed.shouldBeFalse()

        store.playerCount() shouldBe 0
        Files.exists(path).shouldBeFalse()
    }

    "retains delivered recovery evidence across a restart" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v2.json")
        val playerId = UUID.randomUUID()
        val otherChunk = OnboardingPlace.fromChunk("survival", 11, 20)
        val store = OnboardingStore.open(path)
        store.observe(playerId, OnboardingMilestone.FIRST_RTP, 10L)
        store.observe(playerId, OnboardingMilestone.HOME_CREATED, 20L, homeChunk)
        store.observe(playerId, OnboardingMilestone.LAND_CLAIMED, 30L, otherChunk)
        store.markDelivered(playerId, OnboardingHint.FOOTHOLD_MISMATCH, 40L).shouldBeTrue()

        val recovered = OnboardingStore.open(path).observe(playerId, OnboardingMilestone.LAND_CLAIMED, 50L, homeChunk)

        recovered.recoveredFoothold.shouldBeTrue()
    }

    "rejects an unknown schema instead of resetting and spamming players" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v2.json")
        Files.writeString(path, """{"version":99,"players":{}}""")

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "rejects the uncorrelated version one schema" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v2.json")
        Files.writeString(path, """{"version":1,"players":{}}""")

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "rejects progress without first RTP" {
        val playerId = UUID.randomUUID()
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v2.json")
        Files.writeString(
            path,
            """{"version":2,"players":{"$playerId":{"milestones":["home-created"],"pendingHints":[],"deliveredHints":[],"homePlaces":[{"world":"survival","chunkX":1,"chunkZ":2}],"footholdPlaces":[],"updatedAt":1}}}""",
        )

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "rejects a foothold milestone without a correlated place" {
        val playerId = UUID.randomUUID()
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v2.json")
        Files.writeString(
            path,
            """{"version":2,"players":{"$playerId":{"milestones":["first-rtp","home-created","land-claimed","foothold-complete"],"pendingHints":[],"deliveredHints":[],"homePlaces":[{"world":"survival","chunkX":1,"chunkZ":2}],"footholdPlaces":[],"updatedAt":1}}}""",
        )

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "rejects more than the bounded number of remembered homes" {
        val playerId = UUID.randomUUID()
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v2.json")
        val homes =
            (0..OnboardingPlayerProgress.MAX_PLACES).joinToString(",") { chunk ->
                """{"world":"survival","chunkX":$chunk,"chunkZ":0}"""
            }
        Files.writeString(
            path,
            """{"version":2,"players":{"$playerId":{"milestones":["first-rtp","home-created"],"pendingHints":[],"deliveredHints":[],"homePlaces":[$homes],"footholdPlaces":[],"updatedAt":1}}}""",
        )

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "does not mark a hint that is no longer pending" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v2.json")
        val store = OnboardingStore.open(path)

        store.markDelivered(UUID.randomUUID(), OnboardingHint.FIRST_RTP, 10L).shouldBeFalse()
    }
})
