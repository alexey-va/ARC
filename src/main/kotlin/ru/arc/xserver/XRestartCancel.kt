package ru.arc.xserver

import ru.arc.ARC
import ru.arc.restart.RestartManager
import ru.arc.util.Logging.info

class XRestartCancel(
    var initiatedBy: String? = null,
    var servers: Set<String>? = null,
) : XAction() {
    override fun runInternal() {
        info(
            "[XRestartCancel] runInternal — server={}, servers={}, by={}",
            ARC.serverName,
            servers,
            initiatedBy,
        )
        if (servers != null && ARC.serverName != null &&
            !servers!!.contains(ARC.serverName!!.lowercase())
        ) {
            info("[XRestartCancel] Skipping — this server ({}) is not in target list: {}", ARC.serverName, servers)
            return
        }
        RestartManager.handleRemoteCancel(initiatedBy ?: "xaction")
    }

    companion object {
        @JvmStatic
        fun create(
            initiatedBy: String,
            servers: Set<String>?,
        ): XRestartCancel =
            XRestartCancel(
                initiatedBy = initiatedBy,
                servers = servers,
            )
    }
}
