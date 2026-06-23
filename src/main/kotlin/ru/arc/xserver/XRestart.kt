package ru.arc.xserver

import ru.arc.ARC
import ru.arc.restart.RestartManager
import ru.arc.util.Logging.info
import java.time.Duration

class XRestart(
    var delayMillis: Long = 0,
    var initiatedBy: String? = null,
    var servers: Set<String>? = null,
) : XAction() {
    override fun runInternal() {
        info(
            "[XRestart] runInternal — server={}, servers={}, delayMillis={}, by={}",
            ARC.serverName,
            servers,
            delayMillis,
            initiatedBy,
        )
        if (servers != null && ARC.serverName != null &&
            !servers!!.contains(ARC.serverName!!.lowercase())
        ) {
            info("[XRestart] Skipping — this server ({}) is not in target list: {}", ARC.serverName, servers)
            return
        }
        RestartManager.handleRemoteSchedule(
            delay = Duration.ofMillis(delayMillis.coerceAtLeast(0)),
            initiatedBy = initiatedBy ?: "xaction",
        )
    }

    companion object {
        @JvmStatic
        fun create(
            delay: Duration,
            initiatedBy: String,
            servers: Set<String>?,
        ): XRestart =
            XRestart(
                delayMillis = maxOf(delay, Duration.ZERO).toMillis(),
                initiatedBy = initiatedBy,
                servers = servers,
            )
    }
}
