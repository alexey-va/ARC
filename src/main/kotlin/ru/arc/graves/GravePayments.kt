package ru.arc.graves

import com.google.gson.Gson
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.Tasks
import ru.arc.core.TaskScheduler
import ru.arc.core.modules.EconomyModule
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecordJournal
import ru.arc.util.Logging.warn
import net.milkbowl.vault.economy.Economy
import java.nio.file.Path
import java.util.UUID

/** A durable intent precedes native teleport. Only its successful post-event may debit once. */
internal class GravePayments(
    root: Path,
    private val feedback: (Player, String) -> Unit,
    scheduler: TaskScheduler = Tasks.scheduler,
    private val journalFactory: (Path) -> GravePaymentJournal = ::DurableGravePaymentJournal,
    private val economyProvider: () -> Economy? = EconomyModule::getEconomy,
    private val warning: (String, Throwable?) -> Unit = { message, failure ->
        if (failure == null) warn("{}", message) else warn("{}", message, failure)
    },
) : AutoCloseable {
    internal data class Intent(val id: UUID, val player: UUID, val grave: UUID, val price: Double)
    private enum class Phase { PREPARING, READY, TELEPORTING, CHARGED, UNKNOWN, FINISHING }
    private class Pending(val intent: Intent, @Volatile var phase: Phase = Phase.PREPARING) {
        @Volatile var durableCommitted = false
        var acknowledgementScheduled = false
    }
    private val tasks = LifecycleTaskScope(scheduler)
    private val scheduler = scheduler
    private val pending = mutableMapOf<UUID, Pending>()
    private val blocked = mutableSetOf<UUID>()
    private lateinit var journal: GravePaymentJournal
    private var ready = false
    @Volatile
    private var closed = false

    init {
        tasks.runAsync {
            val recovered = runCatching {
                val opened = journalFactory(root)
                opened to opened.loadAll().map { it.player }
            }
            tasks.runSync {
                recovered.onSuccess {
                    journal = it.first
                    blocked.addAll(it.second)
                    ready = true
                    if (it.second.isNotEmpty()) warning("Grave payments: ${it.second.size} unresolved durable intents require review; no debit will be retried", null)
                }.onFailure { warning("Grave payment journal could not load", it) }
            }
        }
    }

    fun prepare(player: Player, grave: UUID, price: Double, valid: () -> Boolean, start: () -> Unit) {
        if (closed || !ready || player.uniqueId in blocked) { feedback(player, "payment-unavailable"); return }
        if (player.uniqueId in pending) { feedback(player, "payment-pending"); return }
        if (!valid()) { feedback(player, "changed"); return }
        if (!price.isFinite() || price < 0.0) { feedback(player, "payment-unavailable"); return }
        if (price > 0) {
            val economy = try {
                economyProvider()
            } catch (failure: Throwable) {
                warning("Grave balance provider lookup failed player=${player.uniqueId} grave=$grave", failure)
                feedback(player, "payment-unavailable")
                return
            }
            if (economy == null) { feedback(player, "payment-unavailable"); return }
            val hasFunds = try {
                economy.has(player, price)
            } catch (failure: Throwable) {
                warning("Grave balance check failed player=${player.uniqueId} grave=$grave", failure)
                feedback(player, "payment-unavailable")
                return
            }
            if (!hasFunds) { feedback(player, "insufficient"); return }
        }
        val entry = Pending(Intent(UUID.randomUUID(), player.uniqueId, grave, price))
        pending[player.uniqueId] = entry
        fun admit() {
            if (closed || pending[player.uniqueId] !== entry || !player.isOnline || !valid()) {
                finishUncharged(entry); return
            }
            entry.phase = Phase.READY
            try { start() } catch (failure: Throwable) {
                warning("Grave teleport failed before completion player=${player.uniqueId} grave=$grave", failure)
                finishUncharged(entry)
                feedback(player, "teleport-failed")
            }
            // Late native callbacks are rejected once this admission expires. Never debit on timeout.
            tasks.runLater(20L * 120) {
                if (pending[player.uniqueId] === entry && entry.phase in setOf(Phase.READY, Phase.TELEPORTING)) {
                    finishUncharged(entry)
                    if (player.isOnline) feedback(player, "teleport-failed")
                }
            }
        }
        if (price == 0.0) { admit(); return }
        tasks.runAsync {
            val stored = runCatching { journal.commit(entry.intent.id.toString(), entry.intent) }
            entry.durableCommitted = stored.getOrNull() == entry.intent
            if (closed) {
                settleCommitAfterClose(entry, stored)
                return@runAsync
            }
            tasks.runSync {
                if (closed) {
                    settleCommitAfterClose(entry, stored)
                    return@runSync
                }
                if (!entry.durableCommitted) {
                    blocked += player.uniqueId
                    entry.phase = Phase.UNKNOWN
                    warning("Grave payment intent write/readback failed player=${player.uniqueId} operation=${entry.intent.id}", stored.exceptionOrNull())
                    feedback(player, "payment-unavailable")
                } else admit()
            }
        }
    }

    /** The native pre-event consumes the admission; legacy/native commands cannot reuse it. */
    fun admitNative(player: Player, grave: UUID): Boolean {
        val entry = pending[player.uniqueId] ?: return false
        if (entry.intent.grave != grave || entry.phase != Phase.READY) return false
        entry.phase = Phase.TELEPORTING
        return true
    }

    fun charge(player: Player, grave: UUID, price: Double, valid: Boolean): Boolean {
        val entry = pending[player.uniqueId] ?: return false
        if (entry.intent.grave != grave || entry.phase != Phase.TELEPORTING) return false
        if (!valid || entry.intent.price != price) { feedback(player, "changed"); return false }
        if (price == 0.0) { entry.phase = Phase.CHARGED; return true }
        val economy = try {
            economyProvider()
        } catch (failure: Throwable) {
            warning("Grave balance provider lookup failed player=${player.uniqueId} grave=$grave", failure)
            feedback(player, "payment-unavailable")
            return false
        } ?: run {
            feedback(player, "payment-unavailable")
            return false
        }
        val hasFunds = try {
            economy.has(player, price)
        } catch (failure: Throwable) {
            warning("Grave balance check failed player=${player.uniqueId} grave=$grave", failure)
            feedback(player, "payment-unavailable")
            return false
        }
        if (!hasFunds) { feedback(player, "insufficient"); return false }
        // Mark unknown before entering the provider. A thrown/ambiguous debit is never replayed.
        entry.phase = Phase.UNKNOWN
        return try {
            if (economy.withdrawPlayer(player, price).transactionSuccess()) {
                entry.phase = Phase.CHARGED
                true
            } else {
                entry.phase = Phase.TELEPORTING
                feedback(player, "payment-failed")
                false
            }
        } catch (failure: Throwable) {
            blocked += player.uniqueId
            warning("Grave debit outcome unknown player=${player.uniqueId} grave=$grave operation=${entry.intent.id}", failure)
            feedback(player, "payment-unavailable")
            false
        }
    }

    /** Called at MONITOR after every cancellation has settled; refunds only a known successful debit. */
    fun complete(player: Player, grave: UUID, cancelled: Boolean) {
        val entry = pending[player.uniqueId]?.takeIf { it.intent.grave == grave } ?: return
        if (entry.phase == Phase.UNKNOWN || entry.phase == Phase.FINISHING) return
        if (entry.phase == Phase.CHARGED && cancelled && entry.intent.price > 0) {
            entry.phase = Phase.UNKNOWN
            val refunded = runCatching {
                economyProvider()?.depositPlayer(player, entry.intent.price)?.transactionSuccess() == true
            }
            if (refunded.getOrDefault(false) != true) {
                blocked += player.uniqueId
                warning("Grave rollback refund unresolved player=${player.uniqueId} operation=${entry.intent.id}", refunded.exceptionOrNull())
                feedback(player, "payment-unavailable")
                return
            }
        }
        if (!cancelled && entry.phase == Phase.CHARGED) feedback(player, "teleported")
        acknowledge(entry)
    }

    private fun finishUncharged(entry: Pending) {
        if (entry.phase in setOf(Phase.UNKNOWN, Phase.CHARGED, Phase.FINISHING)) return
        if (entry.phase == Phase.PREPARING && !entry.durableCommitted) return
        acknowledge(entry)
    }

    private fun acknowledge(entry: Pending) {
        entry.phase = Phase.FINISHING
        if (entry.intent.price == 0.0) { pending.remove(entry.intent.player, entry); return }
        scheduleAcknowledgement(entry)
    }

    private fun settleCommitAfterClose(entry: Pending, stored: Result<Intent>) {
        if (entry.durableCommitted) {
            acknowledgeAfterClose(entry)
        } else {
            warning(
                "Grave payment intent write/readback unresolved after close player=${entry.intent.player} operation=${entry.intent.id}",
                stored.exceptionOrNull(),
            )
        }
    }

    /** The completed write is known unpaid; cleanup may run after the lifecycle scope is closed. */
    private fun acknowledgeAfterClose(entry: Pending) {
        if (!entry.durableCommitted || entry.intent.price == 0.0) return
        entry.phase = Phase.FINISHING
        acknowledgeInline(entry, "after close")
    }

    /** Shutdown cleanup performs only bounded journal I/O and never asks Bukkit for a new task. */
    private fun acknowledgeInline(entry: Pending, context: String) {
        val result = synchronized(entry) {
            runCatching { journal.acknowledgeExactly(entry.intent.id.toString(), entry.intent) }
        }
        if (result.getOrNull() !in setOf(DurableAcknowledgementOutcome.ACKNOWLEDGED, DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED)) {
            warning(
                "Grave payment acknowledgement unresolved $context player=${entry.intent.player} operation=${entry.intent.id} result=${result.getOrNull()}",
                result.exceptionOrNull(),
            )
        }
    }

    private fun scheduleAcknowledgement(entry: Pending) {
        val shouldSchedule = synchronized(entry) {
            if (entry.acknowledgementScheduled) false
            else {
                entry.acknowledgementScheduled = true
                true
            }
        }
        if (!shouldSchedule) return

        try {
            // This storage-only task is deliberately outside the lifecycle scope so close cannot
            // invalidate a write acknowledgement after its commit has already completed.
            scheduler.runAsync(Runnable {
                val result = synchronized(entry) {
                    runCatching { journal.acknowledgeExactly(entry.intent.id.toString(), entry.intent) }
                }
                if (closed) {
                    if (result.getOrNull() !in setOf(DurableAcknowledgementOutcome.ACKNOWLEDGED, DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED)) {
                        warning("Grave payment acknowledgement unresolved after close player=${entry.intent.player} operation=${entry.intent.id} result=${result.getOrNull()}", result.exceptionOrNull())
                    }
                    return@Runnable
                }
                tasks.runSync {
                    if (closed) return@runSync
                    if (result.getOrNull() in setOf(DurableAcknowledgementOutcome.ACKNOWLEDGED, DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED)) {
                        pending.remove(entry.intent.player, entry)
                    } else {
                        blocked += entry.intent.player
                        warning("Grave payment acknowledgement unresolved player=${entry.intent.player} operation=${entry.intent.id} result=${result.getOrNull()}", result.exceptionOrNull())
                    }
                }
            })
        } catch (failure: Throwable) {
            warning("Could not schedule grave payment acknowledgement player=${entry.intent.player} operation=${entry.intent.id}", failure)
        }
    }

    override fun close() {
        closed = true
        pending.values.toList().forEach { entry ->
            when (entry.phase) {
                Phase.PREPARING -> if (entry.durableCommitted) acknowledgeAfterClose(entry)
                // The native post handlers are synchronous, so READY/TELEPORTING are known unpaid
                // here. Drain committed intents inline; Bukkit may reject new tasks during disable.
                Phase.READY, Phase.TELEPORTING, Phase.FINISHING -> {
                    if (entry.intent.price == 0.0) pending.remove(entry.intent.player, entry)
                    else acknowledgeAfterClose(entry)
                }
                Phase.CHARGED, Phase.UNKNOWN -> Unit
            }
        }
        tasks.close()
    }
}

