package ru.arc.hooks.elitemobs

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

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
})
