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
    // transaction history. Until ARC owns an atomic payout journal, no config
    // value may unlock inventory or money mutations.
    private const val SUBMISSION_RUNTIME_READY = false

    private val configRef = AtomicReference<ContractsConfig>()
    private var repo: CachedRepository<ResourceContractRecord>? = null
    private var scope: CoroutineScope? = null

    @JvmStatic
    @Synchronized
    fun init() {
        if (repo != null) return
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
        try {
            ensureCurrentStates(loaded, newRepo)
            repo = newRepo
            scope = newScope
        } catch (failure: Throwable) {
            runBlocking { newRepo.shutdown() }
            newScope.cancel()
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
        if (currentRepo != null) ensureCurrentStates(loaded, currentRepo)
        publishMetrics()
    }

    @JvmStatic
    @Synchronized
    fun shutdown() {
        val currentRepo = repo
        val currentScope = scope
        repo = null
        scope = null
        try {
            if (currentRepo != null) runBlocking { currentRepo.shutdown() }
        } finally {
            currentScope?.cancel()
        }
    }

    @JvmStatic
    fun isAvailable(): Boolean = repo != null

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
            "orders" to views,
        )
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
