package ru.arc.hooks

import dev.unnm3d.rediseconomy.api.TransactionEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.ARC
import ru.arc.audit.AdminEconomyCommandTracker
import ru.arc.audit.AuditManager
import ru.arc.audit.EconomyAttributionResolver
import ru.arc.audit.EconomyBalanceObservation
import ru.arc.audit.EconomyEventStatus
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomyLedgerContext
import ru.arc.audit.EconomyLedgerParty
import ru.arc.audit.EconomyPendingContextTracker
import ru.arc.audit.EconomyRecordKind
import ru.arc.audit.EconomySource
import ru.arc.audit.EconomyTransferCorrelationTracker
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import java.util.UUID

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
                AuditManager.unresolvedBalanceSet(
                    playerName,
                    amount,
                    metadata,
                    ledgerContext(
                        playerId = accountId.uuid,
                        amount = null,
                        requestedAmount = amount,
                        currency = transaction.currencyName,
                        providerTimestamp = transaction.timestamp,
                        actor = transaction.actor,
                        reason = baseReason,
                        source = EconomySource.BALANCE_SET,
                        revertedWith = transaction.revertedWith,
                        action = "balance_set",
                    ),
                )
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
                            source = it.source,
                            flow = if (amount > 0.0) EconomyFlow.MINT else EconomyFlow.BURN,
                            origin = it.origin,
                        ),
                    type = it.source.type,
                    reason =
                        if (it.source == EconomySource.ADMIN_COMMAND) {
                            "Admin command by ${it.actor}"
                        } else {
                            resolved.reason
                        },
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
            ledgerContext(
                playerId = accountId.uuid,
                amount = amount,
                requestedAmount = amount,
                currency = transaction.currencyName,
                providerTimestamp = transaction.timestamp,
                actor = transaction.actor,
                reason = baseReason,
                source = attribution.metadata.source,
                revertedWith = transaction.revertedWith,
                action = adminCommand?.action,
                forcedCorrelationId = adminCommand?.correlationId,
            ),
        )
    }

    private fun ledgerContext(
        playerId: UUID,
        amount: Double?,
        requestedAmount: Double?,
        currency: String?,
        providerTimestamp: Long,
        actor: dev.unnm3d.rediseconomy.transaction.AccountID,
        reason: String,
        source: EconomySource,
        revertedWith: String?,
        action: String? = null,
        forcedCorrelationId: String? = null,
    ): EconomyLedgerContext {
        val capturedAt = System.currentTimeMillis()
        val onlinePlayer = Bukkit.getPlayer(playerId)
        val session = AuditManager.session(playerId, onlinePlayer?.world?.name)
        val observedAfter = HookRegistry.redisEcoHook?.getCachedBalance(playerId, currency)
        val balance = amount?.let { delta -> observedAfter?.let { EconomyBalanceObservation.inferredFromAfter(delta, it) } }
        val pending =
            if (amount != null && source in setOf(EconomySource.SHOP, EconomySource.AUTOSELL)) {
                EconomyPendingContextTracker.consume(playerId, amount, capturedAt)
            } else {
                null
            }
        val counterparty = actorParty(actor)
        val correlationId =
            pending?.correlationId
                ?: forcedCorrelationId
                ?: if (source == EconomySource.PLAYER_TRANSFER && counterparty?.id != null) {
                    EconomyTransferCorrelationTracker.correlate(
                        account = playerId.toString(),
                        actor = counterparty.id,
                        currency = currency.orEmpty(),
                        amount = amount ?: 0.0,
                        reason = reason,
                        timestamp = providerTimestamp.takeIf { it > 0 } ?: capturedAt,
                    )
                } else {
                    UUID.randomUUID().toString()
                }
        return (pending ?: EconomyLedgerContext()).copy(
            recordKind = EconomyRecordKind.TRANSACTION,
            status = if (revertedWith.isNullOrBlank()) EconomyEventStatus.SUCCEEDED else EconomyEventStatus.REVERTED,
            accountId = playerId.toString(),
            providerTimestamp = providerTimestamp.takeIf { it > 0 },
            correlationId = correlationId,
            counterparty = counterparty,
            world = session?.world ?: pending?.world,
            sessionId = session?.sessionId ?: pending?.sessionId,
            sessionStartedAt = session?.startedAt ?: pending?.sessionStartedAt,
            balanceBefore = balance?.before ?: pending?.balanceBefore,
            balanceAfter = balance?.after ?: pending?.balanceAfter ?: observedAfter,
            balanceEvidence = balance?.evidence ?: pending?.balanceEvidence,
            requestedAmount = pending?.requestedAmount ?: requestedAmount,
            action = pending?.action ?: action,
            revertedWith = revertedWith?.take(120),
            capturedAt = capturedAt,
        )
    }

    private fun actorParty(actor: dev.unnm3d.rediseconomy.transaction.AccountID): EconomyLedgerParty? {
        val id = actor.toString().take(80).takeIf(String::isNotBlank) ?: return null
        if (!actor.isPlayer) return EconomyLedgerParty(id = id, name = id, kind = "server")
        val actorId = runCatching { actor.uuid }.getOrNull()
        return EconomyLedgerParty(
            id = actorId?.toString() ?: id,
            name = actorId?.let(::actorName),
            kind = "player",
        )
    }

    private fun actorName(actorId: UUID): String? =
        try {
            Bukkit.getOfflinePlayer(actorId).name ?: HookRegistry.redisEcoHook?.getCachedName(actorId)
        } catch (e: Exception) {
            error("Error resolving RedisEconomy transaction actor", e)
            null
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
