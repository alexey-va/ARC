package ru.arc.payments

import com.google.gson.Gson
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.core.Tasks
import ru.arc.core.async
import ru.arc.core.modules.EconomyModule
import ru.arc.core.sync
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecordJournal
import ru.arc.util.Common
import ru.arc.util.Logging.warn
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Immutable-at-submit item payment intent. Arrays are copied before any task is queued. */
data class ItemPaymentRequest(
    val operationId: UUID,
    val playerId: UUID,
    val slot: Int,
    val originalItemBytes: ByteArray,
    val replacementItemBytes: ByteArray,
    /** Vault currency in minor units: 100 minor units = 1 coin. */
    val priceMinor: Long,
)

enum class ItemPaymentOutcome {
    SUCCESS,
    CANCELLED,
    INSUFFICIENT_FUNDS,
    BUSY_OR_DUPLICATE,
    UNAVAILABLE,
    FAILED_UNCHARGED,
    OUTCOME_UNKNOWN,
}

/**
 * One pending payment record. A committed record is deliberately left on disk
 * until payment and item mutation are both known complete, or a known-unpaid
 * path has been exactly acknowledged.
 */
data class ItemPaymentJournalRecord(
    val operationId: String,
    val playerId: String,
    val slot: Int,
    val originalItemBase64: String,
    val replacementItemBase64: String,
    val priceMinor: Long,
    val balanceBeforeMajor: Double,
    val createdAtMillis: Long,
) {
    fun validated(): ItemPaymentJournalRecord {
        require(canonicalUuid(operationId)) { "Invalid item payment operation id" }
        require(canonicalUuid(playerId)) { "Invalid item payment player id" }
        require(slot in 0..MAX_SLOT) { "Invalid item payment slot" }
        require(priceMinor > 0L) { "Item payment must have a positive price" }
        require(balanceBeforeMajor.isFinite() && balanceBeforeMajor >= 0.0) { "Invalid payment balance snapshot" }
        require(createdAtMillis > 0L) { "Invalid item payment timestamp" }
        require(decodeItem(originalItemBase64).size <= MAX_ITEM_BYTES) { "Original item snapshot is too large" }
        require(decodeItem(replacementItemBase64).size <= MAX_ITEM_BYTES) { "Replacement item snapshot is too large" }
        return this
    }

    internal fun sameContent(other: ItemPaymentJournalRecord): Boolean =
        operationId == other.operationId &&
            playerId == other.playerId &&
            slot == other.slot &&
            originalItemBase64 == other.originalItemBase64 &&
            replacementItemBase64 == other.replacementItemBase64 &&
            priceMinor == other.priceMinor &&
            balanceBeforeMajor.toBits() == other.balanceBeforeMajor.toBits() &&
            createdAtMillis == other.createdAtMillis

    companion object {
        private const val MAX_SLOT = 255
        private const val MAX_ITEM_BYTES = 64 * 1024

        private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        private fun decodeItem(value: String): ByteArray =
            runCatching { Base64.getDecoder().decode(value) }.getOrElse { throw IllegalArgumentException("Invalid item snapshot encoding", it) }
    }
}

interface ItemPaymentJournal {
    fun loadAll(): List<ItemPaymentJournalRecord>

    fun commit(record: ItemPaymentJournalRecord): ItemPaymentJournalRecord

    fun acknowledgeExactly(record: ItemPaymentJournalRecord): DurableAcknowledgementOutcome
}

