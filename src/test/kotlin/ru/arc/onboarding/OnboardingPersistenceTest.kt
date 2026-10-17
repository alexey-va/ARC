package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import ru.arc.config.Config
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnboardingPersistenceTest : FreeSpec({
    fun prepared(): PreparedOnboarding {
        val root = Files.createTempDirectory("arc-onboarding-async-")
        Files.writeString(root.resolve("onboarding.yml"), "enabled: true\nworlds: [survival]\n")
        return PreparedOnboarding(OnboardingConfig.load(Config(root, "onboarding.yml")), OnboardingStore.open(root.resolve("state.json")))
    }

    "blocked cold IO buffers events in order and main reads never acquire the store monitor" {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val player = UUID.randomUUID()
        val persistence = OnboardingPersistence {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            prepared()
        }
        try {
            entered.await(5, TimeUnit.SECONDS) shouldBe true
            val first = persistence.submit(player) { it.store!!.observe(player, OnboardingMilestone.FIRST_RTP, 1L) }
            val home = persistence.submit(player) {
                it.store!!.observe(player, OnboardingMilestone.HOME_CREATED, 2L, OnboardingPlace.fromChunk("survival", 0, 0))
            }
            first.isDone shouldBe false
            persistence.nextHint(player) shouldBe null
            release.countDown()
            first.get(5, TimeUnit.SECONDS).changed shouldBe true
            home.get(5, TimeUnit.SECONDS).changed shouldBe true
            persistence.nextHint(player) shouldBe OnboardingHint.HOME_CREATED
        } finally {
            release.countDown()
            persistence.closeAsync().get(5, TimeUnit.SECONDS)
        }
    }

    "shutdown returns an accepted but undisplayed hint before the next generation opens" {
        val data = prepared()
        val player = UUID.randomUUID()
        val persistence = OnboardingPersistence { data }
        persistence.submit(player) { it.store!!.observe(player, OnboardingMilestone.FIRST_RTP, 1L) }.get(5, TimeUnit.SECONDS)
        persistence.reserveDelivery(player, OnboardingHint.FIRST_RTP, 2L).get(5, TimeUnit.SECONDS) shouldBe true
        val drain = persistence.closeAsync()
        val replacement = OnboardingPersistence(drain) { data }
        try {
            replacement.ready.get(5, TimeUnit.SECONDS)
            replacement.nextHint(player) shouldBe OnboardingHint.FIRST_RTP
        } finally { replacement.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "returning an old delivery cannot resurrect a reset player generation" {
        val data = prepared()
        val player = UUID.randomUUID()
        val persistence = OnboardingPersistence { data }
        try {
            persistence.submit(player) { it.store!!.observe(player, OnboardingMilestone.FIRST_RTP, 1L) }.get(5, TimeUnit.SECONDS)
            persistence.reserveDelivery(player, OnboardingHint.FIRST_RTP, 2L).get(5, TimeUnit.SECONDS)
            persistence.submit(player) { it.store!!.reset(player) }.get(5, TimeUnit.SECONDS)
            persistence.returnDelivery(player).get(5, TimeUnit.SECONDS)
            persistence.nextHint(player) shouldBe null
            data.store!!.playerCount() shouldBe 0
        } finally { persistence.closeAsync().get(5, TimeUnit.SECONDS) }
    }

    "failed bootstrap rejects buffered events instead of reporting ready" {
        val persistence = OnboardingPersistence { error("broken config") }
        try {
            runCatching { persistence.ready.get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
            runCatching { persistence.submit(UUID.randomUUID()) { true }.get(5, TimeUnit.SECONDS) }.isFailure shouldBe true
        } finally { persistence.closeAsync().get(5, TimeUnit.SECONDS) }
    }
})
