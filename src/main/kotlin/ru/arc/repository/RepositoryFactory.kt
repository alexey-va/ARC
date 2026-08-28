package ru.arc.repository

import kotlinx.coroutines.CoroutineScope
import ru.arc.ARC
import ru.arc.repository.redis.RedisSyncMode
import ru.arc.util.Common
import kotlin.time.Duration

/**
 * ARC convenience wrapper — injects [ARC.redisManager] and [Common.gson].
 */
inline fun <reified T : Entity> redisRepo(
    id: String,
    storageKey: String,
    updateChannel: String,
    scope: CoroutineScope,
    syncMode: RedisSyncMode = RedisSyncMode.ENTITY,
    localSyncOrigin: String? = null,
    invalidationCoalesceWindow: Duration = Duration.ZERO,
    configure: RepoConfig.Builder<T>.() -> Unit = {},
): CachedRepository<T> {
    val redis = ARC.redisManager
        ?: error("Redis is not available — cannot create redisRepo for '$id' (redis.enabled=false?)")
    return ru.arc.repository.redisRepo(
        redis = redis,
        gson = Common.gson,
        id = id,
        storageKey = storageKey,
        updateChannel = updateChannel,
        scope = scope,
        syncMode = syncMode,
        localSyncOrigin = localSyncOrigin,
        invalidationCoalesceWindow = invalidationCoalesceWindow,
        configure = configure,
    )
}
