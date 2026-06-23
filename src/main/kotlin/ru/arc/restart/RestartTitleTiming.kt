package ru.arc.restart

data class TitleTiming(
    val fadeIn: Int,
    val stay: Int,
    val fadeOut: Int,
)

object RestartTitleTiming {
    fun forSecondsRemaining(
        secondsRemaining: Int,
        finalCountdownSeconds: Int,
        normal: TitleTiming,
        finalMinute: TitleTiming,
    ): TitleTiming =
        if (secondsRemaining <= finalCountdownSeconds) {
            finalMinute
        } else {
            normal
        }
}
