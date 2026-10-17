package ru.arc.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    redis: ru.arc.redis.RedisOperations = ARC.redisManager
        ?: error("Redis is not available — cannot create redisRepo for '$id' (redis.enabled=false?)"),
    syncMode: RedisSyncMode = RedisSyncMode.ENTITY,
    localSyncOrigin: String? = null,
    invalidationCoalesceWindow: Duration = Duration.ZERO,
    configure: RepoConfig.Builder<T>.() -> Unit = {},
): CachedRepository<T> {
    // Repository timers must not share the finite operation supervisor: shutdown
    // first drains accepted user operations, then flushes/stops this background owner.
    val repositoryScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    return try { ru.arc.repository.redisRepo(
        redis = redis,
        gson = Common.gson,
        id = id,
        storageKey = storageKey,
        updateChannel = updateChannel,
        scope = repositoryScope,
        syncMode = syncMode,
        localSyncOrigin = localSyncOrigin,
        invalidationCoalesceWindow = invalidationCoalesceWindow,
        configure = configure,
    ) } catch (failure: Throwable) {
        repositoryScope.cancel()
        throw failure
    }
}
