package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DungeonScoreboardTest : FreeSpec({
    "refresh gathers native panel data and async reads use snapshots only" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.panelView(player) } returns DungeonPanelView(
            UUID.randomUUID(), DungeonVisit("run", name = "Крипта", stats = DungeonVisitStats(3, "normal", 12)), null)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, net.kyori.adventure.text.Component>>>().toMap()
            val body = secondArg<String>().replace("<value>", values["value"]?.toString().orEmpty())
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(body)
        }
        val board = DungeonScoreboard(qol) { "42" }
        board.refresh(listOf(player))
        CompletableFuture.supplyAsync { board.value(id, "line_2") }.get() shouldBe "Крипта"
        board.value(id, "active") shouldBe "true"
        (1..15).map { board.value(id, "line_$it") }.joinToString("\n") shouldContain "Кристаллы"
    }

    "nonmember and absent panel clear the snapshot" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.panelView(player) } returns DungeonPanelView(UUID.randomUUID(), DungeonVisit("run"), null)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }
        val board = DungeonScoreboard(qol)
        board.refresh(listOf(player)); board.value(id, "active") shouldBe "true"
        board.remove(id); board.value(id, "active") shouldBe "false"
        board.refresh(emptyList()); board.value(id, "active") shouldBe "false"
    }

    "finished state and open unknown stats stay explicit and truthful" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.panelView(player) } returns DungeonPanelView(UUID.randomUUID(), DungeonVisit("run", canResume = false), null)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }
        val board = DungeonScoreboard(qol) { null }
        board.refresh(listOf(player))
        board.value(id, "active") shouldBe "true"
        (1..15).map { board.value(id, "line_$it") }.joinToString("\n") shouldContain "Поход окончен"
        board.clear()
        board.value(id, "active") shouldBe "false"
    }
})
