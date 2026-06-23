package ru.arc.restart

import java.time.Duration

object RestartDurationFormat {
    fun format(duration: Duration): String {
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        if (totalSeconds == 0L) return "0 сек"

        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val parts = mutableListOf<String>()
        if (hours > 0) parts += "$hours ч"
        if (minutes > 0) parts += "$minutes мин"
        if (seconds > 0 && hours == 0L) parts += "$seconds сек"
        return parts.joinToString(" ")
    }

    fun formatSeconds(seconds: Int): String = format(Duration.ofSeconds(seconds.toLong()))
}
