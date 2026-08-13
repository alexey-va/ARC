package ru.arc.contracts

import ru.arc.ARC
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Instant

enum class ContractsMode(val label: String) {
    DISABLED("disabled"),
    OBSERVE("observe"),
    ENFORCE("enforce"),
}

open class ContractsConfig(
    private val config: Config,
) {
    open val enabled: Boolean get() = config.bool("enabled", false)

    open val mode: ContractsMode get() = strictEnum("mode", ContractsMode.DISABLED)

    open val leaderServer: String get() = config.string("leader-server", "spawn").trim().lowercase()

    open val serverWeeklyBudgetMinor: Long
        get() = moneyMinor(config.string("server-weekly-budget", "0"), "server-weekly-budget", allowZero = true)

    open fun validated(): ContractsConfig {
        enabled
        mode
        require(SERVER_ID_PATTERN.matches(leaderServer)) { "Invalid contracts leader-server: $leaderServer" }
        serverWeeklyBudgetMinor
        resourceOrders()
        return this
    }

    open fun resourceOrders(): List<ResourceContractDefinition> {
        val orderIds = config.keys("orders").sorted()
        require(orderIds.size <= MAX_CONFIGURED_ORDERS) { "At most $MAX_CONFIGURED_ORDERS contracts may be configured" }
        val definitions =
            orderIds.mapNotNull { id ->
                val normalizedId = id.trim().lowercase()
                require(normalizedId == id) {
                    "Contract id '$id' must already be normalized lowercase ASCII"
                }
                val root = "orders.$id"
                if (!config.bool("$root.enabled", false)) return@mapNotNull null
                val kind = strictEnum("$root.kind", ContractKind.RESOURCE)
                require(kind == ContractKind.RESOURCE) {
                    "Contract '$id' uses unsupported runtime kind '${kind.label}'"
                }
                val startsAt = instant(config.string("$root.window-starts-at", ""), "$root.window-starts-at")
                val endsAt = instant(config.string("$root.window-ends-at", ""), "$root.window-ends-at")
                ResourceContractDefinition(
                    id = normalizedId,
                    displayName = config.string("$root.display-name", id).trim(),
                    itemKey = ResourceContractDefinition.normalizeItemKey(config.string("$root.item", "")),
                    funding = strictEnum("$root.funding", ContractFunding.SERVER_ENVELOPE),
                    windowStartsAt = startsAt,
                    windowEndsAt = endsAt,
                    payoutMinorPerUnit = moneyMinor(config.string("$root.payout-per-unit", ""), "$root.payout-per-unit"),
                    budgetMinor = moneyMinor(config.string("$root.budget", ""), "$root.budget"),
                    targetQuantity = config.long("$root.target-quantity", 0L),
                    perPlayerQuantityCap = config.long("$root.per-player-quantity-cap", 0L),
                    minSubmissionQuantity = config.integer("$root.min-submission-quantity", 1),
                    maxSubmissionQuantity = config.integer("$root.max-submission-quantity", 2_304),
                    kind = kind,
                ).also { definition ->
                    require(definition.perPlayerQuantityCap <= definition.targetQuantity) {
                        "Contract '$id' per-player cap exceeds target quantity"
                    }
                    require(definition.funding == ContractFunding.SERVER_ENVELOPE) {
                        "Resource contract '$id' must use server_envelope until player escrow runtime is enabled"
                    }
                }
            }
        val totalBudget = definitions.fold(0L) { total, definition -> Math.addExact(total, definition.budgetMinor) }
        require(totalBudget <= serverWeeklyBudgetMinor) {
            "Configured contract budgets $totalBudget exceed server weekly envelope $serverWeeklyBudgetMinor minor units"
        }
        return definitions
    }

    private inline fun <reified E : Enum<E>> strictEnum(path: String, default: E): E {
        val raw = config.stringOrNull(path)?.trim() ?: return default
        return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Contract enum '$path' must be one of ${enumValues<E>().joinToString { it.name.lowercase() }}",
            )
    }

    companion object {
        const val MAX_CONFIGURED_ORDERS = 64
        private val SERVER_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{2,31}")

        fun fromFile(dataPath: Path): ContractsConfig =
            ContractsConfig(ConfigManager.of(dataPath, "modules/contracts.yml"))

        fun load(): ContractsConfig = fromFile(ARC.instance.dataPath)

        internal fun moneyMinor(raw: String, path: String, allowZero: Boolean = false): Long {
            val value =
                runCatching { BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY) }
                    .getOrElse { throw IllegalArgumentException("Contract money '$path' must have at most two decimals") }
            val minor =
                runCatching { value.movePointRight(2).longValueExact() }
                    .getOrElse { throw IllegalArgumentException("Contract money '$path' is outside the supported range") }
            require(if (allowZero) minor >= 0L else minor > 0L) {
                "Contract money '$path' must be ${if (allowZero) "non-negative" else "positive"}"
            }
            return minor
        }

        private fun instant(raw: String, path: String): Long =
            runCatching { Instant.parse(raw.trim()).toEpochMilli() }
                .getOrElse { throw IllegalArgumentException("Contract timestamp '$path' must be an ISO-8601 instant") }
    }
}
