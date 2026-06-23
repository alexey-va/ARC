package ru.arc.scheduled

import ru.arc.configs.ConfigSection
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * When a scheduled command should run.
 */
sealed interface ScheduleSpec {
    /** Human-readable summary for GUI / logs. */
    fun describe(): String

    /**
     * Returns a stable fire key if the schedule matches [now] within [grace],
     * or null if this tick should not fire.
     */
    fun matchSlot(
        now: ZonedDateTime,
        grace: Duration,
    ): String?
}

/** Every fixed duration (e.g. 30m, 6h). */
data class IntervalSchedule(
    val every: Duration,
    val runOnStart: Boolean = false,
) : ScheduleSpec {
    override fun describe(): String =
        buildString {
            append("Интервал: каждые ")
            append(formatDuration(every))
            if (runOnStart) append(" (сразу при старте)")
        }

    override fun matchSlot(
        now: ZonedDateTime,
        grace: Duration,
    ): String? {
        val slotMillis = every.toMillis().coerceAtLeast(1L)
        val epoch = now.toInstant().toEpochMilli()
        val slotIndex = epoch / slotMillis
        val slotStart = slotIndex * slotMillis
        val diff = epoch - slotStart
        return if (diff <= grace.toMillis()) slotIndex.toString() else null
    }
}

