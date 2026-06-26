package ru.arc.repository

import kotlinx.coroutines.CoroutineScope
import ru.arc.ARC
import ru.arc.util.Common

/**
 * ARC convenience wrapper — injects [ARC.redisManager] and [Common.gson].
 */
inline fun <reified T : Entity> redisRepo(
    id: String,
    storageKey: String,
    updateChannel: String,
    scope: CoroutineScope,
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
        configure = configure,
    )
}
