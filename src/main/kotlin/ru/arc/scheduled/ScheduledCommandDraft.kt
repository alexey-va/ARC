package ru.arc.scheduled

import java.time.DayOfWeek

enum class ScheduleEditorType(
    val configKey: String,
    val label: String,
) {
    INTERVAL("interval", "Интервал"),
    DAILY("daily", "Ежедневно"),
    WEEKLY("weekly", "По дням недели"),
    CRON("cron", "Cron"),
    ;

    fun next(): ScheduleEditorType = entries[(ordinal + 1) % entries.size]
}

enum class ServerEditorMode(
    val label: String,
) {
    ALL("все серверы"),
    SPAWN("spawn"),
    SURVIVAL("survival"),
    BOTH("spawn + survival"),
    ;

    fun next(): ServerEditorMode = entries[(ordinal + 1) % entries.size]

    fun toYaml(): List<String> =
        when (this) {
            ALL -> listOf("all")
            SPAWN -> listOf("spawn")
            SURVIVAL -> listOf("survival")
            BOTH -> listOf("spawn", "survival")
        }

    companion object {
        fun fromServers(servers: Set<String>?): ServerEditorMode =
            when {
                servers == null -> ALL
                servers == setOf("spawn") -> SPAWN
                servers == setOf("survival") -> SURVIVAL
                servers.contains("spawn") && servers.contains("survival") -> BOTH
                else -> ALL
            }
    }
}

/**
 * Mutable state for the schedule editor GUI before persisting to YAML.
 */
data class ScheduledCommandDraft(
    var id: String,
    val originalId: String,
    var enabled: Boolean,
    var command: String,
    var serverMode: ServerEditorMode,
    var scheduleType: ScheduleEditorType,
    /** interval: 30m | daily/weekly times: 09:00,21:00 | cron expression */
    var scheduleValue: String,
    var weeklyDays: String,
    var runOnStart: Boolean,
) {
    fun scheduleSummary(): String =
        when (scheduleType) {
            ScheduleEditorType.INTERVAL -> "каждые $scheduleValue"
            ScheduleEditorType.DAILY -> "ежедневно в $scheduleValue"
            ScheduleEditorType.WEEKLY -> "$weeklyDays в $scheduleValue"
            ScheduleEditorType.CRON -> "cron: $scheduleValue"
        }

    fun toScheduleSpec(): ScheduleSpec =
        when (scheduleType) {
            ScheduleEditorType.INTERVAL -> {
                IntervalSchedule(
                    every = ConfigDurationParser.parseDuration(scheduleValue) ?: java.time.Duration.ofHours(1),
                    runOnStart = runOnStart,
                )
            }

            ScheduleEditorType.DAILY -> {
                DailySchedule(
                    times = parseTimeList(scheduleValue),
                    daysOfWeek = null,
                )
            }

            ScheduleEditorType.WEEKLY -> {
                DailySchedule(
                    times = parseTimeList(scheduleValue),
                    daysOfWeek = parseDayList(weeklyDays),
                )
            }

            ScheduleEditorType.CRON -> {
                ScheduleSpecParser.parseCron(scheduleValue)
            }
        }

    companion object {
        fun from(entry: ScheduledCommandEntry): ScheduledCommandDraft {
            val (type, value, days, runOnStart) = fromSchedule(entry.schedule)
            return ScheduledCommandDraft(
                id = entry.id,
                originalId = entry.id,
                enabled = entry.enabled,
                command = entry.command,
                serverMode = ServerEditorMode.fromServers(entry.servers),
                scheduleType = type,
                scheduleValue = value,
                weeklyDays = days,
                runOnStart = runOnStart,
            )
        }

        private fun fromSchedule(schedule: ScheduleSpec): DraftScheduleParts =
            when (schedule) {
                is IntervalSchedule -> {
                    DraftScheduleParts(
                        ScheduleEditorType.INTERVAL,
                        formatDuration(schedule.every),
                        "MONDAY,FRIDAY",
                        schedule.runOnStart,
                    )
                }

                is DailySchedule -> {
                    val times = schedule.times.joinToString(",") { it.toString().substring(0, 5) }
                    if (schedule.daysOfWeek == null) {
                        DraftScheduleParts(ScheduleEditorType.DAILY, times, "MONDAY,FRIDAY", false)
                    } else {
                        DraftScheduleParts(
                            ScheduleEditorType.WEEKLY,
                            times,
                            schedule.daysOfWeek.joinToString(",") { it.name },
                            false,
                        )
                    }
                }

                is CronSchedule -> {
                    DraftScheduleParts(
                        ScheduleEditorType.CRON,
                        schedule.expression,
                        "MONDAY,FRIDAY",
                        false,
                    )
                }
            }

        private fun parseTimeList(raw: String): List<java.time.LocalTime> =
            raw
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { java.time.LocalTime.parse(it) }

        private fun parseDayList(raw: String): Set<DayOfWeek> =
            raw
                .split(',', ';')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .map { DayOfWeek.valueOf(it) }
                .toSet()

        private fun formatDuration(duration: java.time.Duration): String {
            val seconds = duration.seconds
            return when {
                seconds % 86_400 == 0L -> "${seconds / 86_400}d"
                seconds % 3_600 == 0L -> "${seconds / 3_600}h"
                seconds % 60 == 0L -> "${seconds / 60}m"
                else -> "${seconds}s"
            }
        }
    }

    private data class DraftScheduleParts(
        val type: ScheduleEditorType,
        val value: String,
        val days: String,
        val runOnStart: Boolean,
    )
}

/** Parses duration strings for draft → ScheduleSpec (mirrors Config.parseDuration). */
private object ConfigDurationParser {
    fun parseDuration(value: String): java.time.Duration? =
        ru.arc.config.Config
            .parseDuration(value.trim())
}
