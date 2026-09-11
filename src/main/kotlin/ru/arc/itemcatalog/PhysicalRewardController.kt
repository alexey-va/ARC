package ru.arc.itemcatalog

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseIdentity
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.onetime.OneTimeUseScope
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns an immutable catalogue definition into a physical, one-time bearer
 * item. The controller owns only the bearer protocol; native effects remain in
 * the injected adapter callbacks.
 *
 * Claiming occurs before [redeem] is called. A [PhysicalRewardOutcome.Rejected]
 * result is the only native result that releases a claim. Unknown exceptions
 * and [PhysicalRewardOutcome.Uncertain] retain recovery ownership through
 * [OneTimeUseLedger.abandon].
 */
class PhysicalRewardController(
    private val plugin: Plugin,
    private val ledger: OneTimeUseLedger,
    private val resolve: (String) -> PhysicalRewardSpec?,
    private val canRedeem: (org.bukkit.entity.Player, PhysicalRewardSpec) -> String? = { _, _ -> null },
    private val redeem: (org.bukkit.entity.Player, PhysicalRewardSpec, UUID) -> CompletableFuture<PhysicalRewardOutcome>,
    scope: String,
) : AutoCloseable {
    private val operationScope = OneTimeUseScope.parse(scope)
    private val active = AtomicBoolean(true)
    private val registered = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<UUID, PhysicalRewardRedemption>()
    private val stateMonitor = Any()
    private val drainFuture = java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>?>()
    private val lifecycle = java.util.concurrent.atomic.AtomicReference<LifecycleTaskScope?>()

    private val listener = object : Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        fun onPlayerInteract(event: PlayerInteractEvent) {
            if (!active.get() || event.hand != EquipmentSlot.HAND || event.action !in RIGHT_CLICK_ACTIONS) return
            val player = event.player
            val identity = PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) ?: return
            event.isCancelled = true
            start(player, identity)
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        fun onInventoryClick(event: InventoryClickEvent) {
            if (pending.containsKey(event.whoClicked.uniqueId)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        fun onInventoryDrag(event: InventoryDragEvent) {
            if (pending.containsKey(event.whoClicked.uniqueId)) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        fun onPlayerDrop(event: PlayerDropItemEvent) {
            val player = event.player
            if (!pending.containsKey(player.uniqueId)) return
            if (PhysicalRewardVoucher.identity(event.itemDrop.itemStack) != null) event.isCancelled = true
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        fun onSwapHandItems(event: PlayerSwapHandItemsEvent) {
            if (pending.containsKey(event.player.uniqueId)) event.isCancelled = true
        }

        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            cancel(event.player.uniqueId)
        }
    }

    val available: Boolean
        get() = active.get() && ledger.available

    /** Registers this controller's listener once for the current runtime. */
    fun register() {
        if (active.get() && registered.compareAndSet(false, true)) {
            plugin.server.pluginManager.registerEvents(listener, plugin)
        }
    }

    /**
     * Stops new redemptions and invalidates callbacks. A claim that has not
     * started a native effect is released; an in-flight or completed unknown
     * effect is abandoned for recovery. The ledger itself belongs to the
     * module and is deliberately not closed here.
     */
    override fun close() {
        val toRecover = synchronized(stateMonitor) {
            if (!active.compareAndSet(true, false)) return
            pending.values.toList()
        }
        if (registered.compareAndSet(true, false)) HandlerList.unregisterAll(listener)
        toRecover.forEach { redemption ->
            redemption.cancelled = true
            recoverCancelled(redemption)
        }
        lifecycle.getAndSet(null)?.close()
    }

    /**
     * Invalidates this runtime and waits until every already-started claim has
     * completed its release, commit, or abandon callback. The ledger is owned
     * by the module and must be closed only after this future completes (or a
     * bounded shutdown timeout expires).
     */
    fun closeAndDrain(): CompletableFuture<Void> {
        val result = drainFuture.updateAndGet { it ?: CompletableFuture<Void>() }!!
        close()
        val settlements = synchronized(stateMonitor) { pending.values.map { it.settled } }
        if (settlements.isEmpty()) {
            result.complete(null)
        } else {
            CompletableFuture.allOf(*settlements.toTypedArray()).whenComplete { _, _ -> result.complete(null) }
        }
        return result
    }

    /** Creates a new amount-one physical voucher. It never invokes [redeem]. */
    fun createStack(key: String): ItemStack? {
        if (!available || !PhysicalRewardVoucher.isValidKey(key)) return null
        val spec = resolveSafely(key) ?: return null
        if (spec.key != key) return null
        return spec.preview.clone().also { stack ->
            stack.amount = 1
            PhysicalRewardVoucher.mark(
                stack,
                PhysicalRewardVoucherIdentity(UUID.randomUUID(), key, spec.fingerprint),
            )
            appendActionHint(stack)
        }
    }

    /** Returns an inert clone for menus and previews, without voucher PDC. */
    fun previewStack(key: String): ItemStack? =
        if (available && PhysicalRewardVoucher.isValidKey(key)) {
            resolveSafely(key)?.takeIf { it.key == key }?.preview?.clone()?.also { it.amount = 1 }
        } else {
            null
        }

    private fun start(player: org.bukkit.entity.Player, identity: PhysicalRewardVoucherIdentity) {
        if (!active.get() || !ledger.available) return unavailable(player)
        if (pending.containsKey(player.uniqueId)) {
            player.sendMessage(TextUtil.mm(BUSY_MESSAGE, true))
            return
        }
        val spec = resolveSafely(identity.key)
        if (spec == null || spec.key != identity.key || spec.fingerprint != identity.fingerprint) {
            unavailable(player)
            return
        }
        val redemption = PhysicalRewardRedemption(player.uniqueId, identity, UUID.randomUUID())
        val rejected = synchronized(stateMonitor) {
            !active.get() || pending.putIfAbsent(player.uniqueId, redemption) != null
        }
        if (rejected) {
            if (active.get()) player.sendMessage(TextUtil.mm(BUSY_MESSAGE, true)) else unavailable(player)
            return
        }
        val request =
            OneTimeUseClaimRequest(
                identity = OneTimeUseIdentity(identity.id, identity.fingerprint),
                claimId = redemption.claimId,
                claimantId = player.uniqueId,
                scope = operationScope,
            )
        val taskScope =
            try {
                lifecycle()
            } catch (failure: Throwable) {
                pending.remove(player.uniqueId, redemption)
                redemption.settled.complete(null)
                warn("Physical reward lifecycle unavailable: player={} cause={}", player.uniqueId, failure.javaClass.simpleName)
                unavailable(player)
                return
            }
        val token =
            try {
                taskScope.token()
            } catch (failure: Throwable) {
                pending.remove(player.uniqueId, redemption)
                redemption.settled.complete(null)
                warn("Physical reward lifecycle unavailable: player={} cause={}", player.uniqueId, failure.javaClass.simpleName)
                unavailable(player)
                return
            }
        val claimStage =
            try {
                ledger.claim(request)
            } catch (failure: Throwable) {
                warnClaimFailure(player.uniqueId, identity, failure)
                finishWithoutClaim(redemption, player, UNAVAILABLE_MESSAGE)
                return
            }
        observe(
            claimStage,
            taskScope,
            token,
            prepare = { result, _ ->
                if (result is OneTimeUseClaimResult.Acquired) {
                    redemption.claim = result.claim
                    redemption.phase = PhysicalRewardRedemption.Phase.CLAIMED
                }
            },
            current = { result, failure -> handleClaim(player, redemption, result, failure) },
            stale = { result, failure ->
                redemption.cancelled = true
                handleClaim(player, redemption, result, failure)
            },
        )
    }

    private fun handleClaim(
        player: org.bukkit.entity.Player,
        redemption: PhysicalRewardRedemption,
        result: OneTimeUseClaimResult?,
        failure: Throwable?,
    ) {
        if (failure != null) {
            warnClaimFailure(player.uniqueId, redemption.voucher, failure)
            if (redemption.claim != null) release(redemption, player, null)
            else finishWithoutClaim(redemption, player, UNAVAILABLE_MESSAGE)
            return
        }
        when (result) {
            is OneTimeUseClaimResult.Acquired -> {
                if (redemption.cancelled || !active.get() || !player.isOnline) {
                    release(redemption, player, null)
                    return
                }
                val current = resolveSafely(redemption.voucher.key)
                if (current == null || current.key != redemption.voucher.key || current.fingerprint != redemption.voucher.fingerprint) {
                    release(redemption, player, UNAVAILABLE_MESSAGE)
                    return
                }
                if (PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) != redemption.voucher) {
                    release(redemption, player, UNAVAILABLE_MESSAGE)
                    return
                }
                val rejection =
                    try {
                        canRedeem(player, current)
                    } catch (readFailure: Throwable) {
                        warn("Physical reward preflight failed: player={} voucher={} cause={}", player.uniqueId, redemption.voucher.id, readFailure.javaClass.simpleName)
                        release(redemption, player, UNAVAILABLE_MESSAGE)
                        return
                    }
                if (rejection != null) {
                    release(redemption, player, rejection)
                    return
                }
                redemption.phase = PhysicalRewardRedemption.Phase.MUTATING
                val nativeStage =
                    try {
                        redeem(player, current, redemption.claimId)
                    } catch (mutationFailure: Throwable) {
                        handleNative(player, redemption, null, mutationFailure)
                        return
                    }
                val taskScope = lifecycle.get()
                val token = taskScope?.let { scope -> runCatching { scope.token() }.getOrNull() }
                if (taskScope == null || token == null) {
                    handleNativeStale(player, redemption, null, IllegalStateException("controller lifecycle is closed"))
                    return
                }
                observe(
                    nativeStage,
                    taskScope,
                    token,
                    prepare = { outcome, nativeFailure ->
                        redemption.nativeOutcome = outcome
                        redemption.nativeFailure = nativeFailure
                    },
                    current = { outcome, nativeFailure -> handleNative(player, redemption, outcome, nativeFailure) },
                    stale = { outcome, nativeFailure -> handleNativeStale(player, redemption, outcome, nativeFailure) },
                )
            }
            OneTimeUseClaimResult.AlreadyConsumed -> {
                finishDuplicate(player, redemption)
            }
            OneTimeUseClaimResult.Busy,
            OneTimeUseClaimResult.Missing,
            OneTimeUseClaimResult.IdentityConflict,
            null,
            -> finishWithoutClaim(redemption, player, UNAVAILABLE_MESSAGE)
        }
    }

    private fun handleNative(
        player: org.bukkit.entity.Player,
        redemption: PhysicalRewardRedemption,
        outcome: PhysicalRewardOutcome?,
        failure: Throwable?,
    ) {
        if (redemption.settlementStarted.get()) return
        if (failure != null || outcome is PhysicalRewardOutcome.Uncertain || outcome == null) {
            abandon(redemption, player, failure?.javaClass?.simpleName ?: (outcome as? PhysicalRewardOutcome.Uncertain)?.message)
            return
        }
        when (outcome) {
            PhysicalRewardOutcome.Applied -> commit(redemption, player)
            is PhysicalRewardOutcome.Rejected -> release(redemption, player, outcome.message)
            is PhysicalRewardOutcome.Uncertain -> abandon(redemption, player, outcome.message)
        }
    }

    private fun handleNativeStale(
        player: org.bukkit.entity.Player,
        redemption: PhysicalRewardRedemption,
        outcome: PhysicalRewardOutcome?,
        failure: Throwable?,
    ) {
        redemption.cancelled = true
        handleNative(player, redemption, outcome, failure)
    }

    private fun commit(redemption: PhysicalRewardRedemption, player: org.bukkit.entity.Player) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.COMMITTING
        val claim = redemption.claim ?: run {
            warn("Physical reward commit skipped without a claim: player={} voucher={}", redemption.playerId, redemption.voucher.id)
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            return
        }
        val commitStage =
            try {
                ledger.commit(claim)
            } catch (failure: Throwable) {
                warnSettlementFailure(player, redemption, "commit", failure)
                abandonAfterSettlementFailure(redemption, player, notify = true)
                return
            }
        val taskScope = lifecycle.get()
        val token = taskScope?.let { scope -> runCatching { scope.token() }.getOrNull() }
        if (taskScope == null || token == null) {
            commitStage.whenComplete { result, failure -> finishCommitStale(redemption, player, result, failure) }
            return
        }
        observe(
            commitStage,
            taskScope,
            token,
            prepare = { _, _ -> },
            current = { result, failure -> finishCommit(player, redemption, result, failure) },
            stale = { result, failure -> finishCommitStale(redemption, player, result, failure) },
        )
    }

    private fun finishCommit(
        player: org.bukkit.entity.Player,
        redemption: PhysicalRewardRedemption,
        result: OneTimeUseCommitResult?,
        failure: Throwable?,
    ) {
        if (failure != null || (result != OneTimeUseCommitResult.COMMITTED && result != OneTimeUseCommitResult.ALREADY_COMMITTED)) {
            if (failure != null) warnSettlementFailure(player, redemption, "commit", failure)
            abandonAfterSettlementFailure(redemption, player, notify = true)
            return
        }
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        pending.remove(player.uniqueId, redemption)
        try {
            if (player.isOnline) {
                consumeExact(player, redemption.voucher.id)
                player.sendMessage(TextUtil.mm(APPLIED_MESSAGE, true))
            }
        } finally {
            redemption.settled.complete(null)
        }
    }

    private fun finishCommitStale(
        redemption: PhysicalRewardRedemption,
        player: org.bukkit.entity.Player,
        result: OneTimeUseCommitResult?,
        failure: Throwable?,
    ) {
        if (failure != null || (result != OneTimeUseCommitResult.COMMITTED && result != OneTimeUseCommitResult.ALREADY_COMMITTED)) {
            abandonAfterSettlementFailure(redemption, player, notify = false)
            return
        }
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        pending.remove(redemption.playerId, redemption)
        redemption.settled.complete(null)
    }

    private fun release(redemption: PhysicalRewardRedemption, player: org.bukkit.entity.Player, message: String?) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        val claim = redemption.claim
        if (claim == null) {
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            notifyIfLive(player, message)
            return
        }
        val releaseStage =
            try {
                ledger.release(claim)
            } catch (failure: Throwable) {
                warnSettlementFailure(player, redemption, "release", failure)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
                return
            }
        releaseStage.whenComplete { result, failure ->
            if (failure != null || (result != OneTimeUseReleaseResult.RELEASED && result != OneTimeUseReleaseResult.ALREADY_RELEASED)) {
                if (failure != null) warnSettlementFailure(player, redemption, "release", failure)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
                return@whenComplete
            }
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            notifyIfLive(player, message)
        }
    }

    private fun abandon(redemption: PhysicalRewardRedemption, player: org.bukkit.entity.Player, detail: String?) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        val claim = redemption.claim ?: run {
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            return
        }
        val stage =
            try {
                ledger.abandon(claim)
            } catch (failure: Throwable) {
                warnSettlementFailure(player, redemption, "abandon", failure)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
                return
            }
        stage.whenComplete { result, failure ->
            if (failure != null || result == null) {
                if (failure != null) warnSettlementFailure(player, redemption, "abandon", failure)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
                return@whenComplete
            }
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            warn(
                "Physical reward retained for recovery: player={} voucher={} outcome={}",
                redemption.playerId,
                redemption.voucher.id,
                (detail ?: "unknown").take(64),
            )
            notifyIfLive(player, UNCERTAIN_MESSAGE)
        }
    }

    private fun recoverCancelled(redemption: PhysicalRewardRedemption) {
        if (redemption.settlementStarted.get()) return
        val player = plugin.server.getPlayer(redemption.playerId)
        when (redemption.phase) {
            PhysicalRewardRedemption.Phase.CLAIMING -> {
                // The claim callback records an acquired claim before scheduling
                // its main-thread continuation, so a late callback can release it.
            }
            PhysicalRewardRedemption.Phase.CLAIMED ->
                if (player != null) release(redemption, player, null) else releaseDetached(redemption)
            PhysicalRewardRedemption.Phase.MUTATING ->
                if (redemption.nativeFailure == null && redemption.nativeOutcome is PhysicalRewardOutcome.Rejected) {
                    if (player != null) release(redemption, player, (redemption.nativeOutcome as PhysicalRewardOutcome.Rejected).message)
                    else releaseDetached(redemption)
                } else if (player != null) abandon(redemption, player, null) else abandonDetached(redemption)
            PhysicalRewardRedemption.Phase.COMMITTING,
            PhysicalRewardRedemption.Phase.TERMINAL,
            -> if (player != null) abandon(redemption, player, null) else abandonDetached(redemption)
        }
    }

    private fun releaseDetached(redemption: PhysicalRewardRedemption) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        val claim = redemption.claim ?: run {
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            return
        }
        try {
            ledger.release(claim).whenComplete { _, failure ->
                if (failure != null) warn("Physical reward release failed: player={} voucher={} cause={}", redemption.playerId, redemption.voucher.id, failure.javaClass.simpleName)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
            }
        } catch (failure: Throwable) {
            warn("Physical reward release failed: player={} voucher={} cause={}", redemption.playerId, redemption.voucher.id, failure.javaClass.simpleName)
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
        }
    }

    private fun abandonDetached(redemption: PhysicalRewardRedemption) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        val claim = redemption.claim ?: run {
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            return
        }
        try {
            ledger.abandon(claim).whenComplete { _, failure ->
                if (failure != null) warn("Physical reward abandon failed: player={} voucher={} cause={}", redemption.playerId, redemption.voucher.id, failure.javaClass.simpleName)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
            }
        } catch (failure: Throwable) {
            warn("Physical reward abandon failed: player={} voucher={} cause={}", redemption.playerId, redemption.voucher.id, failure.javaClass.simpleName)
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
        }
    }

    private fun cancel(playerId: UUID) {
        pending[playerId]?.let { redemption ->
            redemption.cancelled = true
            recoverCancelled(redemption)
        }
    }

    private fun finishDuplicate(player: org.bukkit.entity.Player, redemption: PhysicalRewardRedemption) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        pending.remove(redemption.playerId, redemption)
        redemption.settled.complete(null)
        if (!redemption.cancelled && player.isOnline) {
            consumeExact(player, redemption.voucher.id)
            player.sendMessage(TextUtil.mm(DUPLICATE_MESSAGE, true))
        }
    }

    private fun finishWithoutClaim(redemption: PhysicalRewardRedemption, player: org.bukkit.entity.Player, message: String) {
        if (!redemption.settlementStarted.compareAndSet(false, true)) return
        redemption.phase = PhysicalRewardRedemption.Phase.TERMINAL
        pending.remove(redemption.playerId, redemption)
        redemption.settled.complete(null)
        notifyIfLive(player, message)
    }

    private fun abandonAfterSettlementFailure(
        redemption: PhysicalRewardRedemption,
        player: org.bukkit.entity.Player,
        notify: Boolean,
    ) {
        // The commit outcome is unknown. Do not release or replay the native
        // effect; the durable row remains the recovery authority.
        val claim = redemption.claim
        if (claim == null) {
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
            if (notify) notifyIfLive(player, UNCERTAIN_MESSAGE)
            return
        }
        try {
            ledger.abandon(claim).whenComplete { _, failure ->
                if (failure != null) warnSettlementFailure(player, redemption, "abandon-after-commit", failure)
                pending.remove(redemption.playerId, redemption)
                redemption.settled.complete(null)
                if (notify) notifyIfLive(player, UNCERTAIN_MESSAGE)
            }
        } catch (failure: Throwable) {
            warnSettlementFailure(player, redemption, "abandon-after-commit", failure)
            pending.remove(redemption.playerId, redemption)
            redemption.settled.complete(null)
        }
    }

    private fun <T> observe(
        stage: CompletionStage<T>,
        taskScope: LifecycleTaskScope,
        token: LifecycleTaskScope.Token,
        prepare: (T?, Throwable?) -> Unit,
        current: (T?, Throwable?) -> Unit,
        stale: (T?, Throwable?) -> Unit,
    ) {
        // The first callback records the completion before scheduling. If
        // reload/quit races this point, close/cancel sees enough state to
        // release or abandon the claim even when the scheduled callback dies.
        stage.whenComplete { value, failure ->
            prepare(value, failure)
            if (!taskScope.isCurrent(token)) stale(value, failure)
        }
        stage.whenCompleteSync(taskScope, token, current)
    }

    private fun lifecycle(): LifecycleTaskScope =
        lifecycle.updateAndGet { it ?: LifecycleTaskScope(Tasks.scheduler) }!!

    private fun resolveSafely(key: String): PhysicalRewardSpec? =
        try {
            resolve(key)
        } catch (failure: Throwable) {
            warn("Physical reward source resolution failed: key={} cause={}", key, failure.javaClass.simpleName)
            null
        }

    /** Sends player-facing text only through the current Paper lifecycle. */
    private fun notifyIfLive(player: org.bukkit.entity.Player, message: String?) {
        if (message == null) return
        val taskScope = lifecycle.get() ?: return
        val token = runCatching { taskScope.token() }.getOrNull() ?: return
        taskScope.runSync(token) {
            if (active.get() && player.isOnline) player.sendMessage(TextUtil.mm(message, true))
        }
    }

    private fun appendActionHint(stack: ItemStack) {
        stack.editMeta { meta ->
            val lore = meta.lore().orEmpty()
            val plain = lore.joinToString("\n") {
                PlainTextComponentSerializer.plainText().serialize(TextUtil.strip(it) ?: Component.empty())
            }
            if (plain.contains("ПКМ") && plain.contains("получ", ignoreCase = true)) return@editMeta
            meta.lore(lore + listOf(Component.empty(), TextUtil.mm(ACTION_HINT, true)))
        }
    }

    private fun consumeExact(player: org.bukkit.entity.Player, voucherId: UUID): Boolean {
        val inventory = player.inventory
        for (slot in 0 until inventory.size) {
            val stack = inventory.getItem(slot) ?: continue
            if (PhysicalRewardVoucher.identity(stack)?.id != voucherId) continue
            if (stack.amount <= 1) inventory.setItem(slot, null)
            else inventory.setItem(slot, stack.clone().also { it.amount = stack.amount - 1 })
            return true
        }
        return false
    }

    private fun unavailable(player: org.bukkit.entity.Player) {
        if (player.isOnline) player.sendMessage(TextUtil.mm(UNAVAILABLE_MESSAGE, true))
    }

    private fun warnClaimFailure(playerId: UUID, voucher: PhysicalRewardVoucherIdentity, failure: Throwable) {
        warn("Physical reward claim failed: player={} voucher={} cause={}", playerId, voucher.id, failure.javaClass.simpleName)
    }

    private fun warnSettlementFailure(player: org.bukkit.entity.Player, redemption: PhysicalRewardRedemption, operation: String, failure: Throwable) {
        warn("Physical reward {} failed: player={} voucher={} cause={}", operation, redemption.playerId, redemption.voucher.id, failure.javaClass.simpleName)
    }

    companion object {
        private val RIGHT_CLICK_ACTIONS = setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK)
        private const val ACTION_HINT = "<#79ffb8>[▶] ПКМ — получить"
        private const val APPLIED_MESSAGE = "<#79ffb8>Награда получена."
        private const val BUSY_MESSAGE = "<#ffcb70>Получение уже выполняется."
        private const val DUPLICATE_MESSAGE = "<#ffcb70>Этот ваучер уже использован."
        private const val UNAVAILABLE_MESSAGE = "<#ffcb70>Награда сейчас недоступна."
        private const val UNCERTAIN_MESSAGE = "<#ffcb70>Выдача сохранена для проверки администрацией."
    }
}
