package ru.arc.onboarding

import ru.arc.product.ProductOnboardingHint
import java.util.LinkedHashSet
import java.util.Locale

internal enum class OnboardingMilestone(val id: String) {
    FIRST_RTP("first-rtp"),
    HOME_CREATED("home-created"),
    LAND_CLAIMED("land-claimed"),
    FOOTHOLD_COMPLETE("foothold-complete"),
    BUILD_BOOK_OPENED("build-book-opened"),
    AUTOBUILD_STARTED("autobuild-started"),
    AUTOBUILD_COMPLETE("autobuild-complete");

    companion object {
        fun fromId(id: String): OnboardingMilestone =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown onboarding milestone: $id")
    }
}

internal enum class OnboardingHint(
    val id: String,
    val productHint: ProductOnboardingHint,
) {
    FIRST_RTP("first-rtp", ProductOnboardingHint.FIRST_RTP),
    HOME_CREATED("home-created", ProductOnboardingHint.HOME_CREATED),
    LAND_CLAIMED("land-claimed", ProductOnboardingHint.LAND_CLAIMED),
    FOOTHOLD_MISMATCH("foothold-mismatch", ProductOnboardingHint.FOOTHOLD_MISMATCH),
    FOOTHOLD_COMPLETE("foothold-complete", ProductOnboardingHint.FOOTHOLD_COMPLETE),
    BUILD_BOOK_MISSING_HOME("build-book-missing-home", ProductOnboardingHint.BUILD_BOOK_MISSING_HOME),
    BUILD_BOOK_MISSING_LAND("build-book-missing-land", ProductOnboardingHint.BUILD_BOOK_MISSING_LAND),
    BUILD_BOOK_MISSING_BOTH("build-book-missing-both", ProductOnboardingHint.BUILD_BOOK_MISSING_BOTH),
    BUILD_BOOK_OUTSIDE_FOOTHOLD("build-book-outside-foothold", ProductOnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD),
    AUTOBUILD_COMPLETE("autobuild-complete", ProductOnboardingHint.AUTOBUILD_COMPLETE);

    companion object {
        fun fromId(id: String): OnboardingHint =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown onboarding hint: $id")
    }
}

/** Chunk-level location retained only in local onboarding state. */
@ConsistentCopyVisibility
internal data class OnboardingPlace private constructor(
    val world: String,
    val chunkX: Int,
    val chunkZ: Int,
) {
    companion object {
        const val MAX_WORLD_LENGTH = 128
        const val MAX_ABS_CHUNK = 2_000_000

        fun fromBlock(
            world: String,
            blockX: Int,
            blockZ: Int,
        ): OnboardingPlace =
            fromChunk(world, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))

        fun fromChunk(
            world: String,
            chunkX: Int,
            chunkZ: Int,
        ): OnboardingPlace {
            val normalized = world.trim().lowercase(Locale.ROOT)
            require(normalized.isNotEmpty()) { "onboarding place world must not be blank" }
            require(normalized.length <= MAX_WORLD_LENGTH) { "onboarding place world is too long" }
            require(normalized.none(Char::isISOControl)) { "onboarding place world contains control characters" }
            require(chunkX in -MAX_ABS_CHUNK..MAX_ABS_CHUNK) { "onboarding chunk X is out of bounds" }
            require(chunkZ in -MAX_ABS_CHUNK..MAX_ABS_CHUNK) { "onboarding chunk Z is out of bounds" }
            return OnboardingPlace(normalized, chunkX, chunkZ)
        }
    }
}

