package ru.arc.onboarding

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

internal enum class OnboardingHint(val id: String) {
    FIRST_RTP("first-rtp"),
    HOME_CREATED("home-created"),
    LAND_CLAIMED("land-claimed"),
    FOOTHOLD_COMPLETE("foothold-complete"),
    BUILD_BOOK_MISSING_HOME("build-book-missing-home"),
    BUILD_BOOK_MISSING_LAND("build-book-missing-land"),
    BUILD_BOOK_MISSING_BOTH("build-book-missing-both"),
    AUTOBUILD_COMPLETE("autobuild-complete");

    companion object {
        fun fromId(id: String): OnboardingHint =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown onboarding hint: $id")
    }
}

internal data class OnboardingPlayerProgress(
    val milestones: MutableSet<OnboardingMilestone> = linkedSetOf(),
    val pendingHints: MutableList<OnboardingHint> = mutableListOf(),
    val deliveredHints: MutableSet<OnboardingHint> = linkedSetOf(),
    var updatedAt: Long = 0L,
) {
    fun copyMutable(): OnboardingPlayerProgress =
        OnboardingPlayerProgress(
            milestones = milestones.toMutableSet(),
            pendingHints = pendingHints.toMutableList(),
            deliveredHints = deliveredHints.toMutableSet(),
            updatedAt = updatedAt,
        )
}

internal data class OnboardingJourneyUpdate(
    val changed: Boolean,
    val addedMilestones: Set<OnboardingMilestone> = emptySet(),
    val queuedHints: List<OnboardingHint> = emptyList(),
)

/** Pure, non-linear onboarding state transitions. Plugin listeners only provide verified outcomes. */
internal object OnboardingJourney {
    fun observe(
        progress: OnboardingPlayerProgress,
        milestone: OnboardingMilestone,
        now: Long,
    ): OnboardingJourneyUpdate {
        if (milestone != OnboardingMilestone.FIRST_RTP && OnboardingMilestone.FIRST_RTP !in progress.milestones) {
            return OnboardingJourneyUpdate(changed = false)
        }
        if (!progress.milestones.add(milestone)) return OnboardingJourneyUpdate(changed = false)

        val addedMilestones = linkedSetOf(milestone)
        val hints = mutableListOf<OnboardingHint>()
        when (milestone) {
            OnboardingMilestone.FIRST_RTP -> hints += OnboardingHint.FIRST_RTP

            OnboardingMilestone.HOME_CREATED -> {
                if (OnboardingMilestone.LAND_CLAIMED in progress.milestones) {
                    completeFoothold(progress, addedMilestones, hints)
                } else {
                    supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.LAND_CLAIMED)
                    hints += OnboardingHint.HOME_CREATED
                }
            }

            OnboardingMilestone.LAND_CLAIMED -> {
                if (OnboardingMilestone.HOME_CREATED in progress.milestones) {
                    completeFoothold(progress, addedMilestones, hints)
                } else {
                    supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.HOME_CREATED)
                    hints += OnboardingHint.LAND_CLAIMED
                }
            }

            OnboardingMilestone.BUILD_BOOK_OPENED -> {
                val hasHome = OnboardingMilestone.HOME_CREATED in progress.milestones
                val hasLand = OnboardingMilestone.LAND_CLAIMED in progress.milestones
                when {
                    hasHome && hasLand -> Unit
                    hasHome -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.HOME_CREATED)
                        hints += OnboardingHint.BUILD_BOOK_MISSING_LAND
                    }
                    hasLand -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP, OnboardingHint.LAND_CLAIMED)
                        hints += OnboardingHint.BUILD_BOOK_MISSING_HOME
                    }
                    else -> {
                        supersedePending(progress, OnboardingHint.FIRST_RTP)
                        hints += OnboardingHint.BUILD_BOOK_MISSING_BOTH
                    }
                }
            }

            OnboardingMilestone.AUTOBUILD_COMPLETE -> hints += OnboardingHint.AUTOBUILD_COMPLETE
            OnboardingMilestone.FOOTHOLD_COMPLETE,
            OnboardingMilestone.AUTOBUILD_STARTED,
            -> Unit
        }

        hints.forEach { hint ->
            if (hint !in progress.deliveredHints && hint !in progress.pendingHints) {
                progress.pendingHints += hint
            }
        }
        progress.updatedAt = now
        return OnboardingJourneyUpdate(
            changed = true,
            addedMilestones = addedMilestones,
            queuedHints = hints.filter { it in progress.pendingHints },
        )
    }

    private fun completeFoothold(
        progress: OnboardingPlayerProgress,
        addedMilestones: MutableSet<OnboardingMilestone>,
        hints: MutableList<OnboardingHint>,
    ) {
        if (progress.milestones.add(OnboardingMilestone.FOOTHOLD_COMPLETE)) {
            addedMilestones += OnboardingMilestone.FOOTHOLD_COMPLETE
            supersedePending(
                progress,
                OnboardingHint.FIRST_RTP,
                OnboardingHint.HOME_CREATED,
                OnboardingHint.LAND_CLAIMED,
                OnboardingHint.BUILD_BOOK_MISSING_HOME,
                OnboardingHint.BUILD_BOOK_MISSING_LAND,
                OnboardingHint.BUILD_BOOK_MISSING_BOTH,
            )
            hints += OnboardingHint.FOOTHOLD_COMPLETE
        }
    }

    private fun supersedePending(
        progress: OnboardingPlayerProgress,
        vararg hints: OnboardingHint,
    ) {
        progress.pendingHints.removeAll(hints.toSet())
    }
}
