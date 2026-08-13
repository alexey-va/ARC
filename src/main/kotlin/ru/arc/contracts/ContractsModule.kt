package ru.arc.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.core.MetricPoint
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.util.Logging.info
import ru.arc.util.Logging.error
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CompletableFuture
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

object ContractsModule : PluginModule {
    override val name = "Contracts"
    override val priority = 87

    override fun init() {
        ContractsManager.init()
    }

    override fun reload() {
        ContractsManager.reload()
    }

    override fun shutdown() {
        ContractsManager.shutdown()
    }
}

data class ResourceContractView(
    val id: String,
    val displayName: String,
    val itemKey: String,
    val funding: String,
    val status: String,
    val windowStartsAt: Long,
    val windowEndsAt: Long,
    val payoutMinorPerUnit: Long,
    val budgetMinor: Long,
    val spentMinor: Long,
    val reservedMinor: Long,
    val targetQuantity: Long,
    val acceptedQuantity: Long,
    val reservedQuantity: Long,
    val remainingQuantity: Long,
    val contributors: Int,
)

/**
 * Runtime owner for the bounded contract catalog and network-persisted state.
 * Mutating submissions remain disabled unless policy mode is explicitly
 * `enforce`; the initial production config uses `observe` with no prices.
 */
object ContractsManager {
    // RedisEconomy 4.5.12 updates the balance before asynchronously recording
    // transaction history and exposes no idempotency key. The durable journal
    // now halts ambiguous crash windows and the disabled coordinator implements
    // durable-before-side-effect ordering. Bounded terminal retention and the
    // authenticated operator reconciliation state machine are implemented;
    // production crash injection and control-plane smoke are still required.
    // No config value may unlock inventory or money mutations before that gate.
    private const val SUBMISSION_RUNTIME_READY = false
    private const val SEASON_MUTATION_RUNTIME_READY = false

    private val configRef = AtomicReference<ContractsConfig>()
    private var repo: CachedRepository<ResourceContractRecord>? = null
    private var scope: CoroutineScope? = null
    private var journalRepo: CachedRepository<ContractSubmissionJournalRecord>? = null
    private var journalScope: CoroutineScope? = null
    private var submissionScope: CoroutineScope? = null
    private var submissionCoordinator: ContractSubmissionCoordinator? = null
    private var submissionMutex = Mutex()
    private val dungeonObserver = DungeonContractObserver()

