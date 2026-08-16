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
        val update = OnboardingJourney.observe(progress, milestone, now)
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
        const val CURRENT_VERSION = 1
        const val MAX_PLAYERS = 100_000
        const val MAX_FILE_BYTES = 64L * 1024L * 1024L

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
                require(stored.deliveredHints.distinct().size == stored.deliveredHints.size) {
                    "Duplicate delivered onboarding hint for $playerId"
                }
                val milestones = stored.milestones.mapTo(linkedSetOf(), OnboardingMilestone::fromId)
                val pending = stored.pendingHints.map(OnboardingHint::fromId)
                val delivered = stored.deliveredHints.mapTo(linkedSetOf(), OnboardingHint::fromId)
                require(milestones.size <= OnboardingMilestone.entries.size) { "Too many onboarding milestones for $playerId" }
                require(pending.size <= OnboardingHint.entries.size) { "Too many pending onboarding hints for $playerId" }
                require(delivered.size <= OnboardingHint.entries.size) { "Too many delivered onboarding hints for $playerId" }
                require(pending.distinct().size == pending.size) { "Duplicate pending onboarding hint for $playerId" }
                require(pending.none(delivered::contains)) { "Onboarding hint is both pending and delivered for $playerId" }
                require(stored.updatedAt >= 0L) { "Negative onboarding update timestamp for $playerId" }
                require(
                    milestones.isEmpty() || OnboardingMilestone.FIRST_RTP in milestones,
                ) { "Onboarding progress exists without first RTP for $playerId" }
                players[playerId] =
                    OnboardingPlayerProgress(
                        milestones = milestones,
                        pendingHints = pending.toMutableList(),
                        deliveredHints = delivered,
                        updatedAt = stored.updatedAt,
                    )
            }
            return OnboardingStore(path, gson, players)
        }
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
    val updatedAt: Long = 0L,
)