internal data class OnboardingPlayerProgress(
    val milestones: MutableSet<OnboardingMilestone> = linkedSetOf(),
    val pendingHints: MutableList<OnboardingHint> = mutableListOf(),
    val deliveredHints: MutableSet<OnboardingHint> = linkedSetOf(),
    val homePlaces: MutableSet<OnboardingPlace> = linkedSetOf(),
    val footholdPlaces: MutableSet<OnboardingPlace> = linkedSetOf(),
    var updatedAt: Long = 0L,
) {
    fun copyMutable(): OnboardingPlayerProgress =
        OnboardingPlayerProgress(
            milestones = LinkedHashSet(milestones),
            pendingHints = pendingHints.toMutableList(),
            deliveredHints = LinkedHashSet(deliveredHints),
            homePlaces = LinkedHashSet(homePlaces),
            footholdPlaces = LinkedHashSet(footholdPlaces),
            updatedAt = updatedAt,
        )

    fun rememberHome(place: OnboardingPlace): Boolean {
        val changed = homePlaces.add(place)
        trimHomes()
        return changed
    }

    fun rememberFoothold(place: OnboardingPlace): Boolean {
        homePlaces.add(place)
        val changed = footholdPlaces.add(place)
        while (footholdPlaces.size > MAX_PLACES) {
            footholdPlaces.remove(footholdPlaces.first())
        }
        trimHomes()
        return changed
    }

    fun isFoothold(place: OnboardingPlace): Boolean = place in footholdPlaces

    private fun trimHomes() {
        while (homePlaces.size > MAX_PLACES) {
            val removable = homePlaces.firstOrNull { it !in footholdPlaces } ?: homePlaces.first()
            homePlaces.remove(removable)
        }
    }

    companion object {
        const val MAX_PLACES = 16
    }
}

internal data class OnboardingJourneyUpdate(
    val changed: Boolean,
    val addedMilestones: Set<OnboardingMilestone> = emptySet(),
    val queuedHints: List<OnboardingHint> = emptyList(),
    val recoveredFoothold: Boolean = false,
)

