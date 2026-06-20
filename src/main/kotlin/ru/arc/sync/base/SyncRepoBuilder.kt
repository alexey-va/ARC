package ru.arc.sync.base

import ru.arc.network.RedisManager

class SyncRepoBuilder<T : SyncData>(val clazz: Class<T>) {
    var key: String = ""
    var redisManager: RedisManager? = null
    var dataApplier: ((T) -> Unit)? = null
    var dataProducer: ((Context) -> T?)? = null

    fun key(key: String): SyncRepoBuilder<T> = apply { this.key = key }
    fun redisManager(redisManager: RedisManager): SyncRepoBuilder<T> = apply { this.redisManager = redisManager }
    fun dataApplier(dataSetter: (T) -> Unit): SyncRepoBuilder<T> = apply { this.dataApplier = dataSetter }
    fun dataProducer(dataProducer: (Context) -> T?): SyncRepoBuilder<T> = apply { this.dataProducer = dataProducer }

    fun build(): SyncRepo<T> = SyncRepo(
        clazz = clazz,
        key = key,
        redisManager = checkNotNull(redisManager) { "redisManager must be set" },
        dataApplier = checkNotNull(dataApplier) { "dataApplier must be set" },
        dataProducer = checkNotNull(dataProducer) { "dataProducer must be set" },
    )
}
