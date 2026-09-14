package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import com.magmaguy.elitemobs.advancedcombat.classes.ClassResourceType
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class DungeonScoreboardTest : FreeSpec({
    "refresh gathers native panel data and async reads use snapshots only" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.scoreboardView(player) } returns DungeonScoreboardView(
            DungeonVisit("run", name = "Крипта", stats = DungeonVisitStats(3, "normal", 12)), true)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, net.kyori.adventure.text.Component>>>().toMap()
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            val body = values.entries.fold(secondArg<String>()) { text, (key, value) ->
                text.replace("<$key>", plain.serialize(value))
            }
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(body)
        }
        val board = DungeonScoreboard(
            qol,
            party = { null },
            resource = { DungeonCombatResource(ClassResourceType.FURY, 37, 100) },
        ) { "42" }
        board.refresh(listOf(player))
        CompletableFuture.supplyAsync { board.value(id, "line_1") }.get() shouldBe "Крипта"
        board.value(id, "active") shouldBe "true"
        val lines = scoreboardLines(board, id)
        lines.joinToString("\n") shouldContain "Кристаллы"
        lines shouldBe listOf(
            "Крипта",
            "Открытый данж",
            "Прохождение идёт",
            "",
            "| Уровень: 12",
            "| Сложность: Обычная",
            "| Участников: 3",
            "| Ярость: 37/100",
            "| Кристаллы: 💎 42",
            "",
            "Shift + F — меню данжа",
        )
    }

    "long dungeon names wrap at word boundaries without widening the sidebar" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.scoreboardView(player) } returns DungeonScoreboardView(
            DungeonVisit(
                "run",
                name = "&f⚔ &a[ур. на выбор] &bПодземелье Освящённого призыва",
                instanced = true,
            ),
            true,
        )
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }

        val board = DungeonScoreboard(qol, party = { null }) { null }
        board.refresh(listOf(player))

        visibleText(board.value(id, "line_1")) shouldBe "⚔ [ур. на выбор] Подземелье"
        visibleText(board.value(id, "line_2")) shouldBe "Освящённого призыва"
        visibleText(board.value(id, "line_3")) shouldBe "Отдельное прохождение"
    }

    "hex colour codes survive wrapping and do not consume visible width" {
        val lines = wrapLegacyScoreboardText("§#12ab34Очень длинное название", 12, 3)

        lines.map(::visibleText) shouldBe listOf("Очень", "длинное", "название")
        lines.all { it.startsWith("§#12ab34") } shouldBe true
    }

    "nonparticipant in an instance gets an observer board and an absent dungeon clears it" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        var view: DungeonScoreboardView? = DungeonScoreboardView(DungeonVisit("run", instanced = true), false)
        every { qol.scoreboardView(player) } answers { view }
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }
        val board = DungeonScoreboard(qol, party = { null }) { null }
        board.refresh(listOf(player)); board.value(id, "active") shouldBe "true"
        (1..15).map { board.value(id, "line_$it") }.joinToString("\n") shouldContain "вы вне состава"
        (1..15).map { board.value(id, "line_$it") }.joinToString("\n") shouldContain "Меню похода — для участников"
        view = null
        board.refresh(listOf(player)); board.value(id, "active") shouldBe "false"
    }

    "finished state and open unknown stats stay explicit and truthful" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.scoreboardView(player) } returns DungeonScoreboardView(DungeonVisit("run", canResume = false), true)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(secondArg<String>())
        }
        val board = DungeonScoreboard(qol, party = { null }) { null }
        board.refresh(listOf(player))
        board.value(id, "active") shouldBe "true"
        (1..15).map { board.value(id, "line_$it") }.joinToString("\n") shouldContain "Поход окончен"
        board.clear()
        board.value(id, "active") shouldBe "false"
    }

    "party section shows the leader and members without exceeding the sidebar limit" {
        val player = mockk<Player>()
        val id = UUID.randomUUID()
        every { player.uniqueId } returns id
        val qol = mockk<EMDungeonQol>()
        every { qol.scoreboardView(player) } returns DungeonScoreboardView(
            DungeonVisit("run", name = "Крипта", instanced = true, stats = DungeonVisitStats(5, "hard", 20)), true)
        every { qol.text(any(), any(), *anyVararg()) } answers {
            val values = thirdArg<Array<out Pair<String, net.kyori.adventure.text.Component>>>().toMap()
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            values.entries.fold(secondArg<String>()) { text, (key, value) -> text.replace("<$key>", plain.serialize(value)) }
                .let(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()::deserialize)
        }
        val party = DungeonPartyView(
            available = true,
            inParty = true,
            members = listOf(
                DungeonPartyMember("Лидер", leader = true, online = true),
                DungeonPartyMember("Друг", leader = false, online = true),
            ),
        )
        val board = DungeonScoreboard(qol, party = { party }) { "42" }

        board.refresh(listOf(player))

        val lines = scoreboardLines(board, id)
        lines.joinToString("\n") shouldContain "Группа · 2"
        lines.joinToString("\n") shouldContain "★ Лидер"
        lines.joinToString("\n") shouldContain "● Друг"
        lines.count { it.isNotBlank() } shouldBe 11
        lines.count { it.isBlank() } shouldBe 3
        lines.first() shouldBe "Крипта"
        lines.last() shouldBe "Shift + F — меню данжа"
    }

    "section spacing has no leading or trailing rows and keeps every blank separator" {
        val rows = joinDungeonScoreboardSections(
            emptyList(),
            listOf("Поход"),
            emptyList(),
            listOf("Группа"),
            listOf("Меню"),
        )

        rows.map(::visibleText) shouldBe listOf("Поход", "", "Группа", "", "Меню")
        rows.filter(String::isEmpty).size shouldBe 2
    }
})

private fun scoreboardLines(board: DungeonScoreboard, playerId: UUID): List<String> =
    (1..15).map { visibleText(board.value(playerId, "line_$it")) }.dropLastWhile(String::isEmpty)

private fun visibleText(value: String): String =
    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build()
            .deserialize(value),
    )
