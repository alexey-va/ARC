package ru.arc.metrics

import ru.arc.metrics.core.MetricPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** First observations only: bounded by the closed mechanic catalog and daily UI row limit. */
internal object ProductEngagementObservations {
    val stages = setOf("seen", "selected", "interest", "result")
    val resultMechanics = setOf("dungeons", "mounts", "treasure", "rtp", "homes", "lands", "autobuild", "contracts",
        "foothold", "gathering", "building", "crafting", "combat", "social")
    val mechanics = ProductFeature.entries.map { it.label }.toSet() + resultMechanics
    fun validKey(key: String): Boolean {
        val parts = key.split('|')
        return when {
            parts.size == 2 -> parts[0] in mechanics && parts[1] in stages
            parts.size == 3 && parts[0] == "menu" -> ProductUiCodec.ID.matches(parts[1]) && ProductUiCodec.REVISION.matches(parts[2])
            else -> false
        }
    }
    fun key(signal: ProductSignal): String? = when (signal.kind) {
        ProductEventKind.FEATURE_INTEREST -> signal.feature?.let { "${it.label}|interest" }
        ProductEventKind.MEANINGFUL_OUTCOME -> signal.outcome?.takeIf { it.isGameplayResult() }?.let { outcome ->
            val mechanic = outcome.uiFeature()?.label ?: when (outcome) {
                ProductOutcome.FOOTHOLD_COMPLETE, ProductOutcome.FOOTHOLD_RECOVERED -> "foothold"
                ProductOutcome.GATHERING_THRESHOLD -> "gathering"
                ProductOutcome.BUILDING_THRESHOLD -> "building"
                ProductOutcome.CRAFTING_THRESHOLD -> "crafting"
                ProductOutcome.COMBAT_THRESHOLD -> "combat"
                ProductOutcome.SOCIAL_THRESHOLD -> "social"
                else -> null
            }
            mechanic?.let { "$it|result" }
        }
        else -> null
    }
    fun keys(signal: ProductUiSignal): List<String> = buildList {
        if (signal.kind == ProductUiKind.OPEN || signal.kind == ProductUiKind.IMPRESSION) add("menu|${signal.surface}|${signal.revision}")
        val stage = when (signal.kind) {
            ProductUiKind.IMPRESSION -> "seen"
            ProductUiKind.CLICK -> "selected"
            else -> null
        }
        if (stage != null && signal.feature != null) add("${signal.feature}|$stage")
    }
}

internal data class ProductEngagementDay(val date: LocalDate, val visited: Boolean, val first: Map<String, Long>, val last: Map<String, Long> = first)
internal class ProductEngagementPlayer(val joinedAt: Long?, val days: List<ProductEngagementDay>)
internal data class ProductRetentionCounts(val eligiblePlayers: Int, val returnedPlayers: Int) {
    val returnRate: Double? = if (eligiblePlayers == 0) null else returnedPlayers.toDouble() / eligiblePlayers
}
internal data class ProductEarlyRetentionRow(val within: String, val horizon: String, val mechanic: String,
    val stage: String, val group: String, val counts: ProductRetentionCounts)
internal data class ProductMenuRetentionRow(val surface: String, val revision: String, val horizon: String,
    val counts: ProductRetentionCounts, val mixedVersionPlayers: Int, val cohorts: Map<String, ProductRetentionCounts>)
internal data class ProductRepeatRow(val mechanic: String, val stage: String, val eligiblePlayers: Int,
    val usedPlayers: Int, val repeatedPlayers: Int)

/** Calendar cohorts close before comparison. Classification uses only the first 10/30 elapsed minutes,
 * never later behavior; each player is counted once, independently of click spam or server transfers.
 * This measures association, not the effect of a mechanic or a menu release. */
