package ru.arc.hooks

import com.olziedev.playerwarps.api.PlayerWarpsAPI
import com.olziedev.playerwarps.api.player.WPlayer
import java.util.UUID

/** Native cache lookups on main. Individual WPlayer getters retain the provider's own contracts. */
internal class CachedPlayerWarps {
    private class Binding(val api: PlayerWarpsAPI) {
        @Volatile var ready = false
    }

    private var binding: Binding? = null

    fun readyApi(): PlayerWarpsAPI {
        val api = checkNotNull(PlayerWarpsAPI.getInstance()) { "PlayerWarps API is not ready" }
        val current = binding?.takeIf { it.api === api }
            ?: Binding(api).also { registered ->
                binding = registered
                // A late callback only marks its own binding, never a replacement provider.
                PlayerWarpsAPI.getInstance { readyApi ->
                    if (readyApi === registered.api) registered.ready = true
                }
            }
        check(current.ready) { "PlayerWarps warp cache is not ready" }
        return api
    }

    fun playersById(api: PlayerWarpsAPI): Map<UUID, WPlayer> = api.warpPlayers.associateBy(WPlayer::getUUID)
}
