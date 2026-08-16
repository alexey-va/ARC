package ru.arc.ops

import ru.arc.metrics.MetricsModule

/** Authenticated read-only product journey report; no player identity is returned. */
object OpsProductInterestHandlers {
    fun summary(
        days: Int,
        limit: Int,
    ): Map<String, Any?> {
        require(days in 1..35) { "days must be 1..35" }
        require(limit in 1..100) { "limit must be 1..100" }
        return MetricsModule.productInterestReport(days, limit)
    }
}
