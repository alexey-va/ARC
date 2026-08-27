package ru.arc.buildertools

import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState

/** Main-thread inputs published as one immutable, bounded health contribution. */
internal data class BuilderToolsRuntimeHealthInputs(
    val closed: Boolean,
    val recovering: Boolean,
    val recoveryBlocked: Boolean,
    val recoveryPlayers: Int,
    val deliveryWaitingForSpace: Int,
    val activeOperations: Int,
    val bookLockedPlayers: Int,
    val landsRequired: Boolean,
    val landsAvailable: Boolean,
    val coreProtectRequired: Boolean,
    val coreProtectAvailable: Boolean,
    val bookContractsEnabled: Boolean,
    val bookRegistryReady: Boolean,
    val bookRegistryFailed: Boolean,
) {
    init {
        listOf(
            recoveryPlayers,
            deliveryWaitingForSpace,
            activeOperations,
            bookLockedPlayers,
        ).forEach { require(it >= 0) { "Builder-tools health counters must not be negative" } }
    }
}

internal object BuilderToolsRuntimeHealth {
    fun contribution(input: BuilderToolsRuntimeHealthInputs): RuntimeHealthContribution {
        val landsReady = !input.landsRequired || input.landsAvailable
        val coreProtectReady = !input.coreProtectRequired || input.coreProtectAvailable
        val registryReady = !input.bookContractsEnabled || (input.bookRegistryReady && !input.bookRegistryFailed)
        val backlog = saturatedAdd(input.recoveryPlayers, input.deliveryWaitingForSpace)
        val leases = saturatedAdd(input.activeOperations, input.bookLockedPlayers)
        val state = when {
            input.closed || input.recoveryBlocked || !landsReady || !coreProtectReady -> RuntimeHealthState.DOWN
            input.recovering || (input.bookContractsEnabled && !input.bookRegistryReady && !input.bookRegistryFailed) ->
                RuntimeHealthState.STARTING
            input.bookRegistryFailed || backlog > 0 -> RuntimeHealthState.DEGRADED
            else -> RuntimeHealthState.UP
        }
        return RuntimeHealthContribution(
            state = state,
            recoveryBacklog = backlog,
            activeLeases = leases,
            schemas = if (input.bookContractsEnabled) {
                mapOf("book_registry" to BuilderBookSqlRegistry.CURRENT_SCHEMA_VERSION)
            } else {
                emptyMap()
            },
            dependencies = linkedMapOf(
                "lands" to landsReady,
                "coreprotect" to coreProtectReady,
                "book_registry" to registryReady,
            ),
        )
    }

    private fun saturatedAdd(first: Int, second: Int): Int =
        (first.toLong() + second.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
