package ru.arc.audit.autosell

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class AutoSellChestObservation(
    val ownerOnline: Boolean,
    val itemsSold: Long,
    val nextInterval: Long,
    val interval: Long,
    val multiplier: Double,
)

internal data class AutoSellRuntimeSample(
    val pluginVersion: String,
    val ownerMustBeOnline: Boolean,
    val chests: List<AutoSellChestObservation>,
    val discoveredChests: Int = chests.size,
    val capped: Boolean = false,
)

private data class AutoSellRuntimeSnapshot(
    val status: String = "warming_up",
    val pluginVersion: String? = null,
    val sampledAt: Long? = null,
    val ownerMustBeOnline: Boolean? = null,
    val discoveredChests: Int = 0,
    val sampledChests: Int = 0,
    val capped: Boolean = false,
    val onlineOwnerChests: Int = 0,
    val eligibleChests: Int = 0,
    val chestsWithPriorSales: Int = 0,
    val lifetimeItemsSold: Long = 0,
    val dueWithin60Seconds: Int = 0,
    val nextDueInSeconds: Long? = null,
    val multipliers: Map<String, Int> = emptyMap(),
    val intervalSeconds: Map<String, Int> = emptyMap(),
    val error: String? = null,
)

/** Thread-safe aggregate state for authenticated AutoSell diagnostics. */
internal class AutoSellAuditService(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val capturedPreTransactions = AtomicLong()
    private val capturedItems = AtomicLong()
    private val lastPreTransactionAt = AtomicLong()

    @Volatile
    private var runtime = AutoSellRuntimeSnapshot()

    fun reset() {
        runtime = AutoSellRuntimeSnapshot()
        capturedPreTransactions.set(0)
        capturedItems.set(0)
        lastPreTransactionAt.set(0)
    }

    fun accept(sample: AutoSellRuntimeSample) {
        val sampledAt = now()
        val eligible =
            if (sample.ownerMustBeOnline) {
                sample.chests.count(AutoSellChestObservation::ownerOnline)
            } else {
                sample.chests.size
            }
        val due = sample.chests.filter { !sample.ownerMustBeOnline || it.ownerOnline }
        runtime =
            AutoSellRuntimeSnapshot(
                status = if (sample.capped) "partial" else "ready",
                pluginVersion = sample.pluginVersion,
                sampledAt = sampledAt,
                ownerMustBeOnline = sample.ownerMustBeOnline,
                discoveredChests = sample.discoveredChests,
                sampledChests = sample.chests.size,
                capped = sample.capped,
                onlineOwnerChests = sample.chests.count(AutoSellChestObservation::ownerOnline),
                eligibleChests = eligible,
                chestsWithPriorSales = sample.chests.count { it.itemsSold > 0 },
                lifetimeItemsSold = sample.chests.sumOf { it.itemsSold.coerceAtLeast(0) },
                dueWithin60Seconds = due.count { it.nextInterval - sampledAt <= 60_000L },
                nextDueInSeconds =
                    due.minOfOrNull { ((it.nextInterval - sampledAt).coerceAtLeast(0L)) / 1_000L },
                multipliers = countDoubles(sample.chests.map(AutoSellChestObservation::multiplier)),
                intervalSeconds = countLongs(sample.chests.map { (it.interval.coerceAtLeast(0L)) / 1_000L }),
            )
    }

    fun unavailable(status: String, failure: Throwable? = null, pluginVersion: String? = null) {
        require(status in setOf("plugin_missing", "unsupported", "error"))
        runtime =
            AutoSellRuntimeSnapshot(
                status = status,
                pluginVersion = pluginVersion,
                sampledAt = now(),
                error = failure?.let { "${it::class.java.simpleName}: ${it.message.orEmpty()}".take(240) },
            )
    }

    fun recordCapture(itemQuantity: Int) {
        capturedPreTransactions.incrementAndGet()
        capturedItems.addAndGet(itemQuantity.coerceAtLeast(0).toLong())
        lastPreTransactionAt.set(now())
    }

    fun summary(): Map<String, Any?> {
        val snapshot = runtime
        return linkedMapOf(
            "status" to snapshot.status,
            "pluginVersion" to snapshot.pluginVersion,
            "sampledAt" to snapshot.sampledAt,
            "ownerMustBeOnline" to snapshot.ownerMustBeOnline,
            "loadedChests" to snapshot.discoveredChests,
            "sampledChests" to snapshot.sampledChests,
            "sampleCapped" to snapshot.capped,
            "onlineOwnerChests" to snapshot.onlineOwnerChests,
            "eligibleChests" to snapshot.eligibleChests,
            "chestsWithPriorSales" to snapshot.chestsWithPriorSales,
            "lifetimeItemsSold" to snapshot.lifetimeItemsSold,
            "dueWithin60Seconds" to snapshot.dueWithin60Seconds,
            "nextDueInSeconds" to snapshot.nextDueInSeconds,
            "multipliers" to snapshot.multipliers,
            "intervalSeconds" to snapshot.intervalSeconds,
            "capturedPreTransactionsSinceStart" to capturedPreTransactions.get(),
            "capturedItemsSinceStart" to capturedItems.get(),
            "lastPreTransactionAt" to lastPreTransactionAt.get().takeIf { it > 0L },
            "error" to snapshot.error,
            "scope" to "local process; loaded chunks only; no player, chest, inventory, or location identifiers",
        )
    }

    private fun countDoubles(values: Collection<Double>): Map<String, Int> =
        values.asSequence()
            .filter { it.isFinite() && it >= 0.0 }
            .groupingBy(::formatNumber)
            .eachCount()
            .toSortedMap(compareBy(String::toBigDecimalOrNull))

    private fun countLongs(values: Collection<Long>): Map<String, Int> =
        values.groupingBy(Long::toString).eachCount().toSortedMap(compareBy(String::toLongOrNull))

    private fun formatNumber(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** Exact AutoSellChests 2.9.0 public-method adapter without a shaded runtime dependency. */
internal object AutoSellReflectionReader {
    fun read(
        plugin: Any,
        pluginVersion: String,
        ownerMustBeOnline: Boolean,
        ownerOnline: (UUID) -> Boolean,
    ): AutoSellRuntimeSample {
        val manager = invoke(plugin, "getManager") ?: error("getManager returned null")
        val loaded = invoke(manager, "getLoadedChests") as? Map<*, *> ?: error("getLoadedChests returned no map")
        val observations =
            loaded.values.asSequence().filterNotNull().take(MAX_CHESTS).map { chest ->
                val owner = invoke(chest, "getOwner") as? UUID ?: error("getOwner returned no UUID")
                AutoSellChestObservation(
                    ownerOnline = ownerOnline(owner),
                    itemsSold = number(chest, "getItemsSold").toLong(),
                    nextInterval = number(chest, "getNextInterval").toLong(),
                    interval = number(chest, "getInterval").toLong(),
                    multiplier = number(chest, "getMultiplier").toDouble(),
                )
            }.toList()
        return AutoSellRuntimeSample(
            pluginVersion = pluginVersion,
            ownerMustBeOnline = ownerMustBeOnline,
            chests = observations,
            discoveredChests = loaded.size,
            capped = loaded.size > observations.size,
        )
    }

    private fun invoke(target: Any, method: String): Any? = target::class.java.getMethod(method).invoke(target)

    private fun number(target: Any, method: String): Number =
        invoke(target, method) as? Number ?: error("$method returned no number")

    private const val MAX_CHESTS = 20_000
}
