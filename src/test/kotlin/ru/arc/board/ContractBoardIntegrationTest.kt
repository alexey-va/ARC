package ru.arc.board

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.ResourceContractView

class ContractBoardIntegrationTest : StringSpec({
    "projects active contracts before player announcements and excludes expired windows" {
        val cards =
            ContractBoardCards.build(
                views =
                    listOf(
                        view(id = "later", status = "paused", endsAt = 4_000L),
                        view(id = "expired", status = "expired", endsAt = 1_000L),
                        view(id = "open", status = "open", endsAt = 3_000L),
                        view(id = "done", status = "completed", endsAt = 2_000L),
                    ),
                mode = ContractsMode.OBSERVE,
                submissionsEnabled = false,
                weeklyBudgetMinor = 25_000_000L,
            )

        cards.filterIsInstance<ContractBoardCard.Order>().map { it.view.id } shouldContainExactly
            listOf("open", "later", "done")
    }

    "shows one honest calibration card when no resource orders exist" {
        ContractBoardCards.build(
            views = emptyList(),
            mode = ContractsMode.OBSERVE,
            submissionsEnabled = false,
            weeklyBudgetMinor = 25_000_000L,
        ) shouldBe listOf(ContractBoardCard.Empty(ContractsMode.OBSERVE, 25_000_000L))
    }

    "does not expose a system card when contracts are disabled" {
        ContractBoardCards.build(
            views = emptyList(),
            mode = ContractsMode.DISABLED,
            submissionsEnabled = false,
            weeklyBudgetMinor = 0L,
        ) shouldBe emptyList()
    }

    "prepares submission only for funded open runtime orders" {
        val enabled = ContractBoardCard.Order(view(), submissionsEnabled = true)
        val observeOnly = ContractBoardCard.Order(view(), submissionsEnabled = false)
        val paused = ContractBoardCard.Order(view(status = "paused"), submissionsEnabled = true)
        val exhausted = ContractBoardCard.Order(view(remaining = 0L), submissionsEnabled = true)

        enabled.canPrepareSubmission shouldBe true
        observeOnly.canPrepareSubmission shouldBe false
        paused.canPrepareSubmission shouldBe false
        exhausted.canPrepareSubmission shouldBe false
    }

    "uses the requested vanilla material and fails closed for custom namespaces" {
        materialFor("minecraft:cobblestone") shouldBe Material.COBBLESTONE
        materialFor("slimefun:basic_circuit_board") shouldBe Material.PAPER
        materialFor("minecraft:not_a_material") shouldBe Material.PAPER
    }

    "exports passive bounded interaction metrics without player or item labels" {
        val cards = listOf(ContractBoardCard.Order(view(), submissionsEnabled = false))
        ContractBoardTelemetry.recordOpen(cards)
        ContractBoardTelemetry.recordInteraction("road_stone", "unavailable")

        val points = ContractBoardTelemetry.points()
        points.first { it.name == "arc_contract_board_opens_total" }.value.toLong() shouldBeGreaterThanOrEqual 1L
        points.first { it.name == "arc_contract_board_visible_cards" }.value shouldBe 1.0
        points.first { it.name == "arc_contract_board_interactions_total" }.tags shouldBe
            mapOf("contract" to "road_stone", "outcome" to "unavailable")
        points.flatMap { it.tags.keys }.none { it.contains("player") || it.contains("item") } shouldBe true
    }
})

private fun view(
    id: String = "road_stone",
    status: String = "open",
    endsAt: Long = 2_000L,
    remaining: Long = 145L,
) = ResourceContractView(
    id = id,
    displayName = "Камень для тракта",
    itemKey = "minecraft:cobblestone",
    funding = "server_envelope",
    status = status,
    windowStartsAt = 1_000L,
    windowEndsAt = endsAt,
    payoutMinorPerUnit = 250L,
    budgetMinor = 50_000L,
    spentMinor = 12_500L,
    reservedMinor = 1_250L,
    targetQuantity = 200L,
    acceptedQuantity = 50L,
    reservedQuantity = 5L,
    remainingQuantity = remaining,
    contributors = 3,
)
