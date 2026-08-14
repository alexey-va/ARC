package ru.arc.board

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.ResourceContractView
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.core.MetricPoint
import ru.arc.util.TextUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only system cards projected into the player bulletin board.
 *
 * These cards are never persisted as player board entries, so they cannot be
 * rated, reported, edited, expired by BoardManager, or announced as if a
 * player had paid to publish them. ContractsManager remains their only source
 * of truth.
 */
internal sealed interface ContractBoardCard {
    data class Order(
        val view: ResourceContractView,
        val submissionsEnabled: Boolean,
    ) : ContractBoardCard {
        val material: Material
            get() = materialFor(view.itemKey)

        val canPrepareSubmission: Boolean
            get() =
                submissionsEnabled &&
                    view.status == "open" &&
                    view.remainingQuantity > 0L &&
                    remainingBudgetMinor >= view.payoutMinorPerUnit

        val remainingBudgetMinor: Long
            get() = (view.budgetMinor - view.spentMinor - view.reservedMinor).coerceAtLeast(0L)

        val status: Component
            get() =
                TextUtil.mm(
                    when (view.status) {
                        "open" -> "<green>открыт"
                        "paused" -> "<yellow>ещё не начался"
                        "completed" -> "<aqua>выполнен"
                        else -> "<gray>недоступен"
                    },
                    true,
                )

        val action: Component
            get() =
                TextUtil.mm(
                    if (canPrepareSubmission) {
                        "<yellow>ЛКМ <gray>— подготовить команду сдачи"
                    } else {
                        "<dark_gray>Сдача предметов сейчас отключена"
                    },
                    true,
                )

        val endsAt: String
            get() = TIME_FORMAT.format(Instant.ofEpochMilli(view.windowEndsAt))

        val progressPercent: String
            get() =
                if (view.targetQuantity == 0L) {
                    "0"
                } else {
                    ((view.acceptedQuantity * 100L) / view.targetQuantity).coerceIn(0L, 100L).toString()
                }
    }

    data class Empty(
        val mode: ContractsMode,
        val weeklyBudgetMinor: Long,
    ) : ContractBoardCard {
        val state: Component
            get() =
                TextUtil.mm(
                    if (mode == ContractsMode.OBSERVE) {
                        "<yellow>калибровка экономики"
                    } else {
                        "<gray>нет активных заказов"
                    },
                    true,
                )
    }
}

internal object ContractBoardCards {
    fun current(): List<ContractBoardCard> {
        val mode = ContractsManager.mode()
        val weeklyBudgetMinor = ContractsManager.summary()["serverWeeklyBudgetMinor"] as? Long ?: 0L
        return build(
            views = ContractsManager.currentViews(),
            mode = mode,
            submissionsEnabled = ContractsManager.submissionsEnabled(),
            weeklyBudgetMinor = weeklyBudgetMinor,
        )
    }

    internal fun build(
        views: List<ResourceContractView>,
        mode: ContractsMode,
        submissionsEnabled: Boolean,
        weeklyBudgetMinor: Long,
    ): List<ContractBoardCard> {
        if (mode == ContractsMode.DISABLED) return emptyList()

        val orders =
            views
                .asSequence()
                .filterNot { it.status == "expired" }
                .sortedWith(compareBy<ResourceContractView>({ statusRank(it.status) }, { it.windowEndsAt }, { it.id }))
                .map { ContractBoardCard.Order(it, submissionsEnabled) }
                .toList()

        return orders.ifEmpty { listOf(ContractBoardCard.Empty(mode, weeklyBudgetMinor)) }
    }

    private fun statusRank(status: String): Int =
        when (status) {
            "open" -> 0
            "paused" -> 1
            "completed" -> 2
            else -> 3
        }
}

/** Passive, bounded content telemetry. No player identity is recorded. */
internal object ContractBoardTelemetry {
    private data class InteractionKey(val contractId: String, val outcome: String)

    private val opens = AtomicLong()
    private val interactions = ConcurrentHashMap<InteractionKey, AtomicLong>()
    @Volatile private var visibleCards: Int = 0

    fun recordOpen(cards: List<ContractBoardCard>) {
        val visibleContractIds = cards.filterIsInstance<ContractBoardCard.Order>().mapTo(mutableSetOf()) { it.view.id }
        interactions.keys.removeIf { it.contractId !in visibleContractIds }
        visibleCards = visibleContractIds.size
        opens.incrementAndGet()
        publish()
    }

    fun recordInteraction(contractId: String, outcome: String) {
        require(outcome == "submit_prompt" || outcome == "unavailable") { "Unsupported board interaction outcome" }
        interactions.computeIfAbsent(InteractionKey(contractId, outcome)) { AtomicLong() }.incrementAndGet()
        publish()
    }

    internal fun points(): List<MetricPoint> =
        buildList {
            add(
                MetricPoint(
                    "arc_contract_board_opens_total",
                    "Contract-integrated bulletin board opens",
                    opens.get().toDouble(),
                ),
            )
            add(
                MetricPoint(
                    "arc_contract_board_visible_cards",
                    "Visible configured contract cards on the bulletin board",
                    visibleCards.toDouble(),
                ),
            )
            interactions.entries.sortedWith(compareBy({ it.key.contractId }, { it.key.outcome })).forEach { (key, value) ->
                add(
                    MetricPoint(
                        "arc_contract_board_interactions_total",
                        "Contract bulletin board interactions by bounded contract and outcome",
                        value.get().toDouble(),
                        mapOf("contract" to key.contractId, "outcome" to key.outcome),
                    ),
                )
            }
        }

    private fun publish() {
        MetricsModule.recordSnapshot("contract-board", "economy-contracts", ::points)
    }
}

internal fun materialFor(itemKey: String): Material {
    val parts = itemKey.split(':', limit = 2)
    if (parts.size != 2 || parts[0] != "minecraft") return Material.PAPER
    return Material.getMaterial(parts[1].uppercase(Locale.ROOT)) ?: Material.PAPER
}

internal fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

private val TIME_FORMAT =
    DateTimeFormatter.ofPattern("dd.MM HH:mm 'МСК'", java.util.Locale.forLanguageTag("ru-RU"))
        .withZone(ZoneId.of("Europe/Moscow"))
