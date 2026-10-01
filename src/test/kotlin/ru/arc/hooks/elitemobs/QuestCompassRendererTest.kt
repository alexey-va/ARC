package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID

class QuestCompassRendererTest : FreeSpec({
    val empty = "-".repeat(63)
    fun text(yaw: Float, native: String = empty) = PlainTextComponentSerializer.plainText()
        .serialize(QuestCompassRenderer.render(yaw, native))
    fun target(index: Int, glyph: Char) = empty.toCharArray().apply { this[index] = glyph }.concatToString()

    "reference strip contains only spaces directions and angle brackets without a ruler" {
        // Official vanilla 1.21.11 advances: spaces=4, cardinals=6, angle brackets=5.
        for (yaw in 0..359) {
            val rendered = QuestCompassRenderer.render(yaw.toFloat(), empty)
            rendered.font() shouldBe Key.key("minecraft", "default")
            text(yaw.toFloat()).sumOf { glyph ->
                when (glyph) {
                    '<', '>' -> 5
                    ' ' -> 4
                    'N', 'E', 'S', 'W' -> 6
                    else -> error("Unchecked compass glyph: $glyph")
                }
            }.let { it in 302..304 } shouldBe true
        }
    }

    "cardinal directions match Bukkit yaw and retain constant width" {
        mapOf(0f to 'S', 90f to 'W', 180f to 'N', -90f to 'E').forEach { (yaw, cardinal) ->
            text(yaw).length shouldBe QuestCompassRenderer.WIDTH + 2
            text(yaw)[37] shouldBe cardinal
        }
        text(0f).trim('<', '>', ' ') shouldBe "S"
        text(45f).trim('<', '>', ' ') shouldBe ""
    }

    "rotation wraps smoothly across negative yaw and multiple revolutions" {
        for (yaw in -360..360) {
            text(yaw.toFloat()) shouldBe text(yaw + 720f)
            text(yaw.toFloat()).length shouldBe 75
        }
        text(Float.NaN) shouldBe text(0f)
    }

    "different POI types keep distinct glyphs on the correct side" {
        text(0f, target(31, '⦿'))[37] shouldBe '◇'
        text(0f, target(19, '☠'))[1] shouldBe '☠'
        text(0f, target(43, '⚔'))[73] shouldBe '⚔'
        text(0f, target(31, '⬯'))[37] shouldBe '○'
    }

    "targets outside the reference window are not falsely pinned to its edges" {
        for (index in listOf(0, 18, 44, 62)) {
            text(0f, target(index, '⦿')) shouldBe text(0f)
        }
    }

    "height cue wins when nearby projected markers collapse into its cell" {
        for (arrow in listOf('↑', '↓', '↕')) {
            val native = target(31, arrow).toCharArray().apply { this[32] = '☠' }.concatToString()
            text(10f, native)[37] shouldBe arrow
        }
    }

    "waiting and unresolved targets stay compact and do not become bogus markers" {
        text(0f, "Waiting for the dungeon to start") shouldBe "< Ожидание старта данжа >"
        text(0f, "[EM] No quest destination found!") shouldBe "< Цель пока не найдена >"
        text(0f, "[EM] Go to world dungeon_instance_123!") shouldBe "< Цель в мире: dungeon_instance_123 >"
        text(0f, "[EM] Go to world " + "long_world_".repeat(10) + "!").length shouldBe 44
        text(0f, "§a" + empty) shouldBe text(0f)
        text(0f, "x".repeat(100)).length shouldBe 44
    }

    "ambient compass projects distinct nearby roles and chests without a quest" {
        val rendered = PlainTextComponentSerializer.plainText().serialize(QuestCompassRenderer.render(
            0f, null, listOf(
                CompassPoi(-30.0, 12.0, DungeonCompassPointKind.GUILD_NPC),
                CompassPoi(-24.0, 10.0, DungeonCompassPointKind.CLASS_TRAINER),
                CompassPoi(-18.0, 10.0, DungeonCompassPointKind.TRANSPORT),
                CompassPoi(-12.0, 10.0, DungeonCompassPointKind.SHOP),
                CompassPoi(-6.0, 10.0, DungeonCompassPointKind.NPC_SERVICE),
                CompassPoi(6.0, 10.0, DungeonCompassPointKind.ELITE_MOB),
                CompassPoi(12.0, 10.0, DungeonCompassPointKind.ELITE_BOSS),
                CompassPoi(18.0, 10.0, DungeonCompassPointKind.CHEST),
                CompassPoi(180.0, 10.0, DungeonCompassPointKind.CHEST),
                CompassPoi(10.0, 65.0, DungeonCompassPointKind.CHEST),
            ),
        ))
        rendered[7] shouldBe '⚑'
        rendered[13] shouldBe '▲'
        rendered[19] shouldBe '↔'
        rendered[25] shouldBe '¤'
        rendered[31] shouldBe '●'
        rendered[43] shouldBe '⚔'
        rendered[49] shouldBe '☠'
        rendered[55] shouldBe '□'
        rendered.count { it == '□' } shouldBe 1
        rendered[37] shouldBe 'S'
    }

    "tracked target wins a collision and nearby POIs cannot overcrowd the strip" {
        val rendered = PlainTextComponentSerializer.plainText().serialize(QuestCompassRenderer.render(
            0f, target(31, '⦿'), listOf(
                CompassPoi(0.0, 1.0, DungeonCompassPointKind.CHEST),
                CompassPoi(12.0, 20.0, DungeonCompassPointKind.ELITE_BOSS),
                CompassPoi(12.0, 2.0, DungeonCompassPointKind.AVAILABLE_QUEST),
                CompassPoi(Double.NaN, 3.0, DungeonCompassPointKind.ELITE_MOB),
            ),
        ))
        rendered[37] shouldBe '◇'
        rendered[49] shouldBe '☠'
        rendered.count { it == '□' } shouldBe 0
    }

    "unresolved tracking still permits real nearby points instead of inventing quest coordinates" {
        val rendered = PlainTextComponentSerializer.plainText().serialize(QuestCompassRenderer.render(
            0f, "[EM] No quest destination found!",
            listOf(CompassPoi(0.0, 3.0, DungeonCompassPointKind.AVAILABLE_QUEST)),
        ))
        rendered[37] shouldBe '!'
        rendered.contains('◇') shouldBe false
    }

    "POI bearing follows movement but never crosses world or nearby radius boundaries" {
        val id = UUID.randomUUID()
        val world = mockk<World> { every { uid } returns id }
        val point = DungeonCompassPoint(id, 0.0, 64.0, 10.0, DungeonCompassPointKind.CHEST)
        point.project(Location(world, 0.0, 64.0, 0.0))!!.bearing shouldBe 0.0
        point.project(Location(world, 0.0, 64.0, 20.0))!!.bearing.let { kotlin.math.abs(it) } shouldBe 180.0
        point.project(Location(world, 0.0, 64.0, -60.0)) shouldBe null
        point.copy(worldId = UUID.randomUUID()).project(Location(world, 0.0, 64.0, 0.0)) shouldBe null
        point.copy(x = Double.NaN).project(Location(world, 0.0, 64.0, 0.0)) shouldBe null
        point.project(Location(world, 0.0, 64.0, 10.0)) shouldBe null
    }

    "crowded compass draws no more than eight visible nearby points" {
        val points = (-30..30 step 3).map { CompassPoi(it.toDouble(), 10.0, DungeonCompassPointKind.ELITE_MOB) }
        val rendered = PlainTextComponentSerializer.plainText().serialize(QuestCompassRenderer.render(0f, null, points))
        rendered.count { it == '⚔' } shouldBe 8
    }

    "bosses outrank nearer common elites when the strip is crowded" {
        val points = (-30..-9 step 3).map { CompassPoi(it.toDouble(), 1.0, DungeonCompassPointKind.ELITE_MOB) } +
            CompassPoi(30.0, 64.0, DungeonCompassPointKind.ELITE_BOSS)
        val rendered = PlainTextComponentSerializer.plainText().serialize(QuestCompassRenderer.render(0f, null, points))
        rendered.count { it == '☠' } shouldBe 1
        rendered.count { it == '⚔' } shouldBe 7
    }

    "elite NPC service roles use distinct visible markers" {
        val markers = mapOf(
            DungeonCompassPointKind.QUEST_UNAVAILABLE to '?',
            DungeonCompassPointKind.REPAIR to '+',
            DungeonCompassPointKind.SCRAP to '×',
            DungeonCompassPointKind.ENCHANT to '★',
            DungeonCompassPointKind.UNBIND to '÷',
            DungeonCompassPointKind.SCROLL to '≡',
        )
        markers.forEach { (kind, marker) ->
            val rendered = PlainTextComponentSerializer.plainText().serialize(
                QuestCompassRenderer.render(45f, null, listOf(CompassPoi(45.0, 4.0, kind))),
            )
            rendered[37] shouldBe marker
        }
    }
})
