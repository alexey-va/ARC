package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

class LandsUiPlannerTest : StringSpec({
    "lists only addable online players in stable name order" {
        val owner = UUID.randomUUID()
        val member = UUID.randomUUID()
        val candidateA = UUID.randomUUID()
        val candidateB = UUID.randomUUID()
        val land = LandsUiLand("01KLAND", "Берег", owner, 12, 64, setOf(owner, member), 8, 120.0, selected = true)

        val result = LandsUiPlanner.addablePlayers(
            owner,
            land,
            listOf(
                LandsUiPlayer(candidateB, "Zed"),
                LandsUiPlayer(member, "Member"),
                LandsUiPlayer(owner, "Owner"),
                LandsUiPlayer(candidateA, "Alex"),
            ),
        )

        result.map { it.name } shouldContainExactly listOf("Alex", "Zed")
    }

    "detects the settlement created after a command without relying on its name" {
        val owner = UUID.randomUUID()
        val before = setOf("01KOLD")
        val old = LandsUiLand("01KOLD", "Дом", owner, 8, 64, setOf(owner), 8, 0.0, selected = false)
        val created = LandsUiLand("01KNEW", "Новый дом", owner, 1, 64, setOf(owner), 8, 0.0, selected = true)

        LandsUiPlanner.createdLand(before, listOf(old, created)) shouldBe created
        LandsUiPlanner.createdLand(before, listOf(old)) shouldBe null
    }

    "offers only settlements that do not already contain the invited player" {
        val owner = UUID.randomUUID()
        val target = UUID.randomUUID()
        val existing = LandsUiLand("01KONE", "Берег", owner, 3, 64, setOf(owner, target), 8, 0.0, false)
        val available = LandsUiLand("01KTWO", "Аванпост", owner, 1, 64, setOf(owner), 8, 0.0, true)

        LandsUiPlanner.inviteableLands(target, listOf(existing, available)).map { it.name } shouldContainExactly
            listOf("Аванпост")
    }

    "builds safe Lands 8 selected-settlement commands" {
        LandsUiCommands.addMember("Alex_23") shouldBe "lands land member add Alex_23"
        LandsUiCommands.create("Новый_дом") shouldBe "lands create Новый_дом"
        LandsUiCommands.rename("Новый_Берег") shouldBe "lands land rename Новый_Берег"
        LandsUiCommands.menu() shouldBe "lands menu"

        runCatching { LandsUiCommands.create("Дом") }.isFailure shouldBe true
        runCatching { LandsUiCommands.create("Слишком_длинное_название_поселения") }.isFailure shouldBe true
        runCatching { LandsUiCommands.land("bad argument") }.isFailure shouldBe true
        runCatching { LandsUiCommands.member("bad-name!") }.isFailure shouldBe true
    }
})
