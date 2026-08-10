package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.TransactionEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.audit.AuditManager
import ru.arc.audit.AdminEconomyCommandTracker
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomySource
import ru.arc.audit.EconomyAttributionResolver
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info

class RedisEcoListener : Listener {
    @EventHandler
    fun onTransaction(event: TransactionEvent) {
        val transaction = event.transaction
        val amount = transaction.amount
        val accountId = transaction.accountIdentifier
        if (!accountId.isPlayer) return
        if (!amount.isFinite()) return
        // RedisEconomy 4.5.12 emits a synthetic -new/+new pair after setBalance.
        // It has zero net value and no old balance, so exact command deltas are captured by CommandListener instead.
        val baseReason = transaction.reason.orEmpty().lineSequence().firstOrNull().orEmpty()
        if (baseReason.equals("Reset balance", ignoreCase = true)) return

        val offlinePlayer = Bukkit.getOfflinePlayer(accountId.uuid)
        val playerName = offlinePlayer.name ?: HookRegistry.redisEcoHook?.getCachedName(accountId.uuid)
        if (playerName == null) {
            info("Transaction of {} for unknown player {}", amount, accountId)
            return
        }

        if (baseReason.equals("Set balance", ignoreCase = true)) {
            if (AdminEconomyCommandTracker.consumeSet(playerName, amount) == null) {
                val metadata =
                    EconomyAttributionResolver.resolve(transaction.reason, amount, transaction.currencyName, ARC.serverName).metadata
                AuditManager.unresolvedBalanceSet(playerName, amount, metadata)
            }
            return
        }
        if (amount == 0.0) return

        val adminCommand = AdminEconomyCommandTracker.consumeDelta(playerName, amount)
        val resolved =
            EconomyAttributionResolver.resolve(
                rawReason = transaction.reason,
                amount = amount,
                currency = transaction.currencyName,
                server = ARC.serverName,
            )
        val attribution =
            adminCommand?.let {
                resolved.copy(
                    metadata =
                        resolved.metadata.copy(
                            source = EconomySource.ADMIN_COMMAND,
                            flow = if (amount > 0.0) EconomyFlow.MINT else EconomyFlow.BURN,
                            origin = it.actor,
                        ),
                    type = ru.arc.audit.Type.COMMAND,
                    reason = "Admin command by ${it.actor}",
                )
            } ?: resolved
        val comment =
            if (attribution.metadata.source == ru.arc.audit.EconomySource.PLAYER_TRANSFER) {
                actorName(transaction.actor) ?: attribution.reason
            } else {
                attribution.reason
            }

        AuditManager.economyOperation(
            playerName,
            amount,
            attribution.type,
            comment,
            attribution.metadata,
        )
    }

    private fun actorName(actor: dev.unnm3d.rediseconomy.transaction.AccountID): String? {
        if (!actor.isPlayer) return actor.toString()
        return try {
            Bukkit.getOfflinePlayer(actor.uuid).name
        } catch (e: Exception) {
            error("Error resolving RedisEconomy transaction actor", e)
            null
        }
    }
}
