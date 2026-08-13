package ru.arc.mounts

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface MountPurchaseResult {
    data object Success : MountPurchaseResult
    data object Busy : MountPurchaseResult
    data object AlreadyOwned : MountPurchaseResult
    data object InvalidLevel : MountPurchaseResult
    data object NotUnlocked : MountPurchaseResult
    data object NotForSale : MountPurchaseResult
    data object EconomyUnavailable : MountPurchaseResult
    data object InsufficientFunds : MountPurchaseResult
    data object PaymentFailed : MountPurchaseResult
    data object PersistenceFailed : MountPurchaseResult
    data object PersistenceFailedRefunded : MountPurchaseResult
    data object PersistenceFailedRefundFailed : MountPurchaseResult
}

class MountPurchaseCoordinator(
    private val ownership: MountOwnership,
    private val wallet: MountWallet,
    private val runSync: (() -> Unit) -> Unit,
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
        purchase(subject.uniqueId, price, callback) { ownership.grantLevel(subject.uniqueId, mount, level) }
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
        purchase(subject.uniqueId, price, callback) { ownership.grantGlow(subject.uniqueId, mount) }
    }

    fun setGlowEnabled(
        subject: MountPermissionSubject,
        mount: MountDefinition,
        enabled: Boolean,
        callback: (MountPurchaseResult) -> Unit,
    ) {
        val profile = ownership.profile(subject, mount)
        if (!profile.glowOwned) return callback(MountPurchaseResult.NotUnlocked)
        if (!activePurchases.add(subject.uniqueId)) return callback(MountPurchaseResult.Busy)
        ownership.setGlowEnabled(subject.uniqueId, mount, enabled).whenComplete { _, failure ->
            runSync {
                activePurchases.remove(subject.uniqueId)
                callback(if (failure == null) MountPurchaseResult.Success else MountPurchaseResult.PersistenceFailed)
            }
        }
    }

    fun clear() {
        activePurchases.clear()
    }

    private fun purchase(
        playerId: UUID,
        price: Double,
        callback: (MountPurchaseResult) -> Unit,
        persist: () -> java.util.concurrent.CompletableFuture<Void>,
    ) {
        if (!wallet.available) return callback(MountPurchaseResult.EconomyUnavailable)
        if (!activePurchases.add(playerId)) return callback(MountPurchaseResult.Busy)
        if (wallet.balance(playerId) < price) {
            activePurchases.remove(playerId)
            return callback(MountPurchaseResult.InsufficientFunds)
        }
        if (!wallet.withdraw(playerId, price)) {
            activePurchases.remove(playerId)
            return callback(MountPurchaseResult.PaymentFailed)
        }

        val future =
            runCatching(persist).getOrElse {
                runSync {
                    activePurchases.remove(playerId)
                    val refunded = wallet.deposit(playerId, price)
                    callback(
                        if (refunded) MountPurchaseResult.PersistenceFailedRefunded
                        else MountPurchaseResult.PersistenceFailedRefundFailed,
                    )
                }
                return
            }
        future.whenComplete { _, failure ->
            runSync {
                activePurchases.remove(playerId)
                if (failure == null) {
                    callback(MountPurchaseResult.Success)
                } else {
                    val refunded = wallet.deposit(playerId, price)
                    callback(
                        if (refunded) MountPurchaseResult.PersistenceFailedRefunded
                        else MountPurchaseResult.PersistenceFailedRefundFailed,
                    )
                }
            }
        }
    }
}
