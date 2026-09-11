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

    "long dungeon names wrap at word boundaries without widening the sidebar" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.panelView(player) } returns DungeonPanelView(
            UUID.randomUUID(),
            DungeonVisit(
                "run",
                name = "&f⚔ &a[ур. на выбор] &bПодземелье Освящённого призыва",
                instanced = true,
            ),
            null,
        )
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }

        val board = DungeonScoreboard(qol) { null }
        board.refresh(listOf(player))

        visibleText(board.value(id, "line_2")) shouldBe "⚔ [ур. на выбор] Подземелье"
        visibleText(board.value(id, "line_3")) shouldBe "Освящённого призыва"
        visibleText(board.value(id, "line_4")) shouldBe "Отдельное прохождение"
    }

    "hex colour codes survive wrapping and do not consume visible width" {
        val lines = wrapLegacyScoreboardText("§#12ab34Очень длинное название", 12, 3)

        lines.map(::visibleText) shouldBe listOf("Очень", "длинное", "название")
        lines.all { it.startsWith("§#12ab34") } shouldBe true
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

private fun visibleText(value: String): String =
    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build()
            .deserialize(value),
    )