    @JvmStatic
    @Synchronized
    fun init() {
        if (repo != null || journalRepo != null) return
        val loaded = ContractsConfig.load().validated()
        configRef.set(loaded)
        dungeonObserver.configure(loaded.observeSeasonCatalog())
        if (!loaded.enabled || loaded.mode == ContractsMode.DISABLED) {
            info("Contracts disabled by policy")
            publishMetrics()
            return
        }
        if (ARC.redisManager == null) {
            info("Contracts unavailable because Redis is disabled")
            publishMetrics()
            return
        }
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newRepo =
            try {
                redisRepo<ResourceContractRecord>(
                    id = "contracts",
                    storageKey = "arc.contracts.v1",
                    updateChannel = "arc.contracts.v1.update",
                    scope = newScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                newScope.cancel()
                throw failure
            }
        val newJournalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val newJournalRepo =
            try {
                redisRepo<ContractSubmissionJournalRecord>(
                    id = "contract-submission-journal",
                    storageKey = "arc.contract-submissions.v1",
                    updateChannel = "arc.contract-submissions.v1.update",
                    scope = newJournalScope,
                ) {
                    loadAllOnStart(true)
                    enableCleanup(false)
                    saveInterval(1.seconds)
                }
            } catch (failure: Throwable) {
                runBlocking { newRepo.shutdown() }
                newScope.cancel()
                newJournalScope.cancel()
                throw failure
            }
        try {
            ensureCurrentStates(loaded, newRepo)
            recoverInterruptedJournals(loaded, newRepo, newJournalRepo)
            val newSubmissionScope =
                if (SUBMISSION_RUNTIME_READY) CoroutineScope(Dispatchers.IO + SupervisorJob()) else null
            val newSubmissionCoordinator =
                if (SUBMISSION_RUNTIME_READY) {
                    ContractSubmissionCoordinator(
                        RedisContractSubmissionPersistence(newRepo, newJournalRepo),
                        PaperContractInventoryGateway(),
                        RedisEconomyContractPaymentGateway(),
                    )
                } else {
                    null
                }
            repo = newRepo
            scope = newScope
            journalRepo = newJournalRepo
            journalScope = newJournalScope
            submissionScope = newSubmissionScope
            submissionCoordinator = newSubmissionCoordinator
        } catch (failure: Throwable) {
            runBlocking {
                try {
                    newJournalRepo.shutdown()
                } finally {
                    newRepo.shutdown()
                }
            }
            newScope.cancel()
            newJournalScope.cancel()
            throw failure
        }
        info(
            "Contracts initialized in {} mode on {} with {} resource order(s)",
            loaded.mode.label,
            ARC.serverName ?: "unknown",
            loaded.resourceOrders().size,
        )
        publishMetrics()
    }

    @JvmStatic
    @Synchronized
    fun reload() {
        val loaded = ContractsConfig.load().validated()
        configRef.set(loaded)
        dungeonObserver.configure(loaded.observeSeasonCatalog())
        val currentRepo = repo
        if (currentRepo != null && (!loaded.enabled || loaded.mode == ContractsMode.DISABLED)) {
            shutdown()
            publishMetrics()
            return
        }
        if (currentRepo == null && loaded.enabled && loaded.mode != ContractsMode.DISABLED) {
            init()
            return
        }
        if (currentRepo != null) {
            ensureCurrentStates(loaded, currentRepo)
            journalRepo?.let { current ->
                ContractSubmissionJournalAudit.summarize(current.allNow().onEach { it.validated() }, System.currentTimeMillis())
            }
        }
        publishMetrics()
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        val currentRepo = repo
        val currentScope = scope
        val currentJournalRepo = journalRepo
        val currentJournalScope = journalScope
        val currentSubmissionScope = submissionScope
        repo = null
        scope = null
        journalRepo = null
        journalScope = null
        submissionScope = null
        submissionCoordinator = null
        submissionMutex = Mutex()
        dungeonObserver.configure(null)
        currentSubmissionScope?.cancel()
        try {
            runBlocking {
                try {
                    currentJournalRepo?.shutdown()
                } finally {
                    currentRepo?.shutdown()
                }
            }
        } finally {
            currentScope?.cancel()
            currentJournalScope?.cancel()
        }
    }

    @JvmStatic
    fun isAvailable(): Boolean = repo != null && journalRepo != null

    @JvmStatic
    fun mode(): ContractsMode = configRef.get()?.mode ?: ContractsMode.DISABLED

    @JvmStatic
    fun isLeader(): Boolean =
        configRef.get()?.leaderServer?.equals(ARC.serverName, ignoreCase = true) == true

    @JvmStatic
    fun submissionsEnabled(): Boolean =
        SUBMISSION_RUNTIME_READY && isAvailable() && isLeader() && mode() == ContractsMode.ENFORCE

    @JvmStatic
    fun observeDungeonStarted(
        runId: String,
        world: String,
        participantIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val recorded = dungeonObserver.started(runId, world, participantIds, now)
        if (recorded) publishMetrics()
        return recorded
    }

    @JvmStatic
    fun observeDungeonCompleted(
        runId: String,
        world: String,
        participantIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): DungeonContractCompletionObservation? {
        val observation = dungeonObserver.completed(runId, world, participantIds, now)
        if (observation != null) publishMetrics()
        return observation
    }

    @JvmStatic
    fun dungeonObservationSnapshot(now: Long = System.currentTimeMillis()): DungeonContractObservationSnapshot =
        dungeonObserver.snapshot(now)

    @JvmStatic
    fun submit(
        playerId: UUID,
        contractId: String,
        requestedQuantity: Int,
    ): CompletableFuture<ContractSubmissionOutcome> {
        val submissionId = "arc-${UUID.randomUUID()}"
        val result = CompletableFuture<ContractSubmissionOutcome>()
        val currentScope = submissionScope
        val coordinator = submissionCoordinator
        val currentMutex = submissionMutex
        val currentJournal = journalRepo
        if (!submissionsEnabled() || currentScope == null || coordinator == null) {
            return result.apply { complete(ContractSubmissionOutcome.Unavailable(submissionId)) }
        }
        val definition = configRef.get()?.resourceOrders()?.firstOrNull { it.id == contractId }
            ?: return result.apply {
                complete(ContractSubmissionOutcome.Rejected(SubmissionRejection.INVALID_REQUEST))
            }
        currentScope.launch {
            try {
                val outcome =
                    currentMutex.withLock {
                        val submitted =
                            if (!submissionsEnabled()) {
                                ContractSubmissionOutcome.Unavailable(submissionId)
                            } else {
                                coordinator.submit(
                                    definition,
                                    submissionId,
                                    playerId.toString(),
                                    requestedQuantity,
                                )
                            }
                        if (currentJournal != null) {
                            try {
                                pruneTerminalJournals(currentJournal, System.currentTimeMillis())
                            } catch (failure: Throwable) {
                                error("Contract journal retention failed after submission", failure)
                            }
                        }
                        submitted
                    }
                result.complete(outcome)
            } catch (failure: Throwable) {
                error("Contract submission {} stopped unexpectedly", submissionId, failure)
                result.complete(ContractSubmissionOutcome.ManualReview(submissionId))
            } finally {
                publishMetrics()
            }
        }
        return result
    }

    @JvmStatic
    fun currentViews(now: Long = System.currentTimeMillis()): List<ResourceContractView> {
        val config = configRef.get() ?: return emptyList()
        val currentRepo = repo
        return config.resourceOrders().map { definition ->
            val record = currentRepo?.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt))
                ?: ResourceContractRecord.empty(definition)
            val state = record.validatedAgainst(definition).state
            val reservations = activeReservations(definition, state)
            val reservedQuantity = reservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.quantity) }
            val reservedMinor = reservations.fold(0L) { total, reservation -> Math.addExact(total, reservation.payoutMinor) }
            val effectiveStatus =
                when {
                    now >= definition.windowEndsAt -> ContractStatus.EXPIRED
                    !definition.isOpenAt(now) && state.status == ContractStatus.OPEN -> ContractStatus.PAUSED
                    else -> state.status
                }
            ResourceContractView(
                id = definition.id,
                displayName = definition.displayName,
                itemKey = definition.itemKey,
                funding = definition.funding.label,
                status = effectiveStatus.label,
                windowStartsAt = definition.windowStartsAt,
                windowEndsAt = definition.windowEndsAt,
                payoutMinorPerUnit = definition.payoutMinorPerUnit,
                budgetMinor = definition.budgetMinor,
                spentMinor = state.spentMinor,
                reservedMinor = reservedMinor,
                targetQuantity = definition.targetQuantity,
                acceptedQuantity = state.acceptedQuantity,
                reservedQuantity = reservedQuantity,
                remainingQuantity =
                    (definition.targetQuantity - state.acceptedQuantity - reservedQuantity).coerceAtLeast(0L),
                contributors = (state.perPlayerQuantity.keys + reservations.map { it.playerId }).size,
            )
        }
    }

    @JvmStatic
    fun summary(): Map<String, Any?> {
        val config = configRef.get()
        val views = currentViews()
        return linkedMapOf(
            "enabled" to (config?.enabled ?: false),
            "mode" to (config?.mode?.label ?: ContractsMode.DISABLED.label),
            "leaderServer" to (config?.leaderServer ?: "spawn"),
            "localServer" to (ARC.serverName ?: "unknown"),
            "localLeader" to isLeader(),
            "submissionRuntimeReady" to SUBMISSION_RUNTIME_READY,
            "submissionsEnabled" to submissionsEnabled(),
            "seasonMutationRuntimeReady" to SEASON_MUTATION_RUNTIME_READY,
            "serverWeeklyBudgetMinor" to (config?.serverWeeklyBudgetMinor ?: 0L),
            "seasonCatalog" to config?.observeSeasonCatalog()?.summary(),
            "dungeonObservation" to dungeonObservationSnapshot(),
            "submissionJournal" to journalSummary(),
            "orders" to views,
        )
    }

    @JvmStatic
    fun journalSummary(now: Long = System.currentTimeMillis()): ContractSubmissionJournalSummary {
        val current = journalRepo ?: return ContractSubmissionJournalSummary.unavailable()
        return ContractSubmissionJournalAudit.summarize(current.allNow(), now)
    }

    @JvmStatic
    fun reconciliationRecords(limit: Int = 20): List<ContractSubmissionJournalRecord> {
        require(limit in 1..100) { "Reconciliation limit must be 1..100" }
        val current = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return current.allNow().asSequence()
            .map { it.validated() }
            .filter { it.status == ContractSubmissionJournalStatus.MANUAL_REVIEW || it.reconciliation != null }
            .sortedWith(compareByDescending<ContractSubmissionJournalRecord> { it.updatedAt }.thenBy { it.submissionId })
            .take(limit)
            .toList()
    }

    @JvmStatic
    fun reconciliationRecord(submissionId: String): ContractSubmissionJournalRecord? {
        val current = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return current.getNow(submissionId)?.validated()
    }

    @JvmStatic
    @Synchronized
    fun previewReconciliation(
        request: ContractSubmissionReconciliationRequest,
    ): ContractSubmissionReconciliationPreview {
        require(isLeader()) { "Contract reconciliation is available only on the configured leader" }
        val currentJournal = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        requireUniqueReconciliationKey(currentJournal, request)
        val record = requireNotNull(currentJournal.getNow(request.submissionId)) { "Contract submission not found" }.validated()
        val preview = ContractSubmissionReconciliationEngine.preview(record, request)
        verifyReconciledPaymentCommit(record, preview)
        return preview
    }

    @JvmStatic
    @Synchronized
    fun applyReconciliation(
        request: ContractSubmissionReconciliationRequest,
        reviewDigest: String,
        now: Long = System.currentTimeMillis(),
    ): ContractSubmissionReconciliationApplyResult {
        require(isLeader()) { "Contract reconciliation is available only on the configured leader" }
        val currentContract = repo ?: throw IllegalStateException("Contract state repository is unavailable")
        val currentJournal = journalRepo ?: throw IllegalStateException("Contract submission journal is unavailable")
        return runBlocking {
            submissionMutex.withLock {
                val before = requireNotNull(currentJournal.getNow(request.submissionId)) {
                    "Contract submission not found"
                }.validated()
                requireUniqueReconciliationKey(currentJournal, request)
                val preview = ContractSubmissionReconciliationEngine.preview(before, request)
                verifyReconciledPaymentCommit(before, preview)
                var resolved = ContractSubmissionReconciliationEngine.apply(before, request, reviewDigest, now)
                val replayed = before.reconciliation != null
                if (resolved != before) {
                    currentJournal.markDirty(resolved)
                    currentJournal.saveDirty().getOrThrow()
                }

                var receipt: ContractSubmissionReceipt? = null
                if (resolved.status == ContractSubmissionJournalStatus.PAID) {
                    val definition = reconciliationDefinition(resolved)
                    val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
                    val state = requireNotNull(currentContract.getNow(stateId)) {
                        "Missing state for reconciled paid contract ${definition.id}"
                    }.validatedAgainst(definition).state
                    val recovery = ContractSubmissionRecoveryEngine.recoverPaid(definition, state, resolved, now)
                    if (recovery.commit.changed) {
                        currentContract.markDirty(ResourceContractRecord(stateId, recovery.commit.state))
                        currentContract.saveDirty().getOrThrow()
                    }
                    resolved = recovery.journal
                    currentJournal.markDirty(resolved)
                    currentJournal.saveDirty().getOrThrow()
                    receipt = recovery.commit.receipt
                }

                try {
                    pruneTerminalJournals(currentJournal, now)
                } catch (failure: Throwable) {
                    error("Contract journal retention failed after reconciliation", failure)
                }
                if (!replayed) {
                    info(
                        "Contract submission {} reconciled as {} by authenticated operator {}",
                        resolved.submissionId,
                        requireNotNull(resolved.reconciliation).resolution.label,
                        resolved.reconciliation.operatorId,
                    )
                }
                publishMetrics()
                ContractSubmissionReconciliationApplyResult(preview, resolved, receipt, replayed)
            }
        }
    }

    @JvmStatic
    fun publishMetrics() {
        val config = configRef.get()
        MetricsModule.recordSnapshot("contracts", "economy-contracts") {
            ContractsMetrics.points(
                enabled = config?.enabled == true,
                available = isAvailable(),
                mode = config?.mode ?: ContractsMode.DISABLED,
                localLeader = isLeader(),
                submissionRuntimeReady = SUBMISSION_RUNTIME_READY,
                submissionsEnabled = submissionsEnabled(),
                seasonMutationRuntimeReady = SEASON_MUTATION_RUNTIME_READY,
                serverWeeklyBudgetMinor = config?.serverWeeklyBudgetMinor ?: 0L,
                views = currentViews(),
                journal = journalSummary(),
                dungeonObservation = dungeonObservationSnapshot(),
            )
        }
    }

    private fun ensureCurrentStates(
        config: ContractsConfig,
        repository: CachedRepository<ResourceContractRecord>,
    ) {
        val definitions = config.resourceOrders()
        definitions.forEach { definition ->
            val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
            val existing = repository.getNow(stateId)
            existing?.validatedAgainst(definition)
        }
        definitions.forEach { definition ->
            val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
            if (repository.getNow(stateId) == null) repository.markDirty(ResourceContractRecord.empty(definition))
        }
    }

    private fun recoverInterruptedJournals(
        config: ContractsConfig,
        contractRepository: CachedRepository<ResourceContractRecord>,
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        now: Long = System.currentTimeMillis(),
    ) {
        val records = journalRepository.allNow().onEach { it.validated() }
        ContractSubmissionJournalAudit.summarize(records, now)
        if (!config.leaderServer.equals(ARC.serverName, ignoreCase = true)) return
        var changed = 0
        records.forEach { record ->
            val recovered =
                if (record.status == ContractSubmissionJournalStatus.PREPARED) {
                    ContractSubmissionJournalEngine.cancelPrepared(record, "restart_before_item_removal", now)
                } else {
                    ContractSubmissionJournalEngine.recoverInterrupted(record, now)
                }
            if (recovered != record) {
                journalRepository.markDirty(recovered)
                changed += 1
            }
        }
        if (changed > 0) {
            runBlocking { journalRepository.saveDirty().getOrThrow() }
            info("Contracts safely recovered {} pre-mutation or interrupted submission(s)", changed)
        }

        records.filter { it.status == ContractSubmissionJournalStatus.PAID }.forEach { paid ->
            val definition =
                config.resourceOrders().firstOrNull {
                    it.id == paid.contractId && it.windowStartsAt == paid.contractWindowStartsAt
                } ?: return@forEach
            val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
            val current = requireNotNull(contractRepository.getNow(stateId)) { "Missing state for paid contract ${definition.id}" }
            val recovery =
                ContractSubmissionRecoveryEngine.recoverPaid(
                    definition,
                    current.validatedAgainst(definition).state,
                    paid,
                    now,
                )
            if (recovery.commit.changed) {
                contractRepository.markDirty(ResourceContractRecord(stateId, recovery.commit.state))
                runBlocking { contractRepository.saveDirty().getOrThrow() }
            }
            journalRepository.markDirty(recovery.journal)
            runBlocking { journalRepository.saveDirty().getOrThrow() }
        }
        runBlocking { pruneTerminalJournals(journalRepository, now) }
    }

    private fun verifyReconciledPaymentCommit(
        record: ContractSubmissionJournalRecord,
        preview: ContractSubmissionReconciliationPreview,
    ) {
        if (!preview.commitsContractState || record.status == ContractSubmissionJournalStatus.CONTRACT_COMMITTED) return
        val definition = reconciliationDefinition(record)
        val currentContract = repo ?: throw IllegalStateException("Contract state repository is unavailable")
        val stateId = ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)
        val state = requireNotNull(currentContract.getNow(stateId)) {
            "Missing state for reconciled paid contract ${definition.id}"
        }.validatedAgainst(definition).state
        ResourceContractEngine.commitReserved(
            definition,
            state,
            requireNotNull(record.quotaReservation()),
            System.currentTimeMillis(),
        )
    }

    private fun reconciliationDefinition(record: ContractSubmissionJournalRecord): ResourceContractDefinition =
        requireNotNull(
            configRef.get()?.resourceOrders()?.firstOrNull {
                it.id == record.contractId && it.windowStartsAt == record.contractWindowStartsAt
            },
        ) { "Configured contract policy for submission ${record.submissionId} is unavailable" }

    private fun requireUniqueReconciliationKey(
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        request: ContractSubmissionReconciliationRequest,
    ) {
        val reused =
            journalRepository.allNow().asSequence()
                .map { it.validated() }
                .firstOrNull {
                    it.submissionId != request.submissionId &&
                        it.reconciliation?.idempotencyKey == request.idempotencyKey
                }
        require(reused == null) { "Reconciliation idempotency key is already bound to another submission" }
    }

    private suspend fun pruneTerminalJournals(
        journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
        now: Long,
    ): ContractSubmissionRetentionPlan {
        val plan = ContractSubmissionRetentionPolicy.plan(journalRepository.allNow(), now)
        plan.deleteSubmissionIds.forEach { submissionId ->
            journalRepository.deleteDurably(submissionId).getOrThrow()
        }
        if (plan.deleteSubmissionIds.isNotEmpty()) {
            info(
                "Contracts retained {} journal record(s) after deleting {} terminal record(s)",
                plan.totalAfter,
                plan.deleteSubmissionIds.size,
            )
        }
        return plan
    }

    private fun activeReservations(
        definition: ResourceContractDefinition,
        state: ResourceContractState,
    ): List<ContractQuotaReservation> =
        journalRepo?.allNow().orEmpty().asSequence()
            .filter { it.contractId == definition.id && it.contractWindowStartsAt == definition.windowStartsAt }
            .map { it.validated() }
            .mapNotNull { it.quotaReservation() }
            .onEach { reservation ->
                state.recentReceipts[reservation.submissionId]?.let { receipt ->
                    require(
                        receipt.playerId == reservation.playerId &&
                            receipt.quantity == reservation.quantity &&
                            receipt.payoutMinor == reservation.payoutMinor,
                    ) { "Contract reservation disagrees with its committed receipt" }
                }
            }
            .filterNot { it.submissionId in state.recentReceipts }
            .toList()

    private class RedisContractSubmissionPersistence(
        private val contractRepository: CachedRepository<ResourceContractRecord>,
        private val journalRepository: CachedRepository<ContractSubmissionJournalRecord>,
    ) : ContractSubmissionPersistence {
        override fun contractState(definition: ResourceContractDefinition): ResourceContractState =
            requireNotNull(
                contractRepository.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt)),
            ) { "Contract state is not loaded" }
                .validatedAgainst(definition)
                .state

        override fun journalRecords(): List<ContractSubmissionJournalRecord> = journalRepository.allNow()

        override suspend fun persistJournal(record: ContractSubmissionJournalRecord) {
            journalRepository.markDirty(record.validated())
            journalRepository.saveDirty().getOrThrow()
        }

        override suspend fun persistContract(
            definition: ResourceContractDefinition,
            state: ResourceContractState,
        ) {
            state.validatedAgainst(definition)
            contractRepository.markDirty(
                ResourceContractRecord(
                    ResourceContractRecord.stateId(definition.id, definition.windowStartsAt),
                    state,
                ),
            )
            contractRepository.saveDirty().getOrThrow()
        }
    }
}

