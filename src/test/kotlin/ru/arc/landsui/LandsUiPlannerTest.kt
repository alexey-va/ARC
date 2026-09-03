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
        val land = LandsUiLand("01KLAND", "Берег", owner, 12, setOf(owner, member), 8, 120.0)

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

    "builds safe Lands 8 selected-settlement commands" {
        LandsUiCommands.addMember("Alex_23") shouldBe "lands land member add Alex_23"
        LandsUiCommands.create("Новый_дом") shouldBe "lands create Новый_дом"
        LandsUiCommands.rename("Новый_Берег") shouldBe "lands land rename Новый_Берег"
        LandsUiCommands.menu() shouldBe "lands menu"

        runCatching { LandsUiCommands.land("bad argument") }.isFailure shouldBe true
        runCatching { LandsUiCommands.member("bad-name!") }.isFailure shouldBe true
    }
})
