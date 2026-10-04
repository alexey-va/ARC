package ru.arc.board

import net.kyori.adventure.text.Component
import org.bukkit.Material
import ru.arc.contracts.ContractsManager
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.ResourceContractView
import ru.arc.contracts.formatContractDeadline
import ru.arc.contracts.contractPriceGrowth
import ru.arc.contracts.PaperContractItems
import ru.arc.config.BoardConfig
import ru.arc.config.contractAdvertiserName
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.core.MetricPoint
import ru.arc.util.TextUtil
import ru.arc.util.Logging
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only system cards projected into the player bulletin board.
 *
 * These cards are never persisted as player board entries, so they cannot be
 * rated, reported, edited, or expired by BoardManager. Eligible NPC advertisements
 * share the existing announcement rotation without publishing paid player entries.
 * ContractsManager remains their only source of truth.
 */
internal sealed interface ContractBoardCard {
    data class Order(
        val view: ResourceContractView,
        val submissionsEnabled: Boolean,
        val advertiser: String = contractAdvertiserName(view.group),
    ) : ContractBoardCard {
        val material: Material
            get() = materialFor(view.itemKey)

        val canPrepareSubmission: Boolean
            get() =
                submissionsEnabled &&
                    view.status == "open" &&
                    view.remainingQuantity >= view.minSubmissionQuantity &&
                    remainingBudgetMinor >= view.minimumSubmissionPayoutMinor

        val priceGrowth: String
            get() = contractPriceGrowth(view.basePayoutMinorPerUnit, view.payoutMinorPerUnit)

        val announcement: Component
            get() = Component.text("[$advertiser] ${view.displayName}: ${money(view.payoutMinorPerUnit)} ")
                .append(TextUtil.mm("<white>💰</white>"))
                .append(Component.text(" ($priceGrowth) · Заказы на доске объявлений"))

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

        fun action(originAllowed: Boolean): Component =
            TextUtil.mm(
                if (!originAllowed) {
                    "<yellow>Сдача у NPC: <white>$advertiser <gray>· спавн"
                } else if (canPrepareSubmission) {
                    "<yellow>ЛКМ <gray>— открыть книгу заказов"
                } else {
                    "<dark_gray>Сдача предметов сейчас отключена"
                },
                true,
            )

        val endsAt: String
            get() = formatContractDeadline(view.windowEndsAt)

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
    fun current(): List<ContractBoardCard> = runCatching {
        val mode = ContractsManager.mode()
        val summary = ContractsManager.summary()
        val weeklyBudgetMinor = summary["serverWeeklyBudgetMinor"] as? Long ?: 0L
        build(
            views = ContractsManager.currentViews(),
            mode = mode,
            submissionsEnabled = ContractsManager.submissionsEnabled(),
            weeklyBudgetMinor = weeklyBudgetMinor,
            minimumPremiumPercent = BoardConfig.contractMinimumPremiumPercent,
            remainingWeeklyBudgetMinor = summary["remainingWeeklyBudgetMinor"] as? Long ?: 0L,
            advertiser = BoardConfig::contractAdvertiser,
        )
    }.getOrElse {
        Logging.warn("NPC contract advertisements unavailable; player board entries remain available", it)
        emptyList()
    }

    internal fun build(
        views: List<ResourceContractView>,
        mode: ContractsMode,
        submissionsEnabled: Boolean,
        weeklyBudgetMinor: Long,
        minimumPremiumPercent: Int = 10,
        remainingWeeklyBudgetMinor: Long = Long.MAX_VALUE,
        advertiser: (String) -> String = ::contractAdvertiserName,
    ): List<ContractBoardCard> {
        if (mode == ContractsMode.DISABLED) return emptyList()
        if (mode == ContractsMode.OBSERVE) return listOf(ContractBoardCard.Empty(mode, weeklyBudgetMinor))
        if (!submissionsEnabled) return emptyList()

        val orders =
            views
                .asSequence()
                .filter { it.status == "open" }
                .map { ContractBoardCard.Order(it, submissionsEnabled, advertiser(it.group)) }
                .filter { it.canPrepareSubmission && it.view.minimumSubmissionPayoutMinor <= remainingWeeklyBudgetMinor }
                .filter { hasContractPremium(it.view, minimumPremiumPercent) }
                .sortedWith(compareBy({ it.view.windowEndsAt }, { it.view.id }))
                .toList()

        return orders
    }

}

/** Compare actual rounded market prices, excluding the player's rank bonus. */
internal fun hasContractPremium(view: ResourceContractView, minimumPercent: Int): Boolean {
    if (view.basePayoutMinorPerUnit <= 0L || view.payoutMinorPerUnit <= view.basePayoutMinorPerUnit) return false
    val base = BigInteger.valueOf(view.basePayoutMinorPerUnit)
    return (BigInteger.valueOf(view.payoutMinorPerUnit) - base) * BigInteger.valueOf(100L) >=
        base * BigInteger.valueOf(minimumPercent.coerceAtLeast(1).toLong())
}

/** Alternate NPC and player adverts when both exist; retain the board's single timer. */
internal class ContractBoardAnnouncementRotation {
    private data class Key(val id: String, val windowStartsAt: Long)
    private val lastShown = mutableMapOf<Key, Long>()
    private var previousWasContract = false

    fun next(orders: List<ContractBoardCard.Order>, playerAvailable: Boolean, now: Long): ContractBoardCard.Order? {
        val keys = orders.mapTo(mutableSetOf()) { Key(it.view.id, it.view.windowStartsAt) }
        lastShown.keys.retainAll(keys)
        if (orders.isEmpty() || (playerAvailable && previousWasContract)) {
            previousWasContract = false
            return null
        }
        val next = orders.minWithOrNull(compareBy({ lastShown[Key(it.view.id, it.view.windowStartsAt)] ?: 0L }, { it.view.id })) ?: return null
        lastShown[Key(next.view.id, next.view.windowStartsAt)] = now
        previousWasContract = true
        return next
    }

    fun clear() {
        lastShown.clear()
        previousWasContract = false
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
        require(outcome == "open_gui" || outcome == "unavailable") { "Unsupported board interaction outcome" }
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
    return PaperContractItems.material(itemKey) ?: Material.PAPER
}

internal fun money(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
