package ru.arc.contracts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ru.arc.ARC
import ru.arc.core.PluginModule
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.core.MetricPoint
import ru.arc.repository.CachedRepository
import ru.arc.repository.redisRepo
import ru.arc.util.Logging.info
import java.util.concurrent.atomic.AtomicReference
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
    val targetQuantity: Long,
    val acceptedQuantity: Long,
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
    // now halts ambiguous crash windows, but live inventory/payment orchestration
    // and operator reconciliation are still intentionally absent. No config
    // value may unlock inventory or money mutations.
    private const val SUBMISSION_RUNTIME_READY = false

    private val configRef = AtomicReference<ContractsConfig>()
    private var repo: CachedRepository<ResourceContractRecord>? = null
    private var scope: CoroutineScope? = null
    private var journalRepo: CachedRepository<ContractSubmissionJournalRecord>? = null
    private var journalScope: CoroutineScope? = null

    @JvmStatic
    @Synchronized
    fun init() {
        if (repo != null || journalRepo != null) return
        val loaded = ContractsConfig.load().validated()
        configRef.set(loaded)
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
            recoverInterruptedJournals(loaded, newJournalRepo)
            repo = newRepo
            scope = newScope
            journalRepo = newJournalRepo
            journalScope = newJournalScope
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
            journalRepo?.let { recoverInterruptedJournals(loaded, it) }
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
        repo = null
        scope = null
        journalRepo = null
        journalScope = null
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
    fun currentViews(now: Long = System.currentTimeMillis()): List<ResourceContractView> {
        val config = configRef.get() ?: return emptyList()
        val currentRepo = repo
        return config.resourceOrders().map { definition ->
            val record = currentRepo?.getNow(ResourceContractRecord.stateId(definition.id, definition.windowStartsAt))
                ?: ResourceContractRecord.empty(definition)
            val state = record.validatedAgainst(definition).state
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
                targetQuantity = definition.targetQuantity,
                acceptedQuantity = state.acceptedQuantity,
                remainingQuantity = (definition.targetQuantity - state.acceptedQuantity).coerceAtLeast(0L),
                contributors = state.perPlayerQuantity.size,
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
            "serverWeeklyBudgetMinor" to (config?.serverWeeklyBudgetMinor ?: 0L),
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
                serverWeeklyBudgetMinor = config?.serverWeeklyBudgetMinor ?: 0L,
                views = currentViews(),
                journal = journalSummary(),
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
        repository: CachedRepository<ContractSubmissionJournalRecord>,
        now: Long = System.currentTimeMillis(),
    ) {
        val records = repository.allNow().onEach { it.validated() }
        ContractSubmissionJournalAudit.summarize(records, now)
        if (!config.leaderServer.equals(ARC.serverName, ignoreCase = true)) return
        var changed = 0
        records.forEach { record ->
            val recovered = ContractSubmissionJournalEngine.recoverInterrupted(record, now)
            if (recovered != record) {
                repository.markDirty(recovered)
                changed += 1
            }
        }
        if (changed > 0) {
            runBlocking { repository.saveDirty().getOrThrow() }
            info("Contracts moved {} interrupted submission(s) to durable manual review", changed)
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
        serverWeeklyBudgetMinor: Long,
        views: List<ResourceContractView>,
        journal: ContractSubmissionJournalSummary,
    ): List<MetricPoint> =
        buildList {
            add(point("arc_contracts_enabled", "Whether the Economy V2 contracts policy is enabled", enabled))
            add(point("arc_contracts_available", "Whether the contracts state repository is available", available))
            add(point("arc_contracts_local_leader", "Whether this Paper node is the configured contracts mutation leader", localLeader))
            add(point("arc_contracts_submission_runtime_ready", "Whether the atomic inventory and payout runtime is implemented", submissionRuntimeReady))
            add(point("arc_contracts_submissions_enabled", "Whether contract submissions may mutate inventory and money", submissionsEnabled))
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
            views.forEach { view ->
                add(quantity(view, "target", view.targetQuantity))
                add(quantity(view, "accepted", view.acceptedQuantity))
                add(quantity(view, "remaining", view.remainingQuantity))
                add(money(view, "budget", view.budgetMinor))
                add(money(view, "spent", view.spentMinor))
                add(money(view, "remaining", (view.budgetMinor - view.spentMinor).coerceAtLeast(0L)))
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