/** Narrow storage seam for deterministic lifecycle and provider tests. */
internal interface GravePaymentJournal {
    fun loadAll(): List<GravePayments.Intent>

    fun commit(recordId: String, intent: GravePayments.Intent): GravePayments.Intent

    fun acknowledgeExactly(recordId: String, expected: GravePayments.Intent): DurableAcknowledgementOutcome
}

internal class DurableGravePaymentJournal(root: Path) : GravePaymentJournal {
    private val gson = Gson()
    private val journal = DurableRecordJournal(
        root, Path.of("data/grave-payments"), 4096,
        { value: GravePayments.Intent -> gson.toJson(value).toByteArray(Charsets.UTF_8) },
        { bytes -> gson.fromJson(bytes.toString(Charsets.UTF_8), GravePayments.Intent::class.java) },
        { value -> value.also { require(it.price.isFinite() && it.price > 0) } },
    )

    override fun loadAll(): List<GravePayments.Intent> = journal.loadAll().map { it.value }

    override fun commit(recordId: String, intent: GravePayments.Intent): GravePayments.Intent = journal.commit(recordId, intent)

    override fun acknowledgeExactly(recordId: String, expected: GravePayments.Intent): DurableAcknowledgementOutcome =
        journal.acknowledgeExactly(recordId, expected) { a, b -> a == b }
}
