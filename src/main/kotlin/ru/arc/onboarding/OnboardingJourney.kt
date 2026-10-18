package ru.arc.onboarding

import java.util.Collections
import java.util.Locale
import java.util.UUID

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

internal enum class OnboardingStatus { OFFERED, ACTIVE, COMPLETED, STOPPED }

/** Normalized chunk identity shared by home, land and construction observations. */
@ConsistentCopyVisibility
internal data class OnboardingPlace private constructor(
    val world: String,
    val chunkX: Int,
    val chunkZ: Int,
) {
    companion object {
        const val MAX_WORLD_LENGTH = 128
        const val MAX_ABS_CHUNK = 2_000_000

        fun fromBlock(world: String, blockX: Int, blockZ: Int): OnboardingPlace =
            fromChunk(world, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))

        fun fromChunk(world: String, chunkX: Int, chunkZ: Int): OnboardingPlace {
            val normalized = world.trim().lowercase(Locale.ROOT)
            require(normalized.isNotEmpty()) { "Onboarding place world must not be blank" }
            require(normalized.length <= MAX_WORLD_LENGTH) { "Onboarding place world is too long" }
            require(normalized.none(Char::isISOControl)) { "Onboarding place world contains control characters" }
            require(chunkX in -MAX_ABS_CHUNK..MAX_ABS_CHUNK) { "Onboarding chunk X is out of bounds" }
            require(chunkZ in -MAX_ABS_CHUNK..MAX_ABS_CHUNK) { "Onboarding chunk Z is out of bounds" }
            return OnboardingPlace(normalized, chunkX, chunkZ)
        }
    }
}

/** An explicit opt-in quest run. Collections are detached, immutable snapshots. */
@ConsistentCopyVisibility
internal data class OnboardingProgress private constructor(
    val version: Int,
    val runId: String,
    val status: OnboardingStatus,
    val milestones: Set<OnboardingMilestone>,
    val homePlaces: Set<OnboardingPlace>,
    val landPlaces: Set<OnboardingPlace>,
    val buildOperationId: String? = null,
) {
    val footholdPlaces: Set<OnboardingPlace> get() = immutableSet(homePlaces.intersect(landPlaces))

    fun isFoothold(place: OnboardingPlace): Boolean = place in homePlaces && place in landPlaces

    internal fun with(
        status: OnboardingStatus = this.status,
        milestones: Set<OnboardingMilestone> = this.milestones,
        homePlaces: Set<OnboardingPlace> = this.homePlaces,
        landPlaces: Set<OnboardingPlace> = this.landPlaces,
        buildOperationId: String? = this.buildOperationId,
    ): OnboardingProgress = create(version, runId, status, milestones, homePlaces, landPlaces, buildOperationId)

    companion object {
        const val VERSION = 1
        const val MAX_PLACES = 16

        fun offered(runId: String = UUID.randomUUID().toString()): OnboardingProgress =
            create(VERSION, runId, OnboardingStatus.OFFERED, emptySet(), emptySet(), emptySet())

        internal fun create(
            version: Int,
            runId: String,
            status: OnboardingStatus,
            milestones: Set<OnboardingMilestone>,
            homePlaces: Set<OnboardingPlace>,
            landPlaces: Set<OnboardingPlace>,
            buildOperationId: String? = null,
        ): OnboardingProgress {
            require(version == VERSION) { "Unsupported onboarding quest version" }
            require(UUID.fromString(runId).toString() == runId) { "Invalid onboarding run identity" }
            require(homePlaces.size <= MAX_PLACES && landPlaces.size <= MAX_PLACES) { "Too many onboarding places" }
            require(milestones.isEmpty() || OnboardingMilestone.FIRST_RTP in milestones) { "Onboarding progress requires first RTP" }
            require(homePlaces.isNotEmpty() == (OnboardingMilestone.HOME_CREATED in milestones)) { "Onboarding home state disagrees" }
            require(landPlaces.isNotEmpty() == (OnboardingMilestone.LAND_CLAIMED in milestones)) { "Onboarding land state disagrees" }
            val hasFoothold = homePlaces.any(landPlaces::contains)
            require(hasFoothold == (OnboardingMilestone.FOOTHOLD_COMPLETE in milestones)) { "Onboarding foothold state disagrees" }
            require(milestones.none { it in BUILD_MILESTONES } || hasFoothold) { "Onboarding construction requires a foothold" }
            buildOperationId?.let(::validateBuildOperationId)
            require((OnboardingMilestone.AUTOBUILD_STARTED in milestones) == (buildOperationId != null)) { "Onboarding construction operation disagrees" }
            val complete = OnboardingMilestone.AUTOBUILD_COMPLETE in milestones
            require(!complete || OnboardingMilestone.AUTOBUILD_STARTED in milestones) { "Onboarding completion requires a start" }
            require(status != OnboardingStatus.OFFERED || milestones.isEmpty()) { "An offered quest cannot have progress" }
            require(status != OnboardingStatus.COMPLETED || complete) { "A completed quest requires construction completion" }
            require(!complete || status in setOf(OnboardingStatus.COMPLETED, OnboardingStatus.STOPPED)) { "Completed construction requires a terminal quest" }
            return OnboardingProgress(version, runId, status, immutableSet(milestones), immutableSet(homePlaces), immutableSet(landPlaces), buildOperationId)
        }

        private val BUILD_MILESTONES = setOf(
            OnboardingMilestone.BUILD_BOOK_OPENED,
            OnboardingMilestone.AUTOBUILD_STARTED,
            OnboardingMilestone.AUTOBUILD_COMPLETE,
        )
    }
}