/** Specific clock times, optionally limited to days of week. */
data class DailySchedule(
    val times: List<LocalTime>,
    val daysOfWeek: Set<DayOfWeek>?,
) : ScheduleSpec {
    override fun describe(): String {
        val timesText = times.sorted().joinToString(", ") { TIME_FORMAT.format(it) }
        val daysText =
            when (daysOfWeek) {
                null -> "каждый день"
                else -> daysOfWeek.sortedBy { it.value }.joinToString(", ") { dayName(it) }
            }
        return "Ежедневно ($daysText): $timesText"
    }

    override fun matchSlot(
        now: ZonedDateTime,
        grace: Duration,
    ): String? {
        if (daysOfWeek != null && now.dayOfWeek !in daysOfWeek) return null
        for (time in times) {
            val scheduled = now.toLocalDate().atTime(time).atZone(now.zone)
            val diffMillis = kotlin.math.abs(Duration.between(scheduled, now).toMillis())
            if (diffMillis <= grace.toMillis()) {
                return "${now.toLocalDate()}_$time"
            }
        }
        return null
    }

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** Five-field cron: minute hour day-of-month month day-of-week (1=Mon … 7=Sun). */
data class CronSchedule(
    val expression: String,
    val minute: CronField,
    val hour: CronField,
    val dayOfMonth: CronField,
    val month: CronField,
    val dayOfWeek: CronField,
) : ScheduleSpec {
    override fun describe(): String = "Cron: $expression"

    override fun matchSlot(
        now: ZonedDateTime,
        grace: Duration,
    ): String? {
        if (!matches(now)) return null
        val minuteStart = now.withSecond(0).withNano(0)
        val diffMillis = kotlin.math.abs(Duration.between(minuteStart, now).toMillis())
        return if (diffMillis <= grace.toMillis()) {
            "${now.toLocalDate()}_${now.hour}_${now.minute}"
        } else {
            null
        }
    }

    private fun matches(now: ZonedDateTime): Boolean =
        minute.matches(now.minute) &&
            hour.matches(now.hour) &&
            dayOfMonth.matches(now.dayOfMonth) &&
            month.matches(now.monthValue) &&
            dayOfWeek.matches(isoDayOfWeek(now.dayOfWeek))
}

class CronField private constructor(
    val raw: String,
    private val matcher: (Int) -> Boolean,
) {
    fun matches(value: Int): Boolean = matcher(value)

    companion object {
        fun parse(
            spec: String,
            min: Int,
            max: Int,
        ): CronField {
            val trimmed = spec.trim()
            if (trimmed == "*") return CronField(trimmed) { true }

            val matcher: (Int) -> Boolean = { value ->
                trimmed.split(',').any { part -> part.trim().matchesPart(value, min, max) }
            }
            return CronField(trimmed, matcher)
        }

        private fun String.matchesPart(
            value: Int,
            min: Int,
            max: Int,
        ): Boolean {
            if (contains('/')) {
                val stepParts = split('/', limit = 2)
                val base = stepParts[0].trim()
                val step = stepParts[1].trim().toIntOrNull() ?: return false
                if (step <= 0) return false
                val baseMatcher =
                    if (base == "*") {
                        { v: Int -> v in min..max }
                    } else {
                        parse(base, min, max).matcher
                    }
                return value in min..max && baseMatcher(value) && (value - min) % step == 0
            }
            if (contains('-')) {
                val rangeParts = split('-', limit = 2)
                val start = rangeParts[0].trim().toIntOrNull() ?: return false
                val end = rangeParts[1].trim().toIntOrNull() ?: return false
                return value in start..end
            }
            return toIntOrNull()?.let { it == value } == true
        }
    }
}

object ScheduleSpecParser {
    fun parse(section: ConfigSection): ScheduleSpec {
        val type = section.string("type", "interval").lowercase(Locale.ROOT)
        return when (type) {
            "interval" -> {
                val every =
                    section.durationOrNull("every")
                        ?: section.durationOrNull("interval")
                        ?: Duration.ofHours(1)
                IntervalSchedule(
                    every = every,
                    runOnStart = section.boolean("run-on-start", false),
                )
            }

            "daily", "weekly" -> {
                val times = parseTimes(section.stringList("times"))
                val days = parseDays(section.stringList("days", listOf("all")))
                DailySchedule(times = times, daysOfWeek = days)
            }

            "cron" -> {
                parseCron(section.string("expression", "* * * * *"))
            }

            else -> {
                throw IllegalArgumentException("Unknown schedule type: $type")
            }
        }
    }

    fun parseCron(expression: String): CronSchedule {
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron expression must have 5 fields: $expression" }
        return CronSchedule(
            expression = expression.trim(),
            minute = CronField.parse(parts[0], 0, 59),
            hour = CronField.parse(parts[1], 0, 23),
            dayOfMonth = CronField.parse(parts[2], 1, 31),
            month = CronField.parse(parts[3], 1, 12),
            dayOfWeek = CronField.parse(parts[4], 1, 7),
        )
    }

    private fun parseTimes(raw: List<String>): List<LocalTime> {
        require(raw.isNotEmpty()) { "Schedule times must not be empty" }
        return raw.map { time ->
            try {
                LocalTime.parse(time.trim(), DateTimeFormatter.ofPattern("H:mm"))
            } catch (_: DateTimeParseException) {
                LocalTime.parse(time.trim(), DateTimeFormatter.ofPattern("HH:mm"))
            }
        }
    }

    private fun parseDays(raw: List<String>): Set<DayOfWeek>? {
        if (raw.isEmpty() || raw.any { it.equals("all", ignoreCase = true) }) return null
        return raw
            .map { day ->
                DayOfWeek.valueOf(day.trim().uppercase(Locale.ROOT))
            }.toSet()
    }
}

private fun isoDayOfWeek(day: DayOfWeek): Int = day.value

private fun dayName(day: DayOfWeek): String =
    when (day) {
        DayOfWeek.MONDAY -> "Пн"
        DayOfWeek.TUESDAY -> "Вт"
        DayOfWeek.WEDNESDAY -> "Ср"
        DayOfWeek.THURSDAY -> "Чт"
        DayOfWeek.FRIDAY -> "Пт"
        DayOfWeek.SATURDAY -> "Сб"
        DayOfWeek.SUNDAY -> "Вс"
    }

private fun formatDuration(duration: Duration): String {
    val seconds = duration.seconds
    return when {
        seconds % 86_400 == 0L -> "${seconds / 86_400}д"
        seconds % 3_600 == 0L -> "${seconds / 3_600}ч"
        seconds % 60 == 0L -> "${seconds / 60}м"
        else -> "${seconds}с"
    }
}
