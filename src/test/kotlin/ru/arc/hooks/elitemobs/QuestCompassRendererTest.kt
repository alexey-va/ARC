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

    "rail uses six-pixel default glyphs instead of collapsed dots in uniform font" {
        // Official vanilla 1.21.11 bitmap advances; brackets=4, rail/ticks/cardinals=6.
        for (yaw in 0..359) {
            val rendered = QuestCompassRenderer.render(yaw.toFloat(), empty)
            rendered.font() shouldBe Key.key("minecraft", "default")
            text(yaw.toFloat()).sumOf { glyph ->
                when (glyph) {
                    '[', ']' -> 4
                    '-', '│', 'N', 'E', 'S', 'W' -> 6
                    else -> error("Unchecked compass glyph: $glyph")
                }
            } shouldBe 302
        }
    }

    "cardinal directions match Bukkit yaw and retain constant width" {
        mapOf(0f to 'S', 90f to 'W', 180f to 'N', -90f to 'E').forEach { (yaw, cardinal) ->
            text(yaw).length shouldBe QuestCompassRenderer.WIDTH + 2
            text(yaw)[25] shouldBe cardinal
        }
        text(0f)[1] shouldBe 'E'
        text(0f)[49] shouldBe 'W'
    }

    "rotation wraps smoothly across negative yaw and multiple revolutions" {
        for (yaw in -360..360) {
            text(yaw.toFloat()) shouldBe text(yaw + 720f)
            text(yaw.toFloat()).length shouldBe 51
        }
        text(Float.NaN) shouldBe text(0f)
    }

    "projected objectives replace ticks and remain on the correct side" {
        text(0f, target(31, '⦿'))[25] shouldBe '◇'
        text(0f, target(1, '☠'))[1] shouldBe '☠'
        text(0f, target(61, '⚔'))[49] shouldBe '⚔'
        text(0f, target(31, '⬯'))[25] shouldBe '◇'
    }

    "height cue wins when nearby projected markers collapse into its cell" {
        for (arrow in listOf('↑', '↓', '↕')) {
            val native = target(31, arrow).toCharArray().apply { this[32] = '☠' }.concatToString()
            text(10f, native)[25] shouldBe arrow
        }
    }

    "waiting and unresolved targets stay compact and do not become bogus markers" {
        text(0f, "Waiting for the dungeon to start") shouldBe "[ Ожидание старта данжа ]"
        text(0f, "[EM] No quest destination found!") shouldBe "[ Цель пока не найдена ]"
        text(0f, "[EM] Go to world dungeon_instance_123!") shouldBe "[ Цель в мире: dungeon_instance_123 ]"
        text(0f, "[EM] Go to world " + "long_world_".repeat(10) + "!").length shouldBe 44
        text(0f, "§a" + empty) shouldBe text(0f)
        text(0f, "x".repeat(100)).length shouldBe 44
    }
})
