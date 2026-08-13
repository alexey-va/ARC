package ru.arc.sync.duels

import java.util.UUID

/**
 * Stable static boundary called reflectively by the standalone Duels plugin.
 * It deliberately exposes only JDK types so neither plugin needs the other's
 * classes on its compile or runtime classpath.
 */
object DuelStatsBridge {
    @Volatile
    private var sync: DuelsSync? = null

    internal fun install(value: DuelsSync) {
        sync = value
    }

    internal fun uninstall() {
        sync = null
    }

    @JvmStatic
    fun statsChanged(uuid: UUID) {
        sync?.statsChanged(uuid)
    }
}
