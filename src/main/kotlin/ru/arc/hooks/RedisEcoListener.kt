package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.TransactionEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.audit.Type.PAY
import ru.arc.config.ConfigManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

class RedisEcoListener : Listener {

    private val miscConfig = ConfigManager.of(ARC.instance.dataPath, "misc.yml")

    @EventHandler
    fun onTransaction(event: TransactionEvent) {
        val amount = event.transaction.amount
        val accountId = event.transaction.accountIdentifier
        if (!accountId.isPlayer) return
        if (!"Payment".equals(event.transaction.reason, ignoreCase = true)) return

        val otherAccountId = if (amount > 0) event.transaction.actor else event.transaction.accountIdentifier
        val offlinePlayer = Bukkit.getOfflinePlayer(accountId.uuid)
        val otherPlayer = try {
            Bukkit.getOfflinePlayer(event.transaction.actor.uuid)
        } catch (e: Exception) {
            error("Error getting player", e)
            null
        }

        if (offlinePlayer.name == null) {
            info("Transaction of {} for unknown player {}", amount, accountId)
            return
        }
        if (Math.abs(amount) < 1.0) return

        AuditManager.operation(
            offlinePlayer.name!!,
            amount,
            PAY,
            otherPlayer?.name ?: "$otherAccountId",
        )
    }
}
