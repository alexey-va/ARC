package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID

class OnboardingStoreTest : FreeSpec({
    "persists progress and pending delivery atomically" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v1.json")
        val playerId = UUID.randomUUID()
        val store = OnboardingStore.open(path)

        store.observe(playerId, OnboardingMilestone.FIRST_RTP, 10L).changed.shouldBeTrue()
        store.nextHint(playerId) shouldBe OnboardingHint.FIRST_RTP
        store.markDelivered(playerId, OnboardingHint.FIRST_RTP, 20L).shouldBeTrue()
        store.observe(playerId, OnboardingMilestone.HOME_CREATED, 30L).changed.shouldBeTrue()

        val restored = OnboardingStore.open(path)
        restored.playerCount() shouldBe 1
        restored.nextHint(playerId) shouldBe OnboardingHint.HOME_CREATED
        restored.observe(playerId, OnboardingMilestone.HOME_CREATED, 40L).changed.shouldBeFalse()
    }

    "does not create state for an event before first RTP" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v1.json")
        val store = OnboardingStore.open(path)

        store.observe(UUID.randomUUID(), OnboardingMilestone.LAND_CLAIMED, 10L).changed.shouldBeFalse()

        store.playerCount() shouldBe 0
        Files.exists(path).shouldBeFalse()
    }

    "rejects an unknown schema instead of resetting and spamming players" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v1.json")
        Files.writeString(path, """{"version":2,"players":{}}""")

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "rejects progress without first RTP" {
        val playerId = UUID.randomUUID()
        val path = Files.createTempDirectory("arc-onboarding-").resolve("onboarding-v1.json")
        Files.writeString(
            path,
            """{"version":1,"players":{"$playerId":{"milestones":["home-created"],"pendingHints":[],"deliveredHints":[],"updatedAt":1}}}""",
        )

        shouldThrow<IllegalArgumentException> { OnboardingStore.open(path) }
    }

    "does not mark a hint that is no longer pending" {
        val path = Files.createTempDirectory("arc-onboarding-").resolve("data/onboarding-v1.json")
        val store = OnboardingStore.open(path)

        store.markDelivered(UUID.randomUUID(), OnboardingHint.FIRST_RTP, 10L).shouldBeFalse()
    }
})
