package ru.arc.chat

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.util.HSVLike
import ru.arc.config.ConfigManager
import java.nio.file.Files
import kotlin.math.abs

class ChatMessageColorizerTest : FreeSpec({
    val variation = ChatMessageColorVariation(enabled = true, hueAmplitudeDegrees = 12.0)
    val localBase = TextColor.color(0xE8D7B7)
    val globalBase = TextColor.color(0xCFE7FF)

    "same nickname has one stable case-insensitive tint" {
        val first = ChatMessageColorizer.colorFor(localBase, "GrocerMC", variation)
        val second = ChatMessageColorizer.colorFor(localBase, "gRoCeRmC", variation)

        first shouldBe second
        ChatMessageColorizer.colorFor(localBase, "Alexey23", variation) shouldNotBe first
    }

    "same nickname uses the same relative hue offset in both channels" {
        val localShift = hueShiftDegrees(localBase, ChatMessageColorizer.colorFor(localBase, "GrocerMC", variation))
        val globalShift = hueShiftDegrees(globalBase, ChatMessageColorizer.colorFor(globalBase, "GrocerMC", variation))

        // RGB quantization is most visible in the very light global palette.
        abs(localShift - globalShift) shouldBeLessThanOrEqual 1.5
        abs(localShift) shouldBeLessThanOrEqual 12.5
    }

    "disabled or zero-amplitude variation returns the exact base color" {
        ChatMessageColorizer.colorFor(localBase, "GrocerMC", ChatMessageColorVariation.DISABLED) shouldBe localBase
        ChatMessageColorizer.colorFor(
            globalBase,
            "GrocerMC",
            ChatMessageColorVariation(enabled = true, hueAmplitudeDegrees = 0.0),
        ) shouldBe globalBase
    }

    "configuration loads the feature switch and clamps unsafe amplitude" {
        val dataPath = Files.createTempDirectory("arc-chat-mode-config-")
        Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            dataPath.resolve("modules/chat-mode.yml"),
            """
            message-color-variation:
              enabled: false
              hue-amplitude-degrees: 90.0
            """.trimIndent(),
        )

        val settings = ChatModeConfig.load(dataPath).messageColorVariation

        settings.enabled shouldBe false
        settings.hueAmplitudeDegrees shouldBe 30.0
        ConfigManager.clear()
    }
})

private fun hueShiftDegrees(
    base: TextColor,
    shifted: TextColor,
): Double {
    val baseHue = HSVLike.fromRGB(base.red(), base.green(), base.blue()).h().toDouble()
    val shiftedHue = HSVLike.fromRGB(shifted.red(), shifted.green(), shifted.blue()).h().toDouble()
    val direct = (shiftedHue - baseHue) * 360.0
    return when {
        direct > 180.0 -> direct - 360.0
        direct < -180.0 -> direct + 360.0
        else -> direct
    }
}
