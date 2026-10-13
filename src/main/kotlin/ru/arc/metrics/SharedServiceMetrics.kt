package ru.arc.metrics

import ru.arc.paper.api.ArcMetricsService
import ru.arc.paper.redis.PaperRedisServiceProvider
import ru.arc.paper.sql.PaperSqlServiceProvider

/** Scalar resource counts only; connection settings never enter metric labels. */
internal class SharedServiceMetrics(
    metrics: ArcMetricsService,
    private val sql: PaperSqlServiceProvider,
    private val redis: PaperRedisServiceProvider,
) : AutoCloseable {
    private val scope = metrics.open("arc-shared-services")
    private val gauges = linkedMapOf(
        "sql_groups" to "Shared SQL pool groups",
        "sql_leases" to "Active shared SQL leases",
        "sql_tasks" to "Accepted shared SQL tasks awaiting completion",
        "sql_completion_tasks" to "Accepted durable SQL completion tasks",
        "sql_connections" to "Checked-out shared SQL connections",
        "sql_capacity" to "Maximum connections across shared SQL groups",
        "redis_groups" to "Shared Redis connection groups",
        "redis_leases" to "Active shared Redis leases",
        "redis_connected_groups" to "Connected shared Redis groups",
        "redis_channels" to "Distinct subscribed channels across Redis groups",
    ).mapValues { (name, description) -> scope.gauge("arc_shared_$name", description, emptyMap()) }

    fun snapshot(): Map<String, Int> {
        val pools = sql.snapshot()
        val transport = redis.diagnostics()
        return linkedMapOf(
            "sql_groups" to pools.size,
            "sql_leases" to pools.sumOf { it.borrowers },
            "sql_tasks" to pools.sumOf { it.inFlightTasks },
            "sql_completion_tasks" to pools.sumOf { it.inFlightCompletionTasks },
            "sql_connections" to pools.sumOf { it.openConnections },
            "sql_capacity" to pools.sumOf { it.maximumPoolSize },
            "redis_groups" to transport.connectionGroups,
            "redis_leases" to transport.activeLeases,
            "redis_connected_groups" to transport.connectedGroups,
            "redis_channels" to transport.activeChannels,
        )
    }

    fun refresh() = snapshot().forEach { (name, value) -> gauges.getValue(name).set(value.toDouble()) }

    override fun close() = scope.close()
}
