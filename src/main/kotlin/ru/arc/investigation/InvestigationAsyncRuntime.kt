package ru.arc.investigation

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** All journal/domain work belongs to one worker; native calls retain that accepted worker operation. */
internal class InvestigationAsyncRuntime(
    val tasks: LifecycleTaskScope,
    private val worker: BoundedAsyncExecutor,
    private val load: (Set<String>) -> InvestigationRuntimeConfig,
    private val journalFactory: () -> InvestigationJournal,
    private val wallet: InvestigationWallet,
    private val currentGeneration: () -> Boolean,
    private val canWithdraw: (UUID) -> Boolean,
    private val onReady: (InvestigationRuntimeConfig) -> Unit,
    private val onFailure: (UUID, Throwable) -> Unit,
    private val onManualReview: (InvestigationJournalRecord) -> Unit,
    private val onBackgroundFailure: (Throwable) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class View(
        val current: Map<UUID, InvestigationJournalRecord> = emptyMap(),
        val latest: Map<UUID, InvestigationJournalRecord> = emptyMap(),
    )

    private val ready = CompletableFuture<Void>()
    private val terminal = CompletableFuture<Unit>()
    private val pending = ConcurrentHashMap<UUID, Any>()
    private val expiring = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var reloading = false
    @Volatile private var view = View()
    private var journal: InvestigationJournal? = null // Worker-owned below.
    private var service: InvestigationService? = null
    private var policy: InvestigationRuntimeConfig? = null
    private var debitIdentity: (() -> Boolean)? = null // Only this serial worker assigns an operation's native guard.
    private val token = tasks.token()
    private var startupThread: Thread? = null

    val available: Boolean get() = !closed && ready.isDone && !ready.isCompletedExceptionally && policy?.config?.enabled == true
    fun readiness(): CompletableFuture<Void> = ready.copy()
    fun drained(): CompletableFuture<Unit> = terminal.copy()

    /** Startup recovery is maintenance; gameplay continues on the serial worker afterwards. */
    fun start(prepared: InvestigationRuntimeConfig? = null) {
        startupThread = Thread.currentThread()
        try {
            requireCurrent()
            val loaded = prepared ?: load(emptySet())
            policy = loaded
            if (loaded.config.enabled) {
                val source = journalFactory()
                val unresolvedWitnesses = source.records().filterNot { it.status.resolved }
                    .flatMap { it.case.witnesses() }.map(InvestigationWitness::commandValue).toSet()
                loaded.config.validated(requireNotNull(loaded.catalog).witnessKeys + unresolvedWitnesses)
                val indexed = PublishingJournal(source)
                journal = indexed
                val bridge = NativeWallet(wallet)
                val domain = InvestigationService(indexed, bridge,
                    enabled = { loaded.config.enabled }, feeMinor = { loaded.config.feeMinor },
                    rewardMinor = { loaded.config.rewardMinor }, duration = { loaded.config.duration },
                    cooldown = { loaded.config.cooldown },
                    caseGenerator = InvestigationCaseGenerator(requireNotNull(loaded.catalog)),
                    // History is joined on this worker before returning a completed future below.
                    runSync = { it() }, clock = clock,
                    random = kotlin.random.Random(java.security.SecureRandom().nextLong()))
                service = domain
                domain.recover(onManualReview)
            }
            onReady(loaded)
        } catch (failure: Throwable) {
            ready.completeExceptionally(failure)
            throw failure
        } finally { startupThread = null }
        ready.complete(null)
    }

    /** Called only after close and actual worker drain, so no journal access races a writer. */
    fun maintenanceConfiguration(): InvestigationRuntimeConfig {
        check(closed && terminal.isDone) { "Investigation writer must drain before maintenance" }
        val witnesses = journal?.records().orEmpty().filterNot { it.status.resolved }
            .flatMap { it.case.witnesses() }.map(InvestigationWitness::commandValue).toSet()
        return load(witnesses)
    }

    fun currentConfiguration(): InvestigationRuntimeConfig = checkNotNull(policy)


    /** These getters never enter a journal, provider or IO-held monitor. */
    fun current(player: UUID): InvestigationJournalRecord? = view.current[player].also {
        if (it?.status == InvestigationStatus.ACTIVE && (it.expiresAt ?: Long.MAX_VALUE) <= clock()) requestExpiry()
    }
    fun latest(player: UUID): InvestigationJournalRecord? = view.latest[player]

    fun prepareReload(): CompletableFuture<InvestigationRuntimeConfig> {
        if (reloading) return CompletableFuture.failedFuture(IllegalStateException("Investigation reload is already preparing"))
        reloading = true
        return worker.submit {
            requireCurrent()
            val witnesses = journal?.records().orEmpty().filterNot { it.status.resolved }
                .flatMap { it.case.witnesses() }.map(InvestigationWitness::commandValue).toSet()
            load(witnesses)
        }
    }
    fun rejectReload() { reloading = false }

    fun startCase(player: UUID, bypassCooldown: Boolean, isCurrentPlayer: () -> Boolean = { true },
        result: (InvestigationStartResult) -> Unit) =
        operate(player, InvestigationStartResult.Busy, result) {
            debitIdentity = isCurrentPlayer
            try { start(player, bypassCooldown) } finally { debitIdentity = null }
        }
    fun collect(player: UUID, witness: InvestigationWitness, result: (InvestigationClueResult) -> Unit) =
        operate(player, InvestigationClueResult.Busy, result) { collectClue(player, witness) }
    fun submit(player: UUID, verdict: InvestigationVerdict, result: (InvestigationVerdictResult) -> Unit) =
        operate(player, InvestigationVerdictResult.Busy, result) { submitVerdict(player, verdict) }

    fun requestExpiry() {
        if (!available || reloading || !expiring.compareAndSet(false, true)) return
        worker.submit {
            requireCurrent()
            service?.expireAll()
        }.whenComplete { _, failure ->
            expiring.set(false)
            if (failure != null && !closed) onBackgroundFailure(failure)
        }
    }

    private fun <T> operate(player: UUID, busy: T, consume: (T) -> Unit, operation: InvestigationService.() -> T): CompletableFuture<T> {
        if (!available) return CompletableFuture.failedFuture(CancellationException("Investigations are not ready"))
        val hold = Any()
        if (reloading || pending.putIfAbsent(player, hold) != null) { consume(busy); return CompletableFuture.completedFuture(busy) }
        val submitted = worker.submit {
            try {
                requireCurrent()
                val result = requireNotNull(service).operation()
                // Keep the per-player hold and worker drain until native resumption actually finishes.
                onMain { consume(result) }
                result
            } catch (failure: Throwable) {
                runCatching { onMain { onFailure(player, failure) } }
                throw failure
            } finally { pending.remove(player, hold) }
        }
        if (submitted.isCompletedExceptionally && pending.remove(player, hold)) {
            consume(busy) // Bounded worker rejection; no operation or money mutation was accepted.
            return CompletableFuture.completedFuture(busy)
        }
        // Rejected work never entered its finally block.
        submitted.whenComplete { _, _ -> pending.remove(player, hold) }
        return submitted
    }

    private fun requireCurrent() {
        if (closed || !currentGeneration()) throw CancellationException("Investigation generation is no longer current")
    }

    private fun <T> onMain(operation: () -> T): T {
        if (startupThread === Thread.currentThread()) {
            requireCurrent()
            return operation()
        }
        return tasks.callSync(token) { requireCurrent(); operation() }.join() // Gameplay worker only.
    }

    private inner class NativeWallet(private val delegate: InvestigationWallet) : InvestigationWallet {
        override val available: Boolean get() = onMain { delegate.available }
        override fun balanceMinor(playerId: UUID): Long? = onMain { delegate.balanceMinor(playerId) }
        override fun withdraw(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence {
            val identity = debitIdentity
            return onMain {
                check(identity?.invoke() != false && canWithdraw(playerId)) { "Investigation player left the admission point before debit" }
                delegate.withdraw(playerId, amountMinor, reason, expectedBalanceBeforeMinor)
            }
        }
        override fun deposit(playerId: UUID, amountMinor: Long, reason: String, expectedBalanceBeforeMinor: Long): InvestigationMoneyEvidence = onMain {
            delegate.deposit(playerId, amountMinor, reason, expectedBalanceBeforeMinor)
        }
        override fun findTransaction(playerId: UUID, amountMinor: Long, reason: String, notBeforeMillis: Long): CompletableFuture<InvestigationProviderEvidence> {
            val query = if (delegate is RedisEconomyInvestigationWallet) {
                onMain { delegate.prepareHistory(playerId, amountMinor, reason, notBeforeMillis) }
            } else { { delegate.findTransaction(playerId, amountMinor, reason, notBeforeMillis) } }
            // Calling the API here also keeps already-completed provider sorting/matching off Paper.
            val evidence = query().handle { value, failure ->
                if (failure == null) value ?: InvestigationProviderEvidence(null, false) else InvestigationProviderEvidence(null, false)
            }.join()
            return CompletableFuture.completedFuture(evidence)
        }
    }

    private inner class PublishingJournal(private val delegate: InvestigationJournal) : InvestigationJournal {
        init { publish() }
        override fun records(): List<InvestigationJournalRecord> = delegate.records()
        override fun persist(record: InvestigationJournalRecord): Boolean = delegate.persist(record).also { if (it) publish() }
        override fun latest(playerId: UUID): InvestigationJournalRecord? = view.latest[playerId]
        override fun open(playerId: UUID): InvestigationJournalRecord? = view.current[playerId]
        private fun publish() {
            val latest = mutableMapOf<UUID, InvestigationJournalRecord>()
            val open = mutableMapOf<UUID, InvestigationJournalRecord>()
            for (record in delegate.records()) {
                val player = UUID.fromString(record.playerId)
                if ((latest[player]?.createdAt ?: Long.MIN_VALUE) <= record.createdAt) latest[player] = record
                if (!record.status.resolved && (open[player]?.createdAt ?: Long.MIN_VALUE) <= record.createdAt) open[player] = record
            }
            view = View(open.toMap(), latest.toMap())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        ready.completeExceptionally(CancellationException("Investigation generation closed"))
        // Cancellation errors must not prevent sealing/draining accepted journal work.
        val cancellationFailure = runCatching { tasks.close() }.exceptionOrNull()
        worker.closeAsync().whenComplete { _, failure ->
            val cause = failure ?: cancellationFailure
            if (cause == null) terminal.complete(Unit) else terminal.completeExceptionally(cause)
        }
    }
}
