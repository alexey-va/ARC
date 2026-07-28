package ru.arc.rtp

import ru.arc.util.Logging.info
import java.nio.file.Path
import java.util.UUID

object RtpPlayerRegistry {
    private lateinit var store: RtpPlayerStore

    fun initialize(pluginDataPath: Path) {
        store = RtpPlayerStore.open(pluginDataPath.resolve("data/rtp-players.json"))
        info(
            "Loaded first-RTP registry: {} players across {} worlds",
            store.playerCount(),
            store.worldCount(),
        )
    }

    fun state(
        playerId: UUID,
        worldName: String,
    ): PlayerRtpState = store.state(playerId, worldName)

    fun markTeleported(
        playerId: UUID,
        worldName: String,
    ): Boolean = store.markTeleported(playerId, worldName)

    fun reset(
        playerId: UUID,
        worldName: String? = null,
    ): PlayerRtpResetResult = store.reset(playerId, worldName)
}
