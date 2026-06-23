package ru.arc.restart

import java.time.Duration

data class RestartWarningPlan(
    val titlePoints: List<Int>,
    val chatPoints: Set<Int>,
)

object RestartWarningPlanner {
    /**
     * Returns seconds-before-restart when titles/chat should fire (descending).
     * Initial announcement at [totalSeconds] is handled separately in [RestartService.schedule].
     */
    fun plan(
        delay: Duration,
        configuredSeconds: List<Int>,
        minuteMarks: Boolean,
        finalCountdownSeconds: Int,
    ): RestartWarningPlan {
        val totalSeconds = delay.seconds.coerceAtLeast(1).toInt()
        val titlePoints = linkedSetOf<Int>()
        val chatPoints = linkedSetOf<Int>()

        if (minuteMarks) {
            var minute = totalSeconds / 60
            while (minute >= 1) {
                val seconds = minute * 60
                titlePoints += seconds
                chatPoints += seconds
                minute--
            }
        }

        configuredSeconds.filter { it in 1..totalSeconds }.forEach { seconds ->
            titlePoints += seconds
            chatPoints += seconds
        }

        val countdown = minOf(finalCountdownSeconds.coerceAtLeast(1), totalSeconds)
        for (seconds in countdown downTo 1) {
            titlePoints += seconds
        }

        titlePoints.remove(totalSeconds)

        return RestartWarningPlan(
            titlePoints = titlePoints.sortedDescending(),
            chatPoints = chatPoints,
        )
    }
}
