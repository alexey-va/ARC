package ru.arc.rtp

import com.google.gson.Gson
import ru.arc.util.Common
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID

data class PlayerRtpState(
    val hasTeleported: Boolean,
    val hasTeleportedToWorld: Boolean,
)

data class PlayerRtpResetResult(
    val globalRemoved: Boolean,
    val worldsRemoved: List<String>,
) {
    val changed: Boolean
        get() = globalRemoved || worldsRemoved.isNotEmpty()
}

/**
 * Small persistent registry for the cross-server first-RTP flow.
 *
 * This deliberately stores only UUID sets. Old Denizen last-location flags are
 * not part of the migration; returning players fall back to the target world's
 * spawn until a separate location-history feature is introduced.
 */
class RtpPlayerStore private constructor(
    private val path: Path,
    private val gson: Gson,
    private val teleportedPlayers: MutableSet<UUID>,
    private val teleportedByWorld: MutableMap<String, MutableSet<UUID>>,
) {
    @Synchronized
    fun state(
        playerId: UUID,
        worldName: String,
    ): PlayerRtpState {
        val worldKey = normalizeWorld(worldName)
        return PlayerRtpState(
            hasTeleported = playerId in teleportedPlayers,
            hasTeleportedToWorld = playerId in teleportedByWorld[worldKey].orEmpty(),
        )
    }

    /**
     * Records a successful RTP and persists the complete registry atomically.
     *
     * Returns true when the registry changed.
     */
    @Synchronized
    fun markTeleported(
        playerId: UUID,
        worldName: String,
    ): Boolean {
        val worldKey = normalizeWorld(worldName)
        val playerAdded = teleportedPlayers.add(playerId)
        val worldAdded = teleportedByWorld.getOrPut(worldKey) { linkedSetOf() }.add(playerId)
        if (!playerAdded && !worldAdded) return false

        runCatching(::save).onFailure {
            if (playerAdded) teleportedPlayers.remove(playerId)
            if (worldAdded) {
                teleportedByWorld[worldKey]?.remove(playerId)
                if (teleportedByWorld[worldKey].isNullOrEmpty()) teleportedByWorld.remove(worldKey)
            }
        }.getOrThrow()
        return true
    }

    /**
     * Removes first-RTP state and persists the complete registry atomically.
     *
     * With [worldName] only the per-world marker is removed. With null the
     * global marker and every per-world marker are removed.
     */
    @Synchronized
    fun reset(
        playerId: UUID,
        worldName: String? = null,
    ): PlayerRtpResetResult {
        val playersBefore = teleportedPlayers.toSet()
        val worldsBefore = teleportedByWorld.mapValues { (_, players) -> players.toSet() }

        val globalRemoved = if (worldName == null) teleportedPlayers.remove(playerId) else false
        val removedWorlds = mutableListOf<String>()
        if (worldName == null) {
            teleportedByWorld.forEach { (world, players) ->
                if (players.remove(playerId)) removedWorlds += world
            }
        } else {
            val worldKey = normalizeWorld(worldName)
            if (teleportedByWorld[worldKey]?.remove(playerId) == true) {
                removedWorlds += worldKey
            }
        }
        teleportedByWorld.entries.removeIf { it.value.isEmpty() }

        val result = PlayerRtpResetResult(globalRemoved, removedWorlds.sorted())
        if (!result.changed) return result

        runCatching(::save).onFailure {
            teleportedPlayers.clear()
            teleportedPlayers.addAll(playersBefore)
            teleportedByWorld.clear()
            worldsBefore.forEach { (world, players) ->
                teleportedByWorld[world] = players.toMutableSet()
            }
        }.getOrThrow()
        return result
    }

    @Synchronized
    fun playerCount(): Int = teleportedPlayers.size

    @Synchronized
    fun worldCount(): Int = teleportedByWorld.size

    private fun save() {
        Files.createDirectories(path.parent)
        val snapshot =
            RtpPlayerFile(
                version = CURRENT_VERSION,
                teleportedPlayers = teleportedPlayers.map(UUID::toString).sorted(),
                teleportedByWorld =
                    teleportedByWorld
                        .toSortedMap()
                        .mapValues { (_, players) -> players.map(UUID::toString).sorted() },
            )
        val temp = Files.createTempFile(path.parent, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(
                temp,
                gson.toJson(snapshot),
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun open(
            path: Path,
            gson: Gson = Common.prettyGson,
        ): RtpPlayerStore {
            if (!Files.exists(path)) {
                return RtpPlayerStore(path, gson, linkedSetOf(), linkedMapOf())
            }

            val model =
                requireNotNull(gson.fromJson(Files.readString(path), RtpPlayerFile::class.java)) {
                    "RTP player registry is empty: $path"
                }
            require(model.version == CURRENT_VERSION) {
                "Unsupported RTP player registry version ${model.version}; expected $CURRENT_VERSION"
            }

            val players = model.teleportedPlayers.mapTo(linkedSetOf(), ::parseUuid)
            val byWorld: MutableMap<String, MutableSet<UUID>> = linkedMapOf()
            model.teleportedByWorld.forEach { (worldName, playerIds) ->
                val worldKey = normalizeWorld(worldName)
                val playersForWorld = byWorld.getOrPut(worldKey) { linkedSetOf() }
                playerIds.mapTo(playersForWorld, ::parseUuid)
                }
            return RtpPlayerStore(path, gson, players, byWorld)
        }

        private fun parseUuid(value: String): UUID =
            runCatching { UUID.fromString(value) }
                .getOrElse { throw IllegalArgumentException("Invalid player UUID in RTP registry", it) }

        private fun normalizeWorld(value: String): String =
            value.trim().lowercase(Locale.ROOT).also {
                require(it.isNotEmpty()) { "World name must not be blank" }
            }
    }
}

private data class RtpPlayerFile(
    val version: Int = RtpPlayerStore.CURRENT_VERSION,
    val teleportedPlayers: List<String> = emptyList(),
    val teleportedByWorld: Map<String, List<String>> = emptyMap(),
)