/** Pure quest transitions; completion requires the exact construction start persisted in this run. */
internal object OnboardingJourney {
    fun observe(
        progress: OnboardingProgress,
        milestone: OnboardingMilestone,
        place: OnboardingPlace? = null,
        verifiedFoothold: Boolean = false,
        operationId: String? = null,
    ): OnboardingProgress {
        require(milestone != OnboardingMilestone.FOOTHOLD_COMPLETE) { "Foothold completion is derived from a matching home and land chunk" }
        require(!verifiedFoothold || milestone == OnboardingMilestone.HOME_CREATED) { "Only a verified home can recover a foothold" }
        if (progress.status != OnboardingStatus.ACTIVE) return progress
        if (milestone != OnboardingMilestone.FIRST_RTP && OnboardingMilestone.FIRST_RTP !in progress.milestones) return progress

        val milestones = progress.milestones.toMutableSet()
        var homes = progress.homePlaces
        var lands = progress.landPlaces
        var buildOperationId = progress.buildOperationId
        when (milestone) {
            OnboardingMilestone.FIRST_RTP -> milestones += milestone
            OnboardingMilestone.HOME_CREATED -> {
                val home = requireNotNull(place) { "${milestone.id} requires a chunk location" }
                homes = remember(homes, home, progress.footholdPlaces)
                milestones += milestone
                if (verifiedFoothold) {
                    lands = remember(lands, home, progress.footholdPlaces)
                    milestones += OnboardingMilestone.LAND_CLAIMED
                }
            }
            OnboardingMilestone.LAND_CLAIMED -> {
                lands = remember(lands, requireNotNull(place) { "${milestone.id} requires a chunk location" }, progress.footholdPlaces)
                milestones += milestone
            }
            OnboardingMilestone.BUILD_BOOK_OPENED, OnboardingMilestone.AUTOBUILD_STARTED, OnboardingMilestone.AUTOBUILD_COMPLETE -> {
                val build = requireNotNull(place) { "${milestone.id} requires a chunk location" }
                if (!progress.isFoothold(build)) return progress
                if (milestone != OnboardingMilestone.BUILD_BOOK_OPENED) {
                    val operation = operationId ?: return progress
                    validateBuildOperationId(operation)
                    if (milestone == OnboardingMilestone.AUTOBUILD_STARTED) {
                        buildOperationId = operation
                    } else if (OnboardingMilestone.AUTOBUILD_STARTED !in milestones || buildOperationId != operation) {
                        return progress
                    }
                }
                milestones += milestone
            }
            OnboardingMilestone.FOOTHOLD_COMPLETE -> error("Handled by precondition")
        }
        if (homes.any(lands::contains)) milestones += OnboardingMilestone.FOOTHOLD_COMPLETE
        val status = if (OnboardingMilestone.AUTOBUILD_COMPLETE in milestones) OnboardingStatus.COMPLETED else progress.status
        return progress.with(status, milestones, homes, lands, buildOperationId)
    }

    private fun remember(places: Set<OnboardingPlace>, place: OnboardingPlace, footholds: Set<OnboardingPlace>): Set<OnboardingPlace> {
        if (place in places) return places
        val removable = places.firstOrNull { it !in footholds }
        if (places.size == OnboardingProgress.MAX_PLACES && removable == null) return places
        return (if (places.size == OnboardingProgress.MAX_PLACES) places - removable!! else places) + place
    }
}

private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))

private fun validateBuildOperationId(operationId: String) {
    require(UUID.fromString(operationId).toString() == operationId) { "Invalid onboarding construction operation identity" }
}
