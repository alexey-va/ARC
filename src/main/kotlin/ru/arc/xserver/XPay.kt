package ru.arc.xserver

import org.bukkit.Bukkit
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.core.modules.EconomyModule
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.error
import java.util.UUID

class XPay(
    var amount: Double = 0.0,
    var playerName: String? = null,
    var playerUuid: UUID? = null
) : XAction() {

    override fun runInternal() {
        val miscConfig = ConfigManager.of(ARC.instance.dataFolder.toPath(), "misc.yml")
        if (amount == 0.0) {
            error("Amount is 0: {}", this)
            return
        }
        if (!miscConfig.bool("xaction.main-server", false)) {
            debug("Main server is disabled, skipping pay action")
            return
        }
        val player = when {
            playerName != null -> Bukkit.getOfflinePlayer(playerName!!)
            playerUuid != null -> Bukkit.getOfflinePlayer(playerUuid!!)
            else -> null
        }
        if (player == null) {
            error("Player not found: {}", this)
            return
        }
        val economy = EconomyModule.getEconomy() ?: return
        val response = economy.depositPlayer(player, amount)
        if (!response.transactionSuccess()) {
            error("Error depositing money: {}", response.errorMessage)
        }
    }
}
