package ru.arc.sync.base

import java.util.UUID

class Context {
    private val map = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T = map[key] as T

    fun put(name: String, uuid: UUID) {
        map[name] = uuid
    }
}
