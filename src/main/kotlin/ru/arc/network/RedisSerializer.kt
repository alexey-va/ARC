package ru.arc.network

import ru.arc.util.Common

object RedisSerializer {
    @JvmStatic fun toJson(serializable: Any): String = Common.gson.toJson(serializable)
    @JvmStatic fun <T> fromJson(json: String, clazz: Class<T>): T = Common.gson.fromJson(json, clazz)
}