/** Production adapter using the shared bounded, atomic durable record journal. */
class FileItemPaymentJournal(
    root: Path,
    relativeDirectory: Path,
    gson: Gson = Common.prettyGson,
) : ItemPaymentJournal {
    private val durable =
        DurableRecordJournal(
            root = root,
            relativeDirectory = relativeDirectory,
            maxRecordBytes = MAX_RECORD_BYTES,
            encode = { record: ItemPaymentJournalRecord -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
            decode = { bytes -> gson.fromJson(bytes.toString(StandardCharsets.UTF_8), ItemPaymentJournalRecord::class.java) },
            validate = ItemPaymentJournalRecord::validated,
        )

    override fun loadAll(): List<ItemPaymentJournalRecord> = durable.loadAll().map { it.value.validated() }

    override fun commit(record: ItemPaymentJournalRecord): ItemPaymentJournalRecord {
        val valid = record.validated()
        return durable.commit(valid.operationId, valid).validated()
    }

    override fun acknowledgeExactly(record: ItemPaymentJournalRecord): DurableAcknowledgementOutcome =
        durable.acknowledgeExactly(record.operationId, record) { expected, current -> expected.sameContent(current) }

    private companion object {
        const val MAX_RECORD_BYTES = 192L * 1024L
    }
}

/**
 * A small scheduling seam keeps disk I/O asynchronous and all Vault/Bukkit work
 * on the Paper main thread. Production always delegates through [Tasks].
 */
interface ItemPaymentTasks {
    fun async(task: () -> Unit)

    fun sync(task: () -> Unit)
}

private object PaperItemPaymentTasks : ItemPaymentTasks {
    override fun async(task: () -> Unit) {
        Tasks.scheduler.async { task() }
    }

    override fun sync(task: () -> Unit) {
        Tasks.scheduler.sync { task() }
    }
}

/**
 * Owns one-shot Vault debits for item mutations.
 *
 * Call [start] after ARC installs `Tasks`; [isReady] remains false until the
 * durable journal has loaded. Call [close] before module shutdown/reload. The
 * supplied callbacks are invoked on the main thread: [validate] must re-check
 * the current operation, slot and item immediately before the debit, and [apply]
 * must perform the caller-owned native item mutation after a successful debit.
 * Neither callback may block. A thrown/ambiguous withdrawal or item mutation
 * leaves its durable record present and locks that player; this service never
 * retries, refunds, or clears such a record automatically.
 */
class ItemPayments(
    private val journalFactory: () -> ItemPaymentJournal,
    private val economyProvider: () -> Economy? = EconomyModule::getEconomy,
    private val playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
    private val tasks: ItemPaymentTasks = PaperItemPaymentTasks,
    private val isMainThread: () -> Boolean = Bukkit::isPrimaryThread,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val warning: (String, Throwable?) -> Unit = { message, failure ->
        warn("{}", if (failure == null) message else "$message (${failure::class.java.simpleName})")
    },
) {
    /** Test/embedded seam for a journal whose construction has no filesystem work. */
    constructor(
        journal: ItemPaymentJournal,
        economyProvider: () -> Economy? = EconomyModule::getEconomy,
        playerLookup: (UUID) -> Player? = Bukkit::getPlayer,
        tasks: ItemPaymentTasks = PaperItemPaymentTasks,
        isMainThread: () -> Boolean = Bukkit::isPrimaryThread,
        clockMillis: () -> Long = System::currentTimeMillis,
        warning: (String, Throwable?) -> Unit = { message, failure ->
            warn("{}", if (failure == null) message else "$message (${failure::class.java.simpleName})")
        },
    ) : this({ journal }, economyProvider, playerLookup, tasks, isMainThread, clockMillis, warning)

    private enum class State { NEW, LOADING, READY, UNAVAILABLE, CLOSED }

    private enum class Phase { PREPARE, COMMIT, REVALIDATE, WITHDRAW, APPLY, ACKNOWLEDGE, RECOVERY }

    private class Pending(
        val request: ItemPaymentRequest,
        val validate: () -> Boolean,
        val apply: () -> Unit,
        val completion: (ItemPaymentOutcome) -> Unit,
        val generation: Long,
    ) {
        @Volatile var phase: Phase = Phase.PREPARE
        @Volatile var invalidated: Boolean = false
    }

    private val lock = Any()
    private val generation = AtomicLong(0L)
    private val activeByPlayer = linkedMapOf<UUID, Pending>()
    private val activeOperationIds = mutableSetOf<UUID>()
    private val recentOperationIds = LinkedHashMap<UUID, Unit>(RECENT_OPERATION_LIMIT, 0.75f, true)
    private val unresolvedPlayers = mutableSetOf<UUID>()
    @Volatile private var loadedJournal: ItemPaymentJournal? = null
    @Volatile private var state: State = State.NEW

    val isReady: Boolean get() = state == State.READY

    /** Asynchronously loads unresolved intents before accepting payments. */
    fun start() {
        val startedGeneration = synchronized(lock) {
            when (state) {
                State.LOADING, State.READY -> return
                State.CLOSED -> return
                State.NEW, State.UNAVAILABLE -> {
                    state = State.LOADING
                    generation.incrementAndGet()
                }
            }
        }
        try {
            tasks.async {
                val load = runCatching {
                    val loaded = journalFactory()
                    val records = loaded.loadAll().also { records ->
                        require(records.size <= MAX_UNRESOLVED_RECORDS) { "Item payment journal exceeds its unresolved-record limit" }
                        records.forEach(ItemPaymentJournalRecord::validated)
                        require(records.map { it.operationId }.distinct().size == records.size) { "Duplicate item payment operation id" }
                    }
                    loaded to records
                }
                tasks.sync {
                    synchronized(lock) {
                        if (state != State.LOADING || generation.get() != startedGeneration) return@sync
                        load.fold(
                            onSuccess = { (openedJournal, records) ->
                                loadedJournal = openedJournal
                                unresolvedPlayers.clear()
                                unresolvedPlayers += records.map { UUID.fromString(it.playerId) }
                                state = State.READY
                                records.forEach { record ->
                                    warnOperation(
                                        UUID.fromString(record.operationId),
                                        UUID.fromString(record.playerId),
                                        Phase.RECOVERY,
                                        "unresolved_record_found",
                                    )
                                }
                            },
                            onFailure = { failure ->
                                state = State.UNAVAILABLE
                                warnFailure(null, null, Phase.COMMIT, failure)
                            },
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) { if (generation.get() == startedGeneration) state = State.UNAVAILABLE }
            warnFailure(null, null, Phase.COMMIT, failure)
        }
    }

    /**
     * Queues one intent. Calls from any thread are marshalled to Paper main;
     * duplicate operation IDs and another active request for the same player
     * are rejected before a journal record or Vault call is made.
     */
    fun submit(
        request: ItemPaymentRequest,
        validate: () -> Boolean,
        apply: () -> Unit,
        completion: (ItemPaymentOutcome) -> Unit,
    ) {
        val invalid = validateRequest(request)
        if (invalid != null) {
            try {
                tasks.sync { completeSafely(completion, invalid) }
            } catch (failure: Throwable) {
                warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
            }
            return
        }
        val snapshot = snapshotRequest(request)
        try {
            tasks.sync { begin(snapshot, validate, apply, completion) }
        } catch (failure: Throwable) {
            warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
        }
    }

    /** Invalidates queued/pre-debit work. A committed record is retained unless an unpaid path proves exact acknowledgement. */
    fun close() {
        synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            generation.incrementAndGet()
            activeByPlayer.values
                .filter { it.phase in setOf(Phase.PREPARE, Phase.COMMIT, Phase.REVALIDATE) }
                .forEach { it.invalidated = true }
        }
    }

    private fun begin(
        request: ItemPaymentRequest,
        validate: () -> Boolean,
        apply: () -> Unit,
        completion: (ItemPaymentOutcome) -> Unit,
    ) {
        if (!isMainThread()) {
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        val immediate = synchronized(lock) {
            when {
                state != State.READY -> ItemPaymentOutcome.UNAVAILABLE
                request.operationId in activeOperationIds || request.operationId in recentOperationIds -> ItemPaymentOutcome.BUSY_OR_DUPLICATE
                request.playerId in unresolvedPlayers -> ItemPaymentOutcome.OUTCOME_UNKNOWN
                request.playerId in activeByPlayer -> ItemPaymentOutcome.BUSY_OR_DUPLICATE
                activeByPlayer.size + unresolvedPlayers.size >= MAX_UNRESOLVED_RECORDS -> ItemPaymentOutcome.UNAVAILABLE
                else -> null
            }
        }
        if (immediate != null) {
            completeSafely(completion, immediate)
            return
        }
        val invalid = validateRequest(request)
        if (invalid != null) {
            completeSafely(completion, invalid)
            return
        }
        val player = runCatching { playerLookup(request.playerId)?.takeIf(Player::isOnline) }.getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        if (player == null) {
            completeSafely(completion, ItemPaymentOutcome.CANCELLED)
            return
        }
        val economy = runCatching(economyProvider).getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        } ?: run {
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        val amount = minorToMajor(request.priceMinor) ?: run {
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        val balanceBefore = runCatching { economy.getBalance(player) }.getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        if (!balanceBefore.isFinite() || balanceBefore < 0.0) {
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        val canPay = runCatching { economy.has(player, amount) }.getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.PREPARE, failure)
            completeSafely(completion, ItemPaymentOutcome.UNAVAILABLE)
            return
        }
        if (!canPay) {
            completeSafely(completion, ItemPaymentOutcome.INSUFFICIENT_FUNDS)
            return
        }

        val pending = synchronized(lock) {
            if (state != State.READY || request.playerId in unresolvedPlayers || request.playerId in activeByPlayer ||
                request.operationId in activeOperationIds || request.operationId in recentOperationIds
            ) {
                null
            } else {
                Pending(request, validate, apply, completion, generation.get()).also {
                    activeByPlayer[request.playerId] = it
                    activeOperationIds += request.operationId
                }
            }
        }
        if (pending == null) {
            completeSafely(completion, ItemPaymentOutcome.BUSY_OR_DUPLICATE)
            return
        }

        val record =
            ItemPaymentJournalRecord(
                operationId = request.operationId.toString(),
                playerId = request.playerId.toString(),
                slot = request.slot,
                originalItemBase64 = Base64.getEncoder().encodeToString(request.originalItemBytes),
                replacementItemBase64 = Base64.getEncoder().encodeToString(request.replacementItemBytes),
                priceMinor = request.priceMinor,
                balanceBeforeMajor = balanceBefore,
                createdAtMillis = clockMillis().coerceAtLeast(1L),
            ).validated()
        pending.phase = Phase.COMMIT
        try {
            tasks.async {
                val committed =
                    runCatching {
                        requireNotNull(loadedJournal) { "Item payment journal is not initialized" }
                            .commit(record)
                            .also { require(record.sameContent(it)) { "Journal readback changed item payment intent" } }
                    }
                tasks.sync {
                    if (committed.isFailure) {
                        acknowledge(pending, record, ItemPaymentOutcome.FAILED_UNCHARGED, Phase.COMMIT)
                    } else {
                        afterCommit(pending, record)
                    }
                }
            }
        } catch (failure: Throwable) {
            warnFailure(request.operationId, request.playerId, Phase.COMMIT, failure)
            acknowledge(pending, record, ItemPaymentOutcome.FAILED_UNCHARGED, Phase.COMMIT)
        }
    }

    private fun afterCommit(pending: Pending, record: ItemPaymentJournalRecord) {
        val request = pending.request
        pending.phase = Phase.REVALIDATE
        if (!canContinue(pending) || !isMainThread()) {
            acknowledge(pending, record, ItemPaymentOutcome.CANCELLED, Phase.REVALIDATE)
            return
        }
        val player = runCatching { playerLookup(request.playerId)?.takeIf(Player::isOnline) }.getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.REVALIDATE, failure)
            acknowledge(pending, record, ItemPaymentOutcome.UNAVAILABLE, Phase.REVALIDATE)
            return
        }
        if (player == null || !runCatching(pending.validate).getOrElse { failure ->
                warnFailure(request.operationId, request.playerId, Phase.REVALIDATE, failure)
                false
            }
        ) {
            acknowledge(pending, record, ItemPaymentOutcome.CANCELLED, Phase.REVALIDATE)
            return
        }
        val economy = runCatching(economyProvider).getOrElse { failure ->
            warnFailure(request.operationId, request.playerId, Phase.REVALIDATE, failure)
            acknowledge(pending, record, ItemPaymentOutcome.UNAVAILABLE, Phase.REVALIDATE)
            return
        } ?: run {
            acknowledge(pending, record, ItemPaymentOutcome.UNAVAILABLE, Phase.REVALIDATE)
            return
        }
        val amount = minorToMajor(request.priceMinor) ?: run {
            acknowledge(pending, record, ItemPaymentOutcome.UNAVAILABLE, Phase.REVALIDATE)
            return
        }
        val canPay = runCatching { economy.has(player, amount) }
        if (canPay.isFailure) {
            warnFailure(request.operationId, request.playerId, Phase.REVALIDATE, canPay.exceptionOrNull()!!)
            acknowledge(pending, record, ItemPaymentOutcome.UNAVAILABLE, Phase.REVALIDATE)
            return
        }
        if (!canPay.getOrThrow()) {
            acknowledge(pending, record, ItemPaymentOutcome.INSUFFICIENT_FUNDS, Phase.REVALIDATE)
            return
        }
        if (!runCatching(pending.validate).getOrElse { failure ->
                warnFailure(request.operationId, request.playerId, Phase.REVALIDATE, failure)
                false
            }
        ) {
            acknowledge(pending, record, ItemPaymentOutcome.CANCELLED, Phase.REVALIDATE)
            return
        }

        if (!enterWithdrawal(pending)) {
            acknowledge(pending, record, ItemPaymentOutcome.CANCELLED, Phase.REVALIDATE)
            return
        }
        val response = try {
            economy.withdrawPlayer(player, amount)
        } catch (failure: Throwable) {
            markUnknown(pending, Phase.WITHDRAW, failure)
            return
        }
        if (!response.transactionSuccess()) {
            val currentBalance = runCatching { economy.getBalance(player) }.getOrNull()
            val outcome =
                if (currentBalance != null && currentBalance.isFinite() && currentBalance + BALANCE_EPSILON < amount) {
                    ItemPaymentOutcome.INSUFFICIENT_FUNDS
                } else {
                    ItemPaymentOutcome.FAILED_UNCHARGED
                }
            acknowledge(pending, record, outcome, Phase.WITHDRAW)
            return
        }

        pending.phase = Phase.APPLY
        try {
            pending.apply()
        } catch (failure: Throwable) {
            markUnknown(pending, Phase.APPLY, failure)
            return
        }
        acknowledge(pending, record, ItemPaymentOutcome.SUCCESS, Phase.APPLY)
    }

    private fun canContinue(pending: Pending): Boolean =
        synchronized(lock) {
            state == State.READY && !pending.invalidated && pending.generation == generation.get() &&
                activeByPlayer[pending.request.playerId] === pending
        }

    /** Linearizes shutdown against the irreversible Vault boundary. */
    private fun enterWithdrawal(pending: Pending): Boolean =
        synchronized(lock) {
            val allowed =
                state == State.READY && !pending.invalidated && pending.generation == generation.get() &&
                    activeByPlayer[pending.request.playerId] === pending
            if (allowed) pending.phase = Phase.WITHDRAW
            allowed
        }

    private fun acknowledge(
        pending: Pending,
        record: ItemPaymentJournalRecord,
        outcome: ItemPaymentOutcome,
        precedingPhase: Phase,
    ) {
        pending.phase = Phase.ACKNOWLEDGE
        try {
            tasks.async {
                val result =
                    runCatching {
                        requireNotNull(loadedJournal) { "Item payment journal is not initialized" }
                            .acknowledgeExactly(record)
                    }
                tasks.sync {
                    val ack = result.getOrNull()
                    if (ack == DurableAcknowledgementOutcome.ACKNOWLEDGED || ack == DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED) {
                        finish(pending, outcome, lockPlayer = false)
                    } else {
                        val failure = result.exceptionOrNull()
                        synchronized(lock) { unresolvedPlayers += pending.request.playerId }
                        if (failure != null) warnFailure(pending.request.operationId, pending.request.playerId, Phase.ACKNOWLEDGE, failure)
                        else warnOperation(pending.request.operationId, pending.request.playerId, Phase.ACKNOWLEDGE, "acknowledgement_${ack?.name?.lowercase() ?: "missing"}_after_${precedingPhase.name.lowercase()}")
                        finish(pending, ItemPaymentOutcome.OUTCOME_UNKNOWN, lockPlayer = true)
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) { unresolvedPlayers += pending.request.playerId }
            warnFailure(pending.request.operationId, pending.request.playerId, Phase.ACKNOWLEDGE, failure)
            finish(pending, ItemPaymentOutcome.OUTCOME_UNKNOWN, lockPlayer = true)
        }
    }

    private fun markUnknown(pending: Pending, phase: Phase, failure: Throwable) {
        synchronized(lock) { unresolvedPlayers += pending.request.playerId }
        warnFailure(pending.request.operationId, pending.request.playerId, phase, failure)
        finish(pending, ItemPaymentOutcome.OUTCOME_UNKNOWN, lockPlayer = true)
    }

    private fun finish(pending: Pending, outcome: ItemPaymentOutcome, lockPlayer: Boolean) {
        synchronized(lock) {
            if (lockPlayer) unresolvedPlayers += pending.request.playerId
            activeByPlayer.remove(pending.request.playerId, pending)
            activeOperationIds.remove(pending.request.operationId)
            rememberCompleted(pending.request.operationId)
        }
        completeSafely(pending.completion, outcome)
    }

    private fun rememberCompleted(operationId: UUID) {
        recentOperationIds[operationId] = Unit
        while (recentOperationIds.size > RECENT_OPERATION_LIMIT) {
            val oldest = recentOperationIds.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
    }

    private fun completeSafely(completion: (ItemPaymentOutcome) -> Unit, outcome: ItemPaymentOutcome) {
        runCatching { completion(outcome) }
            .onFailure { failure -> warnFailure(null, null, Phase.APPLY, failure) }
    }

    private fun warnFailure(operationId: UUID?, playerId: UUID?, phase: Phase, failure: Throwable) {
        warnOperation(operationId, playerId, phase, safeCause(failure))
    }

    private fun warnOperation(operationId: UUID?, playerId: UUID?, phase: Phase, cause: String) {
        runCatching {
            warning(
                "Item payment unresolved op=${operationId?.toString() ?: "none"} " +
                    "player=${playerId?.toString() ?: "none"} phase=${phase.name.lowercase()} " +
                    "cause=${cause.take(MAX_CAUSE_CHARS)}",
                null,
            )
        }
    }

    private fun snapshotRequest(request: ItemPaymentRequest): ItemPaymentRequest =
        request.copy(originalItemBytes = request.originalItemBytes.copyOf(), replacementItemBytes = request.replacementItemBytes.copyOf())

    private fun validateRequest(request: ItemPaymentRequest): ItemPaymentOutcome? =
        runCatching {
            require(request.slot in 0..MAX_SLOT)
            require(request.priceMinor > 0L)
            require(request.originalItemBytes.isNotEmpty() && request.originalItemBytes.size <= MAX_ITEM_BYTES)
            require(request.replacementItemBytes.isNotEmpty() && request.replacementItemBytes.size <= MAX_ITEM_BYTES)
            require(minorToMajor(request.priceMinor) != null)
        }.fold(
            onSuccess = { null },
            onFailure = { ItemPaymentOutcome.FAILED_UNCHARGED },
        )

    private fun minorToMajor(value: Long): Double? = (value.toDouble() / MINOR_PER_MAJOR).takeIf { it.isFinite() && it > 0.0 }

    private fun safeCause(failure: Throwable): String {
        val message = failure.message?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(MAX_CAUSE_CHARS).orEmpty()
        return listOf(failure::class.java.simpleName, message).filter(String::isNotBlank).joinToString(": ").ifBlank { "unknown" }
    }

    private companion object {
        const val MINOR_PER_MAJOR = 100.0
        const val MAX_ITEM_BYTES = 64 * 1024
        const val MAX_SLOT = 255
        const val MAX_UNRESOLVED_RECORDS = 512
        const val RECENT_OPERATION_LIMIT = 4_096
        const val MAX_CAUSE_CHARS = 160
        const val BALANCE_EPSILON = 0.000_001
    }
}
