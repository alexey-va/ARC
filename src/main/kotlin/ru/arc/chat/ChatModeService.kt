package ru.arc.chat

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ChatMode {
    LOCAL,
    GLOBAL,
}

object ChatModeService {
    private val globalPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun getMode(playerId: UUID): ChatMode =
        if (playerId in globalPlayers) ChatMode.GLOBAL else ChatMode.LOCAL

    fun setMode(
        playerId: UUID,
        mode: ChatMode,
    ) {
        when (mode) {
            ChatMode.GLOBAL -> globalPlayers.add(playerId)
            ChatMode.LOCAL -> globalPlayers.remove(playerId)
        }
    }
}
