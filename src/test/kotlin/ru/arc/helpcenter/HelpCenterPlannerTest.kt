package ru.arc.helpcenter

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class HelpCenterPlannerTest : StringSpec({
    val entries = listOf(
        HelpCenterCommand("privat", HelpCenterCategory.PROTECTION, "privat", "Приват", "Земли и поселения", "земля поселение lands"),
        HelpCenterCommand("warps", HelpCenterCategory.TRAVEL, "warps", "Варпы", "Публичные точки игроков", "телепорт точки"),
        HelpCenterCommand("rank", HelpCenterCategory.PROGRESS, "rank", "Ранги", "Серверный прогресс", "уровень развитие"),
    )

    "finds commands by label description command and configured keywords" {
        HelpCenterPlanner.search(entries, "земли", 5).map { it.id } shouldContainExactly listOf("privat")
        HelpCenterPlanner.search(entries, "/WARPS", 5).map { it.id } shouldContainExactly listOf("warps")
        HelpCenterPlanner.search(entries, "телепорт", 5).map { it.id } shouldContainExactly listOf("warps")
        HelpCenterPlanner.search(entries, "прогресс", 5).map { it.id } shouldContainExactly listOf("rank")
    }

    "returns a stable bounded catalog for an empty query" {
        HelpCenterPlanner.search(entries, "   ", 2).map { it.id } shouldContainExactly listOf("privat", "warps")
    }

    "builds only bounded player commands" {
        HelpCenterCommands.home("base_2") shouldBe "home base_2"
        HelpCenterCommands.createHome("Дом-2") shouldBe "sethome Дом-2"
        HelpCenterCommands.deleteHome("Дом-2") shouldBe "delhome Дом-2"
        HelpCenterCommands.relocateHome("Дом-2") shouldBe "edithome Дом-2 relocate"

        runCatching { HelpCenterCommands.home("bad argument") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.deleteHome("/op") }.isFailure shouldBe true
        runCatching { HelpCenterCommands.execute("tell Alex hello") }.isFailure shouldBe true
    }

    "resolves public help pages without exposing internal command help" {
        HelpCenterPage.from("travel") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("перемещения") shouldBe HelpCenterPage.TRAVEL
        HelpCenterPage.from("privat") shouldBe HelpCenterPage.PRIVAT
        HelpCenterPage.from("unknown") shouldBe null
    }
})