/** Pure, non-linear onboarding state transitions. Plugin listeners only provide verified outcomes. */
internal object OnboardingJourney {
    fun observe(
        progress: OnboardingPlayerProgress,
        milestone: OnboardingMilestone,
        now: Long,
        place: OnboardingPlace? = null,
        verifiedFoothold: Boolean = false,
    ): OnboardingJourneyUpdate {
        require(milestone != OnboardingMilestone.FOOTHOLD_COMPLETE) {
            "foothold-complete is derived from a matching home and Lands chunk"
        }
        require(!verifiedFoothold || milestone == OnboardingMilestone.HOME_CREATED) {
            "verifiedFoothold is only valid for a home observation"
        }
        if (milestone != OnboardingMilestone.FIRST_RTP && OnboardingMilestone.FIRST_RTP !in progress.milestones) {
            return OnboardingJourneyUpdate(changed = false)
        }

        val before = progress.copyMutable()
        when (milestone) {
            OnboardingMilestone.FIRST_RTP -> {
                if (progress.milestones.add(milestone)) queue(progress, OnboardingHint.FIRST_RTP)
            }

            OnboardingMilestone.HOME_CREATED -> {
                val home = requirePlace(milestone, place)
                val newHome = progress.rememberHome(home)
                val newMilestone = progress.milestones.add(milestone)
                when {
                    verifiedFoothold -> completeFoothold(progress, home)
                    progress.footholdPlaces.isNotEmpty() -> Unit
                    OnboardingMilestone.LAND_CLAIMED in progress.milestones -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.LAND_CLAIMED)
                        queue(progress, OnboardingHint.FOOTHOLD_MISMATCH)
                    }
                    newHome || newMilestone -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.LAND_CLAIMED)
                        queue(progress, OnboardingHint.HOME_CREATED)
                    }
                }
            }

            OnboardingMilestone.LAND_CLAIMED -> {
                val land = requirePlace(milestone, place)
                val newMilestone = progress.milestones.add(milestone)
                when {
                    land in progress.homePlaces -> completeFoothold(progress, land)
                    progress.footholdPlaces.isNotEmpty() -> Unit
                    OnboardingMilestone.HOME_CREATED in progress.milestones -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.HOME_CREATED)
                        queue(progress, OnboardingHint.FOOTHOLD_MISMATCH)
                    }
                    newMilestone -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.HOME_CREATED)
                        queue(progress, OnboardingHint.LAND_CLAIMED)
                    }
                }
            }

            OnboardingMilestone.BUILD_BOOK_OPENED -> {
                val build = requirePlace(milestone, place)
                progress.milestones.add(milestone)
                when {
                    progress.isFoothold(build) -> supersedeRecovery(progress)
                    progress.footholdPlaces.isNotEmpty() -> queue(progress, OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD)
                    OnboardingMilestone.HOME_CREATED in progress.milestones &&
                        OnboardingMilestone.LAND_CLAIMED in progress.milestones ->
                        queue(progress, OnboardingHint.FOOTHOLD_MISMATCH)
                    OnboardingMilestone.HOME_CREATED in progress.milestones -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.HOME_CREATED)
                        queue(progress, OnboardingHint.BUILD_BOOK_MISSING_LAND)
                    }
                    OnboardingMilestone.LAND_CLAIMED in progress.milestones -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.LAND_CLAIMED)
                        queue(progress, OnboardingHint.BUILD_BOOK_MISSING_HOME)
                    }
                    else -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP)
                        queue(progress, OnboardingHint.BUILD_BOOK_MISSING_BOTH)
                    }
                }
            }

            OnboardingMilestone.AUTOBUILD_STARTED -> {
                val build = requirePlace(milestone, place)
                if (progress.isFoothold(build)) {
                    progress.milestones.add(milestone)
                    supersedeRecovery(progress)
                }
            }

            OnboardingMilestone.AUTOBUILD_COMPLETE -> {
                val build = requirePlace(milestone, place)
                if (progress.isFoothold(build)) {
                    progress.milestones.add(OnboardingMilestone.AUTOBUILD_STARTED)
                    if (progress.milestones.add(milestone)) {
                        supersedeRecovery(progress)
                        queue(progress, OnboardingHint.AUTOBUILD_COMPLETE)
                    }
                }
            }

            OnboardingMilestone.FOOTHOLD_COMPLETE -> error("handled by precondition")
        }

        val changed =
            progress.milestones != before.milestones ||
                progress.pendingHints != before.pendingHints ||
                progress.deliveredHints != before.deliveredHints ||
                progress.homePlaces != before.homePlaces ||
                progress.footholdPlaces != before.footholdPlaces
        if (!changed) return OnboardingJourneyUpdate(changed = false)

        progress.updatedAt = now
        val addedMilestones = progress.milestones - before.milestones
        return OnboardingJourneyUpdate(
            changed = true,
            addedMilestones = addedMilestones,
            queuedHints = progress.pendingHints.filterNot(before.pendingHints::contains),
            recoveredFoothold =
                OnboardingMilestone.FOOTHOLD_COMPLETE in addedMilestones &&
                    before.deliveredHints.any(FOOTHOLD_RECOVERY_HINTS::contains),
        )
    }

    private fun completeFoothold(
        progress: OnboardingPlayerProgress,
        place: OnboardingPlace,
    ) {
        val firstCompletion = progress.milestones.add(OnboardingMilestone.FOOTHOLD_COMPLETE)
        progress.milestones += OnboardingMilestone.HOME_CREATED
        progress.milestones += OnboardingMilestone.LAND_CLAIMED
        progress.rememberFoothold(place)
        supersedeRecovery(progress)
        if (firstCompletion) queue(progress, OnboardingHint.FOOTHOLD_COMPLETE)
    }

    private fun supersedeRecovery(progress: OnboardingPlayerProgress) {
        supersedePending(
            progress,
            OnboardingHint.FIRST_RTP,
            OnboardingHint.HOME_CREATED,
            OnboardingHint.LAND_CLAIMED,
            OnboardingHint.FOOTHOLD_MISMATCH,
            OnboardingHint.BUILD_BOOK_MISSING_HOME,
            OnboardingHint.BUILD_BOOK_MISSING_LAND,
            OnboardingHint.BUILD_BOOK_MISSING_BOTH,
            OnboardingHint.BUILD_BOOK_OUTSIDE_FOOTHOLD,
        )
    }

    private fun queue(
        progress: OnboardingPlayerProgress,
        hint: OnboardingHint,
    ) {
        if (hint !in progress.deliveredHints && hint !in progress.pendingHints) {
            progress.pendingHints += hint
        }
    }

    private fun supersedePending(
        progress: OnboardingPlayerProgress,
        vararg hints: OnboardingHint,
    ) {
        progress.pendingHints.removeAll(hints.toSet())
    }

    private fun requirePlace(
        milestone: OnboardingMilestone,
        place: OnboardingPlace?,
    ): OnboardingPlace = requireNotNull(place) { "${milestone.id} requires a chunk location" }

    private val FOOTHOLD_RECOVERY_HINTS =
        setOf(
            OnboardingHint.FOOTHOLD_MISMATCH,
            OnboardingHint.BUILD_BOOK_MISSING_HOME,
            OnboardingHint.BUILD_BOOK_MISSING_LAND,
            OnboardingHint.BUILD_BOOK_MISSING_BOTH,
        )
}