internal class ProductEngagementAnalysis(
    players: List<ProductEngagementPlayer>, private val now: Long, private val zone: ZoneId,
    val observationStartedAt: Long, cohortDays: Int,
) {
    private fun date(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    private val today = date(now)
    private val newcomers = players.filter { player -> player.joinedAt?.let {
        it >= observationStartedAt && it <= now && !date(it).isBefore(today.minusDays(cohortDays.toLong() - 1))
    } == true }
    private val horizons = mapOf("d1" to 1L, "d7" to 7L, "w1" to 7L)
    private fun mature(player: ProductEngagementPlayer, horizon: String): Boolean =
        date(requireNotNull(player.joinedAt)).plusDays(horizons.getValue(horizon)).isBefore(today)
    private fun returned(player: ProductEngagementPlayer, horizon: String): Boolean {
        val joined = date(requireNotNull(player.joinedAt))
        return player.days.any { day -> day.visited && if (horizon == "w1") {
            day.date.isAfter(joined) && !day.date.isAfter(joined.plusDays(7))
        } else day.date == joined.plusDays(horizons.getValue(horizon)) }
    }
    private fun early(player: ProductEngagementPlayer, budget: Long): Set<String> {
        val joined = requireNotNull(player.joinedAt)
        return player.days.asSequence().flatMap { it.first.asSequence() }
            .filter { (_, at) -> at in joined..minOf(now, joined + budget) }.map { it.key }.toSet()
    }
    private fun counts(players: List<ProductEngagementPlayer>, horizon: String) =
        ProductRetentionCounts(players.size, players.count { returned(it, horizon) })

    val earlyRetention: List<ProductEarlyRetentionRow> = buildList {
        for ((within, budget) in listOf("10m" to 600_000L, "30m" to 1_800_000L)) {
            val classified = newcomers.associateWith { early(it, budget) }
            // Only observed combinations: absent hooks must not look like a measured zero.
            val keys = newcomers.flatMap { early(it, 1_800_000) }.filter { !it.startsWith("menu|") }.toSortedSet()
            for (horizon in horizons.keys) {
                val eligible = newcomers.filter { mature(it, horizon) && requireNotNull(it.joinedAt) + budget <= now }
                for (key in keys) for (group in listOf("with", "without")) {
                    val selected = eligible.filter { (key in classified.getValue(it)) == (group == "with") }
                    add(ProductEarlyRetentionRow(within, horizon, key.substringBefore('|'), key.substringAfter('|'), group, counts(selected, horizon)))
                }
            }
        }
    }
    val menuRetention: List<ProductMenuRetentionRow> = buildList {
        val classified = newcomers.associateWith { early(it, 1_800_000).filter { key -> key.startsWith("menu|") }.toSet() }
        val keys = classified.values.flatten().toSortedSet()
        for (key in keys) for (horizon in horizons.keys) {
            val surface = key.split('|')[1]
            val eligible = newcomers.filter { mature(it, horizon) && key in classified.getValue(it) }
            val (single, mixed) = eligible.partition { player -> classified.getValue(player).count { it.startsWith("menu|$surface|") } == 1 }
            add(ProductMenuRetentionRow(surface, key.substringAfterLast('|'), horizon, counts(single, horizon), mixed.size,
                single.groupBy { date(requireNotNull(it.joinedAt)).toString() }.toSortedMap().mapValues { counts(it.value, horizon) }))
        }
    }.sortedWith(compareByDescending<ProductMenuRetentionRow> { it.counts.eligiblePlayers }.thenBy { it.surface }.thenBy { it.revision }.thenBy { it.horizon })

    val repeatUse: List<ProductRepeatRow> = buildList {
        val eligible = newcomers.filter { mature(it, "w1") }
        val earlyKeys = eligible.associateWith { early(it, 1_800_000) }
        val keys = earlyKeys.values.flatten().filter { it.endsWith("|result") || it.endsWith("|selected") }.toSortedSet()
        for (key in keys) {
            val used = eligible.filter { key in earlyKeys.getValue(it) }
            val repeated = used.count { player ->
                val joined = date(requireNotNull(player.joinedAt))
                // Repeat on another calendar day, within days 1–7, after the classification window.
                player.days.any { it.date.isAfter(joined) && !it.date.isAfter(joined.plusDays(7)) &&
                    (it.last[key] ?: Long.MIN_VALUE) > requireNotNull(player.joinedAt) + 1_800_000 }
            }
            add(ProductRepeatRow(key.substringBefore('|'), key.substringAfter('|'), eligible.size, used.size, repeated))
        }
    }

    fun report(limit: Int): Map<String, Any?> = linkedMapOf(
        "observationStartedAt" to observationStartedAt,
        "interpretation" to "Observed association, not causation; compare cohort dates and audience before judging a release",
        "classification" to "First 10/30 elapsed minutes after observed first primary Paper join, including players who leave earlier",
        "returnDefinition" to "A new Paper visit on day 1, day 7, or any day 1–7 in the configured timezone; only closed target days",
        "repeatDefinition" to "Used in first 30 minutes, then observed again on a later calendar day 1–7 after those 30 minutes; result and menu selection are separate",
        "resultCoverage" to ProductEngagementObservations.resultMechanics.sorted(),
        "missingResultCoverage" to (ProductFeature.entries.filter { it.countsAsSystem }.map { it.label }.toSet() - ProductEngagementObservations.resultMechanics).sorted(),
        "coverageNote" to "Farms, jobs, duels and events do not yet emit confirmed results here; social means chat threshold, not a completed team activity",
        "earlyRetention" to earlyRetention,
        "repeatUse" to repeatUse,
        "menuRetention" to menuRetention.take(limit),
        "menuRows" to menuRetention.size,
        "menuRowsTruncated" to (menuRetention.size > limit),
        "menuVersionDefinition" to "Exactly one observed version of this menu in first 30 minutes; multi-version exposure excluded and counted separately",
        "cohortDates" to newcomers.groupBy { date(requireNotNull(it.joinedAt)).toString() }.toSortedMap().map { (date, players) ->
            mapOf("date" to date, "players" to players.size)
        },
    )

    fun metrics(scope: String): List<MetricPoint> = buildList {
        fun addCount(name: String, value: Int, tags: Map<String, String>) {
            add(MetricPoint(name, "Closed newcomer cohorts: observed association only", value.toDouble(), mapOf("scope" to scope) + tags))
        }
        add(MetricPoint("arc_product_engagement_observation_started_timestamp_seconds", "Start of exact early-action observations", observationStartedAt / 1000.0, mapOf("scope" to scope)))
        for (row in earlyRetention) {
            val tags = mapOf("within" to row.within, "horizon" to row.horizon, "mechanic" to row.mechanic, "stage" to row.stage, "group" to row.group)
            addCount("arc_product_early_retention_players", row.counts.eligiblePlayers, tags + ("measure" to "eligible"))
            addCount("arc_product_early_retention_players", row.counts.returnedPlayers, tags + ("measure" to "returned"))
        }
        for (row in menuRetention.take(200)) {
            val tags = mapOf("surface" to row.surface, "revision" to row.revision, "horizon" to row.horizon, "within" to "30m")
            addCount("arc_product_menu_retention_players", row.counts.eligiblePlayers, tags + ("measure" to "eligible"))
            addCount("arc_product_menu_retention_players", row.counts.returnedPlayers, tags + ("measure" to "returned"))
            addCount("arc_product_menu_retention_players", row.mixedVersionPlayers, tags + ("measure" to "mixed"))
        }
        addCount("arc_product_menu_retention_omitted_rows", (menuRetention.size - 200).coerceAtLeast(0), emptyMap())
        for (row in repeatUse) {
            val tags = mapOf("mechanic" to row.mechanic, "stage" to row.stage, "within" to "30m", "window" to "7d")
            for ((measure, value) in listOf("eligible" to row.eligiblePlayers, "used" to row.usedPlayers, "repeated" to row.repeatedPlayers))
                addCount("arc_product_repeat_players", value, tags + ("measure" to measure))
        }
    }
}
