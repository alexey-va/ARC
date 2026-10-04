package ru.arc.board

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.contracts.ContractsMode
import ru.arc.contracts.ResourceContractView
import ru.arc.util.TextUtil
import ru.arc.paper.testing.MockBukkitTestRuntime

class ContractBoardIntegrationTest : StringSpec({
    "projects only open contracts and hides future completed and expired windows" {
        val cards =
            ContractBoardCards.build(
                views =
                    listOf(
                        view(id = "later", status = "paused", endsAt = 4_000L),
                        view(id = "expired", status = "expired", endsAt = 1_000L),
                        view(id = "open", status = "open", endsAt = 3_000L),
                        view(id = "done", status = "completed", endsAt = 2_000L),
                    ),
                mode = ContractsMode.ENFORCE,
                submissionsEnabled = true,
                weeklyBudgetMinor = 25_000_000L,
            )

        cards.filterIsInstance<ContractBoardCard.Order>().map { it.view.id } shouldContainExactly
            listOf("open")
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

    "shows the origin desk action outside the origin world" {
        val card = ContractBoardCard.Order(view().copy(group = "guild_orders"), submissionsEnabled = true)
        card.action(originAllowed = false) shouldBe TextUtil.mm("<yellow>Сдача у NPC: <white>Староста Григорий <gray>· спавн", true)
    }

    "uses the requested vanilla material and fails closed for custom namespaces" {
        MockBukkitTestRuntime.open().use {
            materialFor("minecraft:cobblestone") shouldBe Material.COBBLESTONE
            materialFor("arc:any_raw_fish") shouldBe Material.COD
            materialFor("slimefun:basic_circuit_board") shouldBe Material.PAPER
            materialFor("minecraft:not_a_material") shouldBe Material.PAPER
        }
    }

    "only advertises a funded market premium at the exact threshold" {
        hasContractPremium(view(base = 10_000L, price = 11_000L), 10) shouldBe true
        hasContractPremium(view(base = 10_001L, price = 11_001L), 10) shouldBe false
        hasContractPremium(view(base = 10_001L, price = 11_002L), 10) shouldBe true
        hasContractPremium(view(base = 200L, price = 200L), 10) shouldBe false
        hasContractPremium(view(base = Long.MAX_VALUE / 2, price = Long.MAX_VALUE), 10) shouldBe true
        fun build(v: ResourceContractView, budget: Long = Long.MAX_VALUE) = ContractBoardCards.build(
            listOf(v), ContractsMode.ENFORCE, true, 25_000_000L, remainingWeeklyBudgetMinor = budget,
        )
        build(view(price = 219L)) shouldBe emptyList()
        build(view(remaining = 0L)) shouldBe emptyList()
        build(view().copy(minSubmissionQuantity = 150)) shouldBe emptyList()
        build(view().copy(minimumSubmissionPayoutMinor = 50_000L)) shouldBe emptyList()
        build(view(), budget = 249L) shouldBe emptyList()
        build(view()).filterIsInstance<ContractBoardCard.Order>().single().priceGrowth shouldBe "+25%"
    }

    "removes adverts when the market price falls and never advertises observe-only sales" {
        val eligible = view()
        ContractBoardCards.build(listOf(eligible), ContractsMode.ENFORCE, true, 0L).size shouldBe 1
        ContractBoardCards.build(listOf(eligible.copy(payoutMinorPerUnit = 200L)), ContractsMode.ENFORCE, true, 0L) shouldBe emptyList()
        ContractBoardCards.build(listOf(eligible), ContractsMode.ENFORCE, false, 0L) shouldBe emptyList()
        ContractBoardCards.build(listOf(eligible), ContractsMode.OBSERVE, false, 0L)
            .filterIsInstance<ContractBoardCard.Order>() shouldBe emptyList()
    }

    "uses the actual NPC author for each order group" {
        val names = listOf("food_orders", "forge_orders", "bank_orders", "guild_orders").map { group ->
            ContractBoardCard.Order(view().copy(group = group), true).advertiser
        }
        names shouldContainExactly listOf("Матео", "Мила", "Делопроизводитель Артур", "Староста Григорий")
    }

    "shares the rotation with player adverts and drops stale windows" {
        val rotation = ContractBoardAnnouncementRotation()
        val a = ContractBoardCard.Order(view(id = "a"), true)
        val b = ContractBoardCard.Order(view(id = "b"), true)
        rotation.next(listOf(a, b), true, 100L) shouldBe a
        rotation.next(listOf(a, b), true, 200L) shouldBe null
        rotation.next(listOf(a, b), true, 300L) shouldBe b
        rotation.next(listOf(a, b), true, 400L) shouldBe null
        rotation.next(listOf(a, b), false, 500L) shouldBe a
        rotation.next(listOf(b), false, 600L) shouldBe b
        val nextWindow = a.copy(view = a.view.copy(windowStartsAt = 3_000L))
        rotation.next(listOf(nextWindow, b), false, 700L) shouldBe nextWindow
        rotation.next(emptyList(), true, 800L) shouldBe null
        rotation.clear()
        rotation.next(listOf(a, b), true, 900L) shouldBe a
    }

    "exports passive bounded interaction metrics without player or item labels" {
        val cards = listOf(ContractBoardCard.Order(view(), submissionsEnabled = false))
        ContractBoardTelemetry.recordOpen(cards)
        ContractBoardTelemetry.recordInteraction("road_stone", "open_gui")

        val points = ContractBoardTelemetry.points()
        points.first { it.name == "arc_contract_board_opens_total" }.value.toLong() shouldBeGreaterThanOrEqual 1L
        points.first { it.name == "arc_contract_board_visible_cards" }.value shouldBe 1.0
        points.first { it.name == "arc_contract_board_interactions_total" }.tags shouldBe
            mapOf("contract" to "road_stone", "outcome" to "open_gui")
        points.flatMap { it.tags.keys }.none { it.contains("player") || it.contains("item") } shouldBe true
    }
})

private fun view(
    id: String = "road_stone",
    status: String = "open",
    endsAt: Long = 2_000L,
    remaining: Long = 145L,
    base: Long = 200L,
    price: Long = 250L,
) = ResourceContractView(
    id = id,
    displayName = "Камень для тракта",
    itemKey = "minecraft:cobblestone",
    funding = "server_envelope",
    status = status,
    windowStartsAt = 1_000L,
    windowEndsAt = endsAt,
    payoutMinorPerUnit = price,
    basePayoutMinorPerUnit = base,
    budgetMinor = 50_000L,
    spentMinor = 12_500L,
    reservedMinor = 1_250L,
    targetQuantity = 200L,
    acceptedQuantity = 50L,
    reservedQuantity = 5L,
    remainingQuantity = remaining,
    contributors = 3,
)
