package ru.arc.mounts

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

sealed interface MountPurchaseResult {
    data object Success : MountPurchaseResult
    data object Busy : MountPurchaseResult
    data object AlreadyOwned : MountPurchaseResult
    data object InvalidLevel : MountPurchaseResult
    data object NotUnlocked : MountPurchaseResult
    data object NotForSale : MountPurchaseResult
    data object PurchasesDisabled : MountPurchaseResult
    data object EconomyUnavailable : MountPurchaseResult
    data object InsufficientFunds : MountPurchaseResult
    data object PaymentFailed : MountPurchaseResult
    data object JournalUnavailable : MountPurchaseResult
    data object PersistenceFailed : MountPurchaseResult
    data object PersistenceFailedRefunded : MountPurchaseResult
    data object PersistenceFailedRefundFailed : MountPurchaseResult
    data object ManualReview : MountPurchaseResult
}

class MountPurchaseCoordinator(
    private val ownership: MountOwnership,
    private val wallet: MountWallet,
    private val journal: MountPurchaseJournal,
    private val purchasesEnabled: () -> Boolean,
    private val runSync: (() -> Unit) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStateChanged: () -> Unit = {},
) {
    private val activePurchases = ConcurrentHashMap.newKeySet<UUID>()

    fun purchaseLevel(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        level: Int,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        if (level !in 1..mount.maxLevel) return callback(MountPurchaseResult.InvalidLevel)
        val profile = ownership.profile(subject, mount)
        if (profile.level >= level) return callback(MountPurchaseResult.AlreadyOwned)
        if (level != profile.level + 1) return callback(MountPurchaseResult.InvalidLevel)
        val price = mount.price(level) ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.LEVEL,
            level.toString(),
            mount.levelPermission(level),
            price,
            callback,
        ) { ownership.grantLevel(subject.uniqueId, mount, level) }
    }

    fun purchaseGlow(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.glowOwned) return callback(MountPurchaseResult.AlreadyOwned)
        val price = mount.glowPrice ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.GLOW,
            "glow",
            mount.glowPermission,
            price,
            callback,
        ) { ownership.grantGlow(subject.uniqueId, mount) }
    }

    fun purchaseSkin(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        skin: MountSkinDefinition,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.unlocked) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.ownsSkin(skin.id)) return callback(MountPurchaseResult.AlreadyOwned)
        val price = skin.price ?: return callback(MountPurchaseResult.NotForSale)
        purchase(
            subject.uniqueId,
            mount,
            MountPurchaseKind.SKIN,
            skin.id,
            mount.skinPermission(skin.id),
            price,
            callback,
        ) { ownership.grantSkin(subject.uniqueId, mount, skin) }
    }

    fun setGlowEnabled(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        enabled: Boolean,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.glowOwned) return callback(MountPurchaseResult.NotUnlocked)
        runSetting(subject.uniqueId, callback) { ownership.setGlowEnabled(subject.uniqueId, mount, enabled) }
    }

    fun setActiveSkin(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        skinId: String,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.ownsSkin(skinId)) return callback(MountPurchaseResult.NotUnlocked)
        if (profile.activeSkinId == skinId) return callback(MountPurchaseResult.AlreadyOwned)
        runSetting(subject.uniqueId, callback) { ownership.setActiveSkin(subject.uniqueId, mount, skinId) }
    }

    fun recover(catalog: MountCatalog, onManualReview: (MountPurchaseJournalRecord) -> Unit) {
        journal.records().filter { !it.status.terminal || it.status == MountPurchaseJournalStatus.MANUAL_REVIEW }.forEach { record ->
            val mount = catalog[record.mountId]
            if (mount == null) {
                markManualReview(record, "mount_missing")?.let(onManualReview)
                return@forEach
            }
            when (record.status) {
                MountPurchaseJournalStatus.PREPARED -> persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "startup_cancelled"))
                MountPurchaseJournalStatus.WITHDRAWAL_STARTED -> recoverWithdrawal(record, mount, onManualReview)
                MountPurchaseJournalStatus.REFUND_STARTED -> recoverRefund(record, onManualReview)
                MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                MountPurchaseJournalStatus.OWNERSHIP_STARTED,
                -> recoverOwnership(record, mount, onManualReview)
                MountPurchaseJournalStatus.MANUAL_REVIEW -> recoverManualReview(record, mount, onManualReview)
                MountPurchaseJournalStatus.COMPLETED,
                MountPurchaseJournalStatus.CANCELLED,
                MountPurchaseJournalStatus.REFUNDED,
                -> Unit
            }
        }
    }

    fun clear() {
        activePurchases.clear()
    }

    private fun purchase(
        playerId: UUID,
        mount: MountDefinition,
        kind: MountPurchaseKind,
        target: String,
        permission: String,
        price: Double,
        callback: (MountPurchaseResult) -> Unit,
        applyPermission: () -> CompletableFuture<Void>,
    ) {
        if (!purchasesEnabled()) return callback(MountPurchaseResult.PurchasesDisabled)
        if (!wallet.available) return callback(MountPurchaseResult.EconomyUnavailable)
        if (journal.hasOpenPurchase(playerId)) return callback(MountPurchaseResult.ManualReview)
        if (!activePurchases.add(playerId)) return callback(MountPurchaseResult.Busy)

        val now = clock()
        val record =
            MountPurchaseJournalRecord(
                transactionId = UUID.randomUUID().toString(),
                playerId = playerId.toString(),
                mountId = mount.id,
                kind = kind,
                target = target,
                permission = permission,
                priceMinor = price.toExactMinor(),
                createdAt = now,
                updatedAt = now,
            )
        if (!persist(record)) return finish(playerId, callback, MountPurchaseResult.JournalUnavailable)
        val balanceBefore = wallet.balanceMinor(playerId)
        if (balanceBefore == null) {
            val cancelled = persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "balance_unavailable"))
            return finish(
                playerId,
                callback,
                if (cancelled) MountPurchaseResult.EconomyUnavailable else MountPurchaseResult.JournalUnavailable,
            )
        }
        if (balanceBefore < record.priceMinor) {
            persist(record.advance(MountPurchaseJournalStatus.CANCELLED, "insufficient_funds"))
            return finish(playerId, callback, MountPurchaseResult.InsufficientFunds)
        }
        val started =
            record.copy(
                status = MountPurchaseJournalStatus.WITHDRAWAL_STARTED,
                updatedAt = nextTime(record.updatedAt),
                balanceBeforeMinor = balanceBefore,
            )
        if (!persist(started)) return finish(playerId, callback, MountPurchaseResult.JournalUnavailable)

        val evidence = wallet.withdraw(playerId, record.priceMinor, "arc-mount:${record.transactionId}", balanceBefore)
        val expectedAfter = balanceBefore - record.priceMinor
        if (evidence.balanceAfterMinor != expectedAfter) {
            val exactUnchanged = evidence.balanceAfterMinor == balanceBefore && evidence.providerAccepted == false
            val terminal =
                if (exactUnchanged) started.advance(MountPurchaseJournalStatus.CANCELLED, evidence.failureCode ?: "provider_rejected")
                else started.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence.failureCode ?: "ambiguous_withdrawal")
            persist(terminal)
            return finish(
                playerId,
                callback,
                if (exactUnchanged) MountPurchaseResult.PaymentFailed else MountPurchaseResult.ManualReview,
            )
        }
        val withdrawn =
            started.copy(
                status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                updatedAt = nextTime(started.updatedAt),
                balanceAfterMinor = expectedAfter,
                evidence = "exact_balance_delta",
            )
        if (!persist(withdrawn)) return finish(playerId, callback, MountPurchaseResult.ManualReview)
        val ownershipStarted = withdrawn.advance(MountPurchaseJournalStatus.OWNERSHIP_STARTED, "permission_write_started")
        if (!persist(ownershipStarted)) return finish(playerId, callback, MountPurchaseResult.ManualReview)

        val future = runCatching(applyPermission).getOrElse {
            verifyOrRefund(playerId, ownershipStarted, callback)
            return
        }
        future.whenComplete { _, _ -> runSync { verifyOrRefund(playerId, ownershipStarted, callback) } }
    }

    private fun verifyOrRefund(
        playerId: UUID,
        record: MountPurchaseJournalRecord,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, verificationFailure ->
            runSync {
                when {
                    verificationFailure != null -> {
                        persist(record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, "permission_verification_failed"))
                        finish(playerId, callback, MountPurchaseResult.ManualReview)
                    }
                    owned == true -> {
                        val completed = record.advance(MountPurchaseJournalStatus.COMPLETED, "permission_verified")
                        val saved = persist(completed)
                        finish(
                            playerId,
                            callback,
                            if (saved) MountPurchaseResult.Success else MountPurchaseResult.ManualReview,
                        )
                    }
                    else -> refund(playerId, record, callback)
                }
            }
        }
    }

    private fun refund(
        playerId: UUID,
        record: MountPurchaseJournalRecord,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val before = wallet.balanceMinor(playerId)
        if (before == null) {
            persist(record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, "refund_balance_unavailable"))
            return finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        }
        val started =
            record.copy(
                status = MountPurchaseJournalStatus.REFUND_STARTED,
                updatedAt = nextTime(record.updatedAt),
                refundBalanceBeforeMinor = before,
                evidence = "permission_not_applied",
            )
        if (!persist(started)) return finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        val evidence = wallet.deposit(playerId, record.priceMinor, "arc-mount-refund:${record.transactionId}", before)
        val expectedAfter = before + record.priceMinor
        if (evidence.balanceAfterMinor == expectedAfter) {
            val refunded =
                started.copy(
                    status = MountPurchaseJournalStatus.REFUNDED,
                    updatedAt = nextTime(started.updatedAt),
                    refundBalanceAfterMinor = expectedAfter,
                    evidence = "exact_refund_balance_delta",
                )
            val saved = persist(refunded)
            finish(
                playerId,
                callback,
                if (saved) MountPurchaseResult.PersistenceFailedRefunded else MountPurchaseResult.ManualReview,
            )
        } else {
            persist(started.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence.failureCode ?: "ambiguous_refund"))
            finish(playerId, callback, MountPurchaseResult.PersistenceFailedRefundFailed)
        }
    }

    private fun recoverOwnership(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, lookupFailure ->
            runSync {
                if (lookupFailure != null) {
                    markManualReview(record, "startup_permission_lookup_failed")?.let(onManualReview)
                    return@runSync
                }
                if (owned == true) {
                    if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "startup_permission_verified"))) {
                        onManualReview(record)
                    }
                    return@runSync
                }
                val write = permissionWrite(record, mount)
                if (write == null) {
                    markManualReview(record, "startup_target_missing")?.let(onManualReview)
                    return@runSync
                }
                write.whenComplete { _, _ ->
                    ownership.hasDirectPermission(playerId, record.permission).whenComplete { recovered, verifyFailure ->
                        runSync {
                            if (verifyFailure == null && recovered == true) {
                                if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "startup_permission_recovered"))) {
                                    onManualReview(record)
                                }
                            } else {
                                markManualReview(record, "startup_permission_recovery_failed")?.let(onManualReview)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun recoverManualReview(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        ownership.hasDirectPermission(playerId, record.permission).whenComplete { owned, lookupFailure ->
            runSync {
                val withdrawalProven =
                    record.balanceBeforeMinor != null &&
                        record.balanceAfterMinor == record.balanceBeforeMinor - record.priceMinor
                when {
                    lookupFailure != null -> onManualReview(record)
                    owned == true && record.refundBalanceBeforeMinor != null -> onManualReview(record)
                    owned == true && withdrawalProven -> {
                        if (!persist(record.advance(MountPurchaseJournalStatus.COMPLETED, "manual_permission_verified"))) {
                            onManualReview(record)
                        }
                    }
                    record.refundBalanceBeforeMinor != null -> recoverRefund(record, onManualReview)
                    withdrawalProven -> recoverOwnership(record, mount, onManualReview)
                    record.balanceBeforeMinor != null -> recoverWithdrawal(record, mount, onManualReview)
                    else -> onManualReview(record)
                }
            }
        }
    }

    private fun recoverWithdrawal(
        record: MountPurchaseJournalRecord,
        mount: MountDefinition,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        wallet.findTransaction(
            playerId = playerId,
            amountMinor = -record.priceMinor,
            reason = "arc-mount:${record.transactionId}",
            notBeforeMillis = record.createdAt,
        ).whenComplete { history, failure ->
            runSync {
                if (failure != null || history?.found != true) {
                    val evidence = if (history?.historyAvailable == true) "withdrawal_not_found_in_history" else "withdrawal_history_unavailable"
                    markManualReview(record, evidence)?.let(onManualReview)
                    return@runSync
                }
                val before = requireNotNull(record.balanceBeforeMinor)
                val withdrawn =
                    record.copy(
                        status = MountPurchaseJournalStatus.FUNDS_WITHDRAWN,
                        updatedAt = nextTime(record.updatedAt),
                        balanceAfterMinor = before - record.priceMinor,
                        evidence = "history_withdrawal:${history.transactionId}",
                    )
                if (persist(withdrawn)) recoverOwnership(withdrawn, mount, onManualReview)
                else onManualReview(record)
            }
        }
    }

    private fun recoverRefund(
        record: MountPurchaseJournalRecord,
        onManualReview: (MountPurchaseJournalRecord) -> Unit,
    ) {
        val playerId = UUID.fromString(record.playerId)
        wallet.findTransaction(
            playerId = playerId,
            amountMinor = record.priceMinor,
            reason = "arc-mount-refund:${record.transactionId}",
            notBeforeMillis = record.createdAt,
        ).whenComplete { history, failure ->
            runSync {
                if (failure != null || history?.found != true) {
                    val evidence = if (history?.historyAvailable == true) "refund_not_found_in_history" else "refund_history_unavailable"
                    markManualReview(record, evidence)?.let(onManualReview)
                    return@runSync
                }
                val before = requireNotNull(record.refundBalanceBeforeMinor)
                val refunded =
                    record.copy(
                        status = MountPurchaseJournalStatus.REFUNDED,
                        updatedAt = nextTime(record.updatedAt),
                        refundBalanceAfterMinor = before + record.priceMinor,
                        evidence = "history_refund:${history.transactionId}",
                    )
                if (!persist(refunded)) onManualReview(record)
            }
        }
    }

    private fun permissionWrite(record: MountPurchaseJournalRecord, mount: MountDefinition): CompletableFuture<Void>? {
        val playerId = UUID.fromString(record.playerId)
        return when (record.kind) {
            MountPurchaseKind.LEVEL -> record.target.toIntOrNull()?.let { ownership.grantLevel(playerId, mount, it) }
            MountPurchaseKind.GLOW -> ownership.grantGlow(playerId, mount)
            MountPurchaseKind.SKIN -> mount.skin(record.target)?.let { ownership.grantSkin(playerId, mount, it) }
        }
    }

    private fun runSetting(
        playerId: UUID,
        callback: (MountPurchaseResult) -> Unit,
        mutation: () -> CompletableFuture<Void>,
    ) {
        if (!activePurchases.add(playerId)) return callback(MountPurchaseResult.Busy)
        val future = runCatching(mutation).getOrElse {
            activePurchases.remove(playerId)
            callback(MountPurchaseResult.PersistenceFailed)
            return
        }
        future.whenComplete { _, failure ->
            runSync {
                activePurchases.remove(playerId)
                callback(if (failure == null) MountPurchaseResult.Success else MountPurchaseResult.PersistenceFailed)
            }
        }
    }

    private fun finish(
        playerId: UUID,
        callback: (MountPurchaseResult) -> Unit,
        result: MountPurchaseResult,
    ) {
        activePurchases.remove(playerId)
        onStateChanged()
        callback(result)
    }

    private fun markManualReview(record: MountPurchaseJournalRecord, evidence: String): MountPurchaseJournalRecord? {
        val review = record.advance(MountPurchaseJournalStatus.MANUAL_REVIEW, evidence)
        return review.takeIf(::persist)
    }

    private fun MountPurchaseJournalRecord.advance(
        status: MountPurchaseJournalStatus,
        evidence: String,
    ): MountPurchaseJournalRecord = copy(status = status, updatedAt = nextTime(updatedAt), evidence = evidence)

    private fun nextTime(previous: Long): Long = maxOf(clock(), previous + 1L)

    private fun persist(record: MountPurchaseJournalRecord): Boolean =
        runCatching { journal.persist(record) }.getOrDefault(false).also { if (it) onStateChanged() }
}
