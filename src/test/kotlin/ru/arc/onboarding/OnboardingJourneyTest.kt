package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class OnboardingJourneyTest : FreeSpec({
    val homeChunk = OnboardingPlace.fromChunk("survival", 10, 20)
    val otherChunk = OnboardingPlace.fromChunk("survival", 11, 20)

    "ignores mechanics observed before the first RTP" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 1L, homeChunk).changed shouldBe false
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 2L, homeChunk).changed shouldBe false
        OnboardingJourney.observe(progress, OnboardingMilestone.BUILD_BOOK_OPENED, 3L, homeChunk).changed shouldBe false
        progress.milestones shouldBe emptySet()
        progress.homePlaces shouldBe emptySet()
        progress.pendingHints shouldBe emptyList()
    }

    "connects home then claim only when both occupy the same chunk" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L, homeChunk)
        val completed = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 3L, homeChunk)

        completed.addedMilestones shouldBe
            setOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)
        progress.footholdPlaces shouldBe setOf(homeChunk)
        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_COMPLETE)
    }

    "connects claim then home through the live Lands protection check" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 2L, homeChunk)
        val completed =
            OnboardingJourney.observe(
                progress,
                OnboardingMilestone.HOME_CREATED,
                3L,
                homeChunk,
                verifiedFoothold = true,
            )

        completed.addedMilestones shouldBe
            setOf(OnboardingMilestone.HOME_CREATED, OnboardingMilestone.FOOTHOLD_COMPLETE)
        progress.footholdPlaces shouldBe setOf(homeChunk)
        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_COMPLETE)
    }

    "does not call different home and claim chunks a safe base" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L, homeChunk)
        val mismatch = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 3L, otherChunk)

        mismatch.addedMilestones shouldBe setOf(OnboardingMilestone.LAND_CLAIMED)
        progress.footholdPlaces shouldBe emptySet()
        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_MISMATCH)
    }

    "recovers when a later claim matches the remembered home" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L, homeChunk)
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 3L, otherChunk)

        val recovered = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 4L, homeChunk)

        recovered.addedMilestones shouldBe setOf(OnboardingMilestone.FOOTHOLD_COMPLETE)
        progress.footholdPlaces shouldBe setOf(homeChunk)
        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_COMPLETE)
    }

    "explains only the missing protection when a BuildBook is opened early" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L, homeChunk)

        OnboardingJourney.observe(progress, OnboardingMilestone.BUILD_BOOK_OPENED, 3L, homeChunk)

        progress.pendingHints.shouldContainExactly(OnboardingHint.BUILD_BOOK_MISSING_LAND)
    }

    "warns when the BuildBook is opened outside an established foothold" {
        val progress = safeProgress(homeChunk)
        progress.pendingHints.clear()

        OnboardingJourney.observe(progress, OnboardingMilestone.BUILD_BOOK_OPENED, 4L, otherChunk)

        progress.pendingHints.shouldContainExactly(OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD)
    }

    "does not advance the first-build journey outside the safe chunk" {
        val progress = safeProgress(homeChunk)
        progress.pendingHints.clear()

        OnboardingJourney.observe(progress, OnboardingMilestone.AUTOBUILD_COMPLETE, 4L, otherChunk).changed shouldBe false
        (OnboardingMilestone.AUTOBUILD_COMPLETE in progress.milestones) shouldBe false

        val completed = OnboardingJourney.observe(progress, OnboardingMilestone.AUTOBUILD_COMPLETE, 5L, homeChunk)
        completed.addedMilestones shouldBe
            setOf(OnboardingMilestone.AUTOBUILD_STARTED, OnboardingMilestone.AUTOBUILD_COMPLETE)
        progress.pendingHints.shouldContainExactly(OnboardingHint.AUTOBUILD_COMPLETE)
    }

    "does not duplicate milestones or hints" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 2L).changed shouldBe false
        progress.pendingHints.shouldContainExactly(OnboardingHint.FIRST_RTP)
    }

    "normalizes world names and negative block coordinates to stable chunks" {
        OnboardingPlace.fromBlock(" Survival ", -1, -17) shouldBe
            OnboardingPlace.fromChunk("survival", -1, -2)
    }
})

private fun safeProgress(place: OnboardingPlace): OnboardingPlayerProgress =
    OnboardingPlayerProgress().also { progress ->
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L, place)
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 3L, place)
    }
