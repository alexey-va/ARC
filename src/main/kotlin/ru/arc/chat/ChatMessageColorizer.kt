package ru.arc.chat

import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.util.HSVLike
import java.util.Locale

internal object ChatMessageColorizer {
    fun colorFor(
        base: TextColor,
        playerName: String,
        variation: ChatMessageColorVariation,
    ): TextColor {
        val amplitude =
            variation.hueAmplitudeDegrees
                .takeIf(Double::isFinite)
                ?.coerceIn(0.0, ChatModeConfig.MAX_HUE_AMPLITUDE_DEGREES)
                ?: ChatModeConfig.DEFAULT_HUE_AMPLITUDE_DEGREES
        if (!variation.enabled || amplitude == 0.0) return base

        val hsv = HSVLike.fromRGB(base.red(), base.green(), base.blue())
        val shiftedHue = wrapHue(hsv.h().toDouble() + speakerOffset(playerName) * amplitude / 360.0)
        return TextColor.color(HSVLike.hsvLike(shiftedHue.toFloat(), hsv.s(), hsv.v()))
    }

    internal fun speakerOffset(playerName: String): Double {
        var hash = FNV_OFFSET_BASIS
        playerName.lowercase(Locale.ROOT).forEach { character ->
            hash = hash xor character.code
            hash *= FNV_PRIME
        }
        val unit = (hash.toLong() and UNSIGNED_INT_MASK) / UNSIGNED_INT_MAX
        return unit * 2.0 - 1.0
    }

    private fun wrapHue(hue: Double): Double = ((hue % 1.0) + 1.0) % 1.0

    private const val FNV_OFFSET_BASIS = -2_128_831_035
    private const val FNV_PRIME = 16_777_619
    private const val UNSIGNED_INT_MASK = 0xFFFF_FFFFL
    private const val UNSIGNED_INT_MAX = 4_294_967_295.0
}
