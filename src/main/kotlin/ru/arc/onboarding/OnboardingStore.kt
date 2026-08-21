package ru.arc.onboarding

import com.google.gson.Gson
import ru.arc.util.Common
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal class OnboardingStore private constructor(
    private val path: Path,
    private val gson: Gson,
    private val players: MutableMap<UUID, OnboardingPlayerProgress>,
) {
    @Synchronized
    fun observe(
        playerId: UUID,
        milestone: OnboardingMilestone,
        now: Long,
        place: OnboardingPlace? = null,
        verifiedFoothold: Boolean = false,
    ): OnboardingJourneyUpdate {
        val existing = players[playerId]
        if (existing == null && milestone != OnboardingMilestone.FIRST_RTP) {
            return OnboardingJourneyUpdate(changed = false)
        }
        require(existing != null || players.size < MAX_PLAYERS) {
            "Onboarding state reached its hard player cap of $MAX_PLAYERS"
        }
        val before = existing?.copyMutable()
        val progress = existing ?: OnboardingPlayerProgress().also { players[playerId] = it }
        val update = OnboardingJourney.observe(progress, milestone, now, place, verifiedFoothold)
        if (!update.changed) {
            if (existing == null) players.remove(playerId)
            return update
        }

        runCatching(::save).onFailure {
            if (before == null) players.remove(playerId) else players[playerId] = before
        }.getOrThrow()
        return update
    }

    @Synchronized
    fun nextHint(playerId: UUID): OnboardingHint? = players[playerId]?.pendingHints?.firstOrNull()

    @Synchronized
    fun markDelivered(
        playerId: UUID,
        hint: OnboardingHint,
        now: Long,
    ): Boolean {
        val progress = players[playerId] ?: return false
        if (hint !in progress.pendingHints) return false
        val before = progress.copyMutable()
        progress.pendingHints.remove(hint)
        progress.deliveredHints += hint
        progress.updatedAt = now
        runCatching(::save).onFailure { players[playerId] = before }.getOrThrow()
        return true
    }

    @Synchronized
    fun playerCount(): Int = players.size

    @Synchronized
    fun reset(playerId: UUID): Boolean {
        val removed = players.remove(playerId) ?: return false
        runCatching(::save).onFailure { players[playerId] = removed }.getOrThrow()
        return true
    }

    private fun save() {
        Files.createDirectories(path.parent)
        val snapshot =
            OnboardingStoreFile(
                version = CURRENT_VERSION,
                players =
                    players
                        .toSortedMap(compareBy(UUID::toString))
                        .mapKeys { it.key.toString() }
                        .mapValues { (_, progress) ->
                            OnboardingPlayerFile(
                                milestones = progress.milestones.map(OnboardingMilestone::id).sorted(),
                                pendingHints = progress.pendingHints.map(OnboardingHint::id),
                                deliveredHints = progress.deliveredHints.map(OnboardingHint::id).sorted(),
                                homePlaces = progress.homePlaces.sortedWith(PLACE_COMPARATOR).map(::toFile),
                                footholdPlaces = progress.footholdPlaces.sortedWith(PLACE_COMPARATOR).map(::toFile),
                                updatedAt = progress.updatedAt,
                            )
                        },
            )
        val temp = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(temp, gson.toJson(snapshot), StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val CURRENT_VERSION = 2
        const val MAX_PLAYERS = 100_000
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L

        private val PLACE_COMPARATOR =
            compareBy<OnboardingPlace>(OnboardingPlace::world, OnboardingPlace::chunkX, OnboardingPlace::chunkZ)

        fun open(
            path: Path,
            gson: Gson = Common.prettyGson,
        ): OnboardingStore {
            if (!Files.exists(path)) return OnboardingStore(path, gson, linkedMapOf())
            require(Files.size(path) <= MAX_FILE_BYTES) {
                "Onboarding state exceeds its hard file-size cap of $MAX_FILE_BYTES bytes"
            }
            val model =
                requireNotNull(gson.fromJson(Files.readString(path), OnboardingStoreFile::class.java)) {
                    "Onboarding state is empty: $path"
                }
            require(model.version == CURRENT_VERSION) {
                "Unsupported onboarding state version ${model.version}; expected $CURRENT_VERSION"
            }
            require(model.players.size <= MAX_PLAYERS) {
                "Onboarding state exceeds its hard player cap of $MAX_PLAYERS"
            }
            val players = linkedMapOf<UUID, OnboardingPlayerProgress>()
            model.players.forEach { (rawId, stored) ->
                val playerId =
                    runCatching { UUID.fromString(rawId) }
                        .getOrElse { throw IllegalArgumentException("Invalid player UUID in onboarding state", it) }
                require(stored.milestones.distinct().size == stored.milestones.size) {
                    "Duplicate onboarding milestone for $playerId"
                }
                require(stored.pendingHints.distinct().size == stored.pendingHints.size) {
                    "Duplicate pending onboarding hint for $playerId"
                }
                require(stored.deliveredHints.distinct().size == stored.deliveredHints.size) {
                    "Duplicate delivered onboarding hint for $playerId"
                }
                require(stored.homePlaces.size <= OnboardingPlayerProgress.MAX_PLACES) {
                    "Too many onboarding home places for $playerId"
                }
                require(stored.footholdPlaces.size <= OnboardingPlayerProgress.MAX_PLACES) {
                    "Too many onboarding foothold places for $playerId"
                }
                require(stored.homePlaces.distinct().size == stored.homePlaces.size) {
                    "Duplicate onboarding home place for $playerId"
                }
                require(stored.footholdPlaces.distinct().size == stored.footholdPlaces.size) {
                    "Duplicate onboarding foothold place for $playerId"
                }

                val milestones = stored.milestones.mapTo(linkedSetOf(), OnboardingMilestone::fromId)
                val pending = stored.pendingHints.map(OnboardingHint::fromId)
                val delivered = stored.deliveredHints.mapTo(linkedSetOf(), OnboardingHint::fromId)
                val homes = stored.homePlaces.mapTo(linkedSetOf(), ::fromFile)
                val footholds = stored.footholdPlaces.mapTo(linkedSetOf(), ::fromFile)

                require(milestones.size <= OnboardingMilestone.entries.size) { "Too many onboarding milestones for $playerId" }
                require(pending.size <= OnboardingHint.entries.size) { "Too many pending onboarding hints for $playerId" }
                require(delivered.size <= OnboardingHint.entries.size) { "Too many delivered onboarding hints for $playerId" }
                require(pending.none(delivered::contains)) { "Onboarding hint is both pending and delivered for $playerId" }
                require(stored.updatedAt >= 0L) { "Negative onboarding update timestamp for $playerId" }
                require(milestones.isEmpty() || OnboardingMilestone.FIRST_RTP in milestones) {
                    "Onboarding progress exists without first RTP for $playerId"
                }
                require(homes.isEmpty() == (OnboardingMilestone.HOME_CREATED !in milestones)) {
                    "Onboarding home milestone and places disagree for $playerId"
                }
                require(footholds.all(homes::contains)) { "Onboarding foothold is missing its home for $playerId" }
                require(footholds.isEmpty() == (OnboardingMilestone.FOOTHOLD_COMPLETE !in milestones)) {
                    "Onboarding foothold milestone and places disagree for $playerId"
                }
                if (footholds.isNotEmpty()) {
                    require(OnboardingMilestone.LAND_CLAIMED in milestones) {
                        "Onboarding foothold exists without a land claim for $playerId"
                    }
                }
                if (OnboardingMilestone.AUTOBUILD_STARTED in milestones) {
                    require(footholds.isNotEmpty()) { "Onboarding build started without a foothold for $playerId" }
                }
                if (OnboardingMilestone.AUTOBUILD_COMPLETE in milestones) {
                    require(OnboardingMilestone.AUTOBUILD_STARTED in milestones) {
                        "Onboarding build completed without a start for $playerId"
                    }
                }

                players[playerId] =
                    OnboardingPlayerProgress(
                        milestones = milestones,
                        pendingHints = pending.toMutableList(),
                        deliveredHints = delivered,
                        homePlaces = homes,
                        footholdPlaces = footholds,
                        updatedAt = stored.updatedAt,
                    )
            }
            return OnboardingStore(path, gson, players)
        }

        private fun toFile(place: OnboardingPlace): OnboardingPlaceFile =
            OnboardingPlaceFile(place.world, place.chunkX, place.chunkZ)

        private fun fromFile(place: OnboardingPlaceFile): OnboardingPlace =
            OnboardingPlace.fromChunk(place.world, place.chunkX, place.chunkZ)
    }
}

private data class OnboardingStoreFile(
    val version: Int = OnboardingStore.CURRENT_VERSION,
    val players: Map<String, OnboardingPlayerFile> = emptyMap(),
)

private data class OnboardingPlayerFile(
    val milestones: List<String> = emptyList(),
    val pendingHints: List<String> = emptyList(),
    val deliveredHints: List<String> = emptyList(),
    val homePlaces: List<OnboardingPlaceFile> = emptyList(),
    val footholdPlaces: List<OnboardingPlaceFile> = emptyList(),
    val updatedAt: Long = 0L,
)

private data class OnboardingPlaceFile(
    val world: String = "",
    val chunkX: Int = 0,
    val chunkZ: Int = 0,
)
