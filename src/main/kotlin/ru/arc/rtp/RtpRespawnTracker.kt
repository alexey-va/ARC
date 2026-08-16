package ru.arc.rtp

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RtpCompletionRequest(
    val provider: RtpProvider?,
    val setRespawn: Boolean,
    val persistPlayerId: UUID?,
    val persistWorldName: String?,
)

/**
 * Short-lived correlation between an ARC-initiated RTP and the provider's
 * post-teleport event. Entries are consumed exactly once.
 */
object RtpRespawnTracker {
    val pending: Cache<String, RtpCompletionRequest> =
        CacheBuilder
            .newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build()

    fun mark(
        playerName: String,
        provider: RtpProvider? = null,
        setRespawn: Boolean = true,
        persistPlayerId: UUID? = null,
        persistWorldName: String? = null,
    ) {
        pending.put(
            playerName,
            RtpCompletionRequest(provider, setRespawn, persistPlayerId, persistWorldName),
        )
    }

    fun cancel(playerName: String) {
        pending.invalidate(playerName)
    }

    fun hasPending(playerName: String): Boolean = pending.getIfPresent(playerName) != null

    fun consume(
        playerName: String,
        provider: RtpProvider,
    ): Boolean = take(playerName, provider) != null

    fun take(
        playerName: String,
        provider: RtpProvider,
    ): RtpCompletionRequest? {
        val request = pending.getIfPresent(playerName) ?: return null
        if (request.provider != null && request.provider != provider) return null
        return request.takeIf { pending.asMap().remove(playerName, request) }
    }
}
