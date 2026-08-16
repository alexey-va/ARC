package ru.arc.onboarding

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class OnboardingJourneyTest : FreeSpec({
    "ignores mechanics observed before the first RTP" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 1L).changed shouldBe false
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 2L).changed shouldBe false
        OnboardingJourney.observe(progress, OnboardingMilestone.BUILD_BOOK_OPENED, 3L).changed shouldBe false
        progress.milestones shouldBe emptySet()
        progress.pendingHints shouldBe emptyList()
    }

    "connects RTP, home, claim and foothold without requiring an order" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L)
        val completed = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 3L)

        completed.addedMilestones shouldBe
            setOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.FOOTHOLD_COMPLETE)
        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_COMPLETE)
    }

    "uses the symmetric recovery hint when claim comes before home" {
        val progress = OnboardingPlayerProgress()

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, 2L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 3L)

        progress.pendingHints.shouldContainExactly(OnboardingHint.FOOTHOLD_COMPLETE)
    }

    "explains only the missing protection when a BuildBook is opened early" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)
        OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, 2L)

        OnboardingJourney.observe(progress, OnboardingMilestone.BUILD_BOOK_OPENED, 3L)

        progress.pendingHints.shouldContainExactly(OnboardingHint.BUILD_BOOK_MISSING_LAND)
    }

    "does not duplicate milestones or hints" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)

        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 2L).changed shouldBe false
        progress.pendingHints.shouldContainExactly(OnboardingHint.FIRST_RTP)
    }

    "connects a completed first build to the next economy surface" {
        val progress = OnboardingPlayerProgress()
        OnboardingJourney.observe(progress, OnboardingMilestone.FIRST_RTP, 1L)

        OnboardingJourney.observe(progress, OnboardingMilestone.AUTOBUILD_COMPLETE, 2L)

        progress.pendingHints.last() shouldBe OnboardingHint.AUTOBUILD_COMPLETE
    }
})
