package ru.arc.onboarding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class OnboardingJourneyTest : FreeSpec({
    val home = OnboardingPlace.fromBlock(" Survival ", -1, -17)
    val elsewhere = OnboardingPlace.fromChunk("survival", 4, 8)
    val operationId = UUID.randomUUID().toString()

    fun active(): OnboardingProgress = OnboardingProgress.offered().with(status = OnboardingStatus.ACTIVE)
    fun arrived(): OnboardingProgress = OnboardingJourney.observe(active(), OnboardingMilestone.FIRST_RTP)

    "only an active quest after first RTP can collect gameplay milestones" {
        val offered = OnboardingProgress.offered()
        OnboardingJourney.observe(offered, OnboardingMilestone.FIRST_RTP) shouldBe offered
        val started = active()
        listOf(
            OnboardingMilestone.HOME_CREATED,
            OnboardingMilestone.LAND_CLAIMED,
            OnboardingMilestone.BUILD_BOOK_OPENED,
            OnboardingMilestone.AUTOBUILD_STARTED,
            OnboardingMilestone.AUTOBUILD_COMPLETE,
        ).forEach { OnboardingJourney.observe(started, it, home) shouldBe started }
        val stopped = started.with(status = OnboardingStatus.STOPPED)
        OnboardingJourney.observe(stopped, OnboardingMilestone.FIRST_RTP) shouldBe stopped
    }

    "home and land in either order derive one normalized negative-coordinate foothold" {
        home shouldBe OnboardingPlace.fromChunk("survival", -1, -2)
        listOf(
            listOf(OnboardingMilestone.HOME_CREATED, OnboardingMilestone.LAND_CLAIMED),
            listOf(OnboardingMilestone.LAND_CLAIMED, OnboardingMilestone.HOME_CREATED),
        ).forEach { observations ->
            val complete = observations.fold(arrived()) { progress, milestone -> OnboardingJourney.observe(progress, milestone, home) }
            complete.footholdPlaces shouldBe setOf(home)
            complete.milestones shouldBe setOf(
                OnboardingMilestone.FIRST_RTP,
                OnboardingMilestone.HOME_CREATED,
                OnboardingMilestone.LAND_CLAIMED,
                OnboardingMilestone.FOOTHOLD_COMPLETE,
            )
        }
    }

    "different chunks and worlds do not complete a foothold until their matching observation arrives" {
        var progress = OnboardingJourney.observe(arrived(), OnboardingMilestone.HOME_CREATED, home)
        progress = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, elsewhere)
        progress = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, OnboardingPlace.fromChunk("creative", -1, -2))
        progress.footholdPlaces shouldBe emptySet()
        progress = OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, elsewhere)
        progress.footholdPlaces shouldBe setOf(elsewhere)
    }

    "a verified owned home recovers the corresponding land and construction counts only at that foothold" {
        val foothold = OnboardingJourney.observe(arrived(), OnboardingMilestone.HOME_CREATED, home, verifiedFoothold = true)
        foothold.homePlaces shouldBe setOf(home)
        foothold.landPlaces shouldBe setOf(home)
        foothold.footholdPlaces shouldBe setOf(home)
        listOf(OnboardingMilestone.BUILD_BOOK_OPENED, OnboardingMilestone.AUTOBUILD_STARTED, OnboardingMilestone.AUTOBUILD_COMPLETE).forEach {
            OnboardingJourney.observe(foothold, it, elsewhere, operationId = operationId) shouldBe foothold
        }
        val opened = OnboardingJourney.observe(foothold, OnboardingMilestone.BUILD_BOOK_OPENED, home)
        OnboardingJourney.observe(opened, OnboardingMilestone.AUTOBUILD_STARTED, home) shouldBe opened
        OnboardingJourney.observe(opened, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId) shouldBe opened
        val started = OnboardingJourney.observe(opened, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = operationId)
        started.buildOperationId shouldBe operationId
        val completed = OnboardingJourney.observe(started, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId)
        completed.status shouldBe OnboardingStatus.COMPLETED
        completed.milestones shouldBe OnboardingMilestone.entries.toSet()
        OnboardingJourney.observe(completed, OnboardingMilestone.HOME_CREATED, elsewhere) shouldBe completed
    }

    "construction completion requires the exact latest operation started in this run" {
        val foothold = OnboardingJourney.observe(arrived(), OnboardingMilestone.HOME_CREATED, home, verifiedFoothold = true)
        val first = OnboardingJourney.observe(foothold, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = operationId)
        val replacementId = UUID.randomUUID().toString()
        val replacement = OnboardingJourney.observe(first, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = replacementId)
        replacement.buildOperationId shouldBe replacementId
        OnboardingJourney.observe(replacement, OnboardingMilestone.AUTOBUILD_COMPLETE, home) shouldBe replacement
        OnboardingJourney.observe(replacement, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = operationId) shouldBe replacement
        OnboardingJourney.observe(replacement, OnboardingMilestone.AUTOBUILD_COMPLETE, elsewhere, operationId = replacementId) shouldBe replacement
        OnboardingJourney.observe(replacement, OnboardingMilestone.AUTOBUILD_COMPLETE, home, operationId = replacementId).status shouldBe OnboardingStatus.COMPLETED
        shouldThrow<IllegalArgumentException> {
            OnboardingJourney.observe(foothold, OnboardingMilestone.AUTOBUILD_STARTED, home, operationId = "invalid-operation")
        }
    }

    "milestone derivation and verified-home input cannot be forged" {
        shouldThrow<IllegalArgumentException> { OnboardingJourney.observe(arrived(), OnboardingMilestone.FOOTHOLD_COMPLETE, home) }
        shouldThrow<IllegalArgumentException> { OnboardingJourney.observe(arrived(), OnboardingMilestone.LAND_CLAIMED, home, verifiedFoothold = true) }
        shouldThrow<IllegalArgumentException> { OnboardingJourney.observe(arrived(), OnboardingMilestone.HOME_CREATED) }
    }

    "progress is a detached immutable snapshot and remembered places stay bounded without losing a foothold" {
        val input = mutableSetOf(OnboardingMilestone.FIRST_RTP)
        val snapshot = OnboardingProgress.create(1, active().runId, OnboardingStatus.ACTIVE, input, emptySet(), emptySet())
        input.clear()
        snapshot.milestones shouldBe setOf(OnboardingMilestone.FIRST_RTP)
        shouldThrow<UnsupportedOperationException> { (snapshot.milestones as MutableSet<OnboardingMilestone>).clear() }
        var progress = OnboardingJourney.observe(snapshot, OnboardingMilestone.HOME_CREATED, home, verifiedFoothold = true)
        repeat(OnboardingProgress.MAX_PLACES + 2) { index ->
            progress = OnboardingJourney.observe(progress, OnboardingMilestone.HOME_CREATED, OnboardingPlace.fromChunk("survival", index, 0))
            progress = OnboardingJourney.observe(progress, OnboardingMilestone.LAND_CLAIMED, OnboardingPlace.fromChunk("survival", index, 1))
        }
        progress.homePlaces.size shouldBe OnboardingProgress.MAX_PLACES
        progress.landPlaces.size shouldBe OnboardingProgress.MAX_PLACES
        progress.footholdPlaces shouldBe setOf(home)
        snapshot.homePlaces shouldBe emptySet()
    }
})
