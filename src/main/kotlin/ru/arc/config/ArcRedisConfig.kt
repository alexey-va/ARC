package ru.arc.config

import ru.arc.ARC
import ru.arc.redis.RedisModuleConfig
import ru.arc.redis.TestRedisModuleConfig

/** Cached [RedisModuleConfig] accessor for Paper ARC (hot-reload via [ConfigManager.getVersion]). */
object ArcRedisConfig {
    @JvmStatic
    fun get(): RedisModuleConfig =
        if (ARC.plugin == null) {
            TestRedisModuleConfig(
                enabled = false,
                serverName = "test-server",
                mainServer = true,
            )
        } else {
            RedisModuleConfig.load(ARC.instance.dataPath)
        }
}