object ContractsMetrics {
    fun points(
        enabled: Boolean,
        available: Boolean,
        mode: ContractsMode,
        localLeader: Boolean,
        submissionRuntimeReady: Boolean,
        submissionsEnabled: Boolean,
        seasonMutationRuntimeReady: Boolean,
        serverWeeklyBudgetMinor: Long,
        views: List<ResourceContractView>,
        journal: ContractSubmissionJournalSummary,
        dungeonObservation: DungeonContractObservationSnapshot,
    ): List<MetricPoint> =
        buildList {
            add(point("arc_contracts_enabled", "Whether the Economy V2 contracts policy is enabled", enabled))
            add(point("arc_contracts_available", "Whether the contracts state repository is available", available))
            add(point("arc_contracts_local_leader", "Whether this Paper node is the configured contracts mutation leader", localLeader))
            add(point("arc_contracts_submission_runtime_ready", "Whether the atomic inventory and payout runtime is implemented", submissionRuntimeReady))
            add(point("arc_contracts_submissions_enabled", "Whether contract submissions may mutate inventory and money", submissionsEnabled))
            add(
                point(
                    "arc_season_mutation_runtime_ready",
                    "Whether season project and dungeon money or item mutations are enabled",
                    seasonMutationRuntimeReady,
                ),
            )
            add(
                MetricPoint(
                    "arc_contracts_mode",
                    "Current bounded Economy V2 contracts mode",
                    1.0,
                    mapOf("mode" to mode.label),
                ),
            )
            add(
                MetricPoint(
                    "arc_contracts_server_weekly_budget_currency",
                    "Configured weekly server-funded contracts envelope",
                    serverWeeklyBudgetMinor / 100.0,
                ),
            )
            add(point("arc_contract_journal_available", "Whether the durable contract submission journal is available", journal.available))
            add(
                MetricPoint(
                    "arc_contract_journal_records",
                    "Durable contract submission journal records by bounded state",
                    journal.totalRecords.toDouble(),
                    mapOf("state" to "all"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_capacity_remaining",
                    "Remaining bounded network record capacity before contract submissions must fail closed",
                    journal.capacityRemaining.toDouble(),
                ),
            )
            journal.stateCounts.forEach { (state, count) ->
                add(
                    MetricPoint(
                        "arc_contract_journal_records",
                        "Durable contract submission journal records by bounded state",
                        count.toDouble(),
                        mapOf("state" to state),
                    ),
                )
            }
            add(
                MetricPoint(
                    "arc_contract_journal_held_item_quantity",
                    "Item quantity confirmed removed and not yet committed or refunded",
                    journal.heldItemQuantity.toDouble(),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_payout_currency",
                    "Contract payout exposure split into pending and ambiguous provider outcomes",
                    journal.pendingPayoutMinor / 100.0,
                    mapOf("component" to "pending"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_payout_currency",
                    "Contract payout exposure split into pending and ambiguous provider outcomes",
                    journal.ambiguousPayoutMinor / 100.0,
                    mapOf("component" to "ambiguous"),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_manual_review",
                    "Contract submissions halted for manual reconciliation",
                    journal.manualReviewCount.toDouble(),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_journal_oldest_attention_age_seconds",
                    "Age of the oldest non-terminal contract submission journal record",
                    journal.oldestAttentionAgeSeconds.toDouble(),
                ),
            )
            add(
                point(
                    "arc_dungeon_contract_catalog_available",
                    "Whether a validated observe-only dungeon contract catalog is loaded",
                    dungeonObservation.catalogAvailable,
                ),
            )
            dungeonObservation.statsByContract.forEach { (contractId, observation) ->
                add(
                    MetricPoint(
                        "arc_dungeon_contract_active_runs",
                        "Native EliteMobs dungeon runs currently observed by configured contract",
                        (dungeonObservation.activeRunsByContract[contractId] ?: 0).toDouble(),
                        mapOf("contract" to contractId),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_runs_total",
                        "Native EliteMobs dungeon run events observed by configured contract",
                        observation.startedRuns.toDouble(),
                        mapOf("contract" to contractId, "event" to "started"),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_runs_total",
                        "Native EliteMobs dungeon run events observed by configured contract",
                        observation.nativeCompletedRuns.toDouble(),
                        mapOf("contract" to contractId, "event" to "native_completed"),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_dungeon_contract_completion_duration_seconds_total",
                        "Total duration of native completions whose configured dungeon start was observed",
                        observation.nativeCompletionDurationSeconds.toDouble(),
                        mapOf("contract" to contractId),
                    ),
                )
                DungeonCompletionPlayerOutcome.entries.forEach { outcome ->
                    add(
                        MetricPoint(
                            "arc_dungeon_contract_completion_players_total",
                            "Players associated with native dungeon runs grouped by bounded completion outcome",
                            (observation.playerOutcomes[outcome] ?: 0L).toDouble(),
                            mapOf("contract" to contractId, "outcome" to outcome.label),
                        ),
                    )
                }
            }
            views.forEach { view ->
                add(quantity(view, "target", view.targetQuantity))
                add(quantity(view, "accepted", view.acceptedQuantity))
                add(quantity(view, "reserved", view.reservedQuantity))
                add(quantity(view, "remaining", view.remainingQuantity))
                add(money(view, "budget", view.budgetMinor))
                add(money(view, "spent", view.spentMinor))
                add(money(view, "reserved", view.reservedMinor))
                add(money(view, "remaining", (view.budgetMinor - view.spentMinor - view.reservedMinor).coerceAtLeast(0L)))
                add(
                    MetricPoint(
                        "arc_contract_contributors",
                        "Distinct contributors recorded inside a configured contract window",
                        view.contributors.toDouble(),
                        mapOf("contract" to view.id),
                    ),
                )
                add(
                    MetricPoint(
                        "arc_contract_status",
                        "Current bounded status of a configured contract window",
                        1.0,
                        mapOf("contract" to view.id, "status" to view.status),
                    ),
                )
                add(window(view, "start", view.windowStartsAt))
                add(window(view, "end", view.windowEndsAt))
            }
        }

    private fun point(name: String, description: String, value: Boolean): MetricPoint =
        MetricPoint(name, description, if (value) 1.0 else 0.0)

    private fun quantity(view: ResourceContractView, component: String, value: Long): MetricPoint =
        MetricPoint(
            "arc_contract_quantity",
            "Configured, accepted and remaining item quantity by bounded contract",
            value.toDouble(),
            mapOf("contract" to view.id, "component" to component),
        )

    private fun money(view: ResourceContractView, component: String, valueMinor: Long): MetricPoint =
        MetricPoint(
            "arc_contract_money_currency",
            "Configured, spent and remaining contract money by bounded contract",
            valueMinor / 100.0,
            mapOf("contract" to view.id, "component" to component),
        )

    private fun window(view: ResourceContractView, boundary: String, value: Long): MetricPoint =
        MetricPoint(
            "arc_contract_window_timestamp_seconds",
            "Contract window boundary as a Unix timestamp",
            value / 1_000.0,
            mapOf("contract" to view.id, "boundary" to boundary),
        )
}
