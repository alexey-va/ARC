package ru.arc.repository

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import ru.arc.ARC
import ru.arc.repository.redis.RedisStorage
import ru.arc.repository.redis.RedisSyncService
import ru.arc.util.Common
import kotlin.time.Duration

/**
 * Creates and initializes a [CachedRepository] backed by Redis.
 *
 * Eliminates the boilerplate of constructing [RepoConfig], [RedisStorage],
 * [RedisSyncService], and [CachedRepository] separately.
 *
 * Usage:
 * ```kotlin
 * repo = redisRepo<MyData>(
 *     id = "my-feature",
 *     storageKey = "arc.my_data",
 *     updateChannel = "arc.my_data_update",
 *     scope = scope,
 * ) {
 *     loadAllOnStart(true)
 *     saveInterval(500.milliseconds)
 * }
 * ```
 */
inline fun <reified T : Entity> redisRepo(
    id: String,
    storageKey: String,
    updateChannel: String,
    scope: CoroutineScope,
    configure: RepoConfig.Builder<T>.() -> Unit = {}
): CachedRepository<T> {
    val entityType = object : TypeToken<T>() {}.type

    val config = RepoConfig.builder<T>(id)
        .storageKey(storageKey)
        .updateChannel(updateChannel)
        .apply(configure)
        .build()

    val storage = RedisStorage<T>(
        redis = ARC.redisManager!!,
        storageKey = storageKey,
        entityType = entityType,
        gson = Common.gson
    )

    val syncService = RedisSyncService<T>(
        redis = ARC.redisManager!!,
        channel = updateChannel,
        entityType = entityType,
        gson = Common.gson
    )

    val repo = CachedRepository(
        config = config,
        storage = storage,
        syncService = syncService,
        scope = scope
    )

    runBlocking { repo.init() }
    return repo
}
