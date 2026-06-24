package ru.arc.scheduled

import ru.arc.config.Config
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException

sealed interface ValidationResult {
    data object Ok : ValidationResult

    data class Error(
        val message: String,
    ) : ValidationResult
}

object ScheduledCommandInputValidator {
    const val CANCEL_INPUT = "exit"

    private val ID_PATTERN = Regex("^[a-z][a-z0-9_-]{0,47}$")
    private val TIME_PATTERN = Regex("""^\d{1,2}:\d{2}$""")

    fun isCancel(input: String): Boolean = input.trim().equals(CANCEL_INPUT, ignoreCase = true)

    fun validate(
        inputId: Int,
        input: String,
        scheduleType: ScheduleEditorType,
        existingIds: Set<String>,
        currentId: String,
    ): ValidationResult {
        if (isCancel(input)) return ValidationResult.Ok

        val trimmed = input.trim()
        if (trimmed.isBlank()) return ValidationResult.Error("Значение не может быть пустым")

        return when (inputId) {
            0 -> validateCommand(trimmed)
            1 -> validateScheduleValue(trimmed, scheduleType)
            2 -> validateWeeklyDays(trimmed)
            3 -> validateId(trimmed, existingIds, currentId)
            else -> ValidationResult.Error("Неизвестное поле")
        }
    }

    fun validateDraft(
        draft: ScheduledCommandDraft,
        existingIds: Set<String>,
    ): ValidationResult {
        validateCommand(draft.command).let { if (it is ValidationResult.Error) return it }
        validateScheduleValue(
            draft.scheduleValue,
            draft.scheduleType,
        ).let { if (it is ValidationResult.Error) return it }
        if (draft.scheduleType == ScheduleEditorType.WEEKLY) {
            validateWeeklyDays(draft.weeklyDays).let { if (it is ValidationResult.Error) return it }
        }
        return validateId(draft.id, existingIds, draft.originalId)
    }

    fun validateCommand(input: String): ValidationResult =
        if (input.isBlank()) {
            ValidationResult.Error("Команда не может быть пустой")
        } else {
            ValidationResult.Ok
        }

    fun validateScheduleValue(
        input: String,
        scheduleType: ScheduleEditorType,
    ): ValidationResult =
        when (scheduleType) {
            ScheduleEditorType.INTERVAL -> validateInterval(input)
            ScheduleEditorType.DAILY, ScheduleEditorType.WEEKLY -> validateTimes(input)
            ScheduleEditorType.CRON -> validateCron(input)
        }

    fun validateWeeklyDays(input: String): ValidationResult {
        val parts =
            input
                .split(',', ';')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ValidationResult.Error("Укажите хотя бы один день (MONDAY, FRIDAY, …)")
        for (part in parts) {
            runCatching { DayOfWeek.valueOf(part) }
                .onFailure {
                    return ValidationResult.Error("Неизвестный день: $part")
                }
        }
        return ValidationResult.Ok
    }

    fun validateId(
        id: String,
        existingIds: Set<String>,
        currentId: String,
    ): ValidationResult {
        val normalized = id.trim().lowercase()
        if (!ID_PATTERN.matches(normalized)) {
            return ValidationResult.Error(
                "ID: латиница, цифры, _, -; начинается с буквы (например: morning_hunt)",
            )
        }
        if (normalized != currentId.lowercase() && normalized in existingIds.map { it.lowercase() }.toSet()) {
            return ValidationResult.Error("Расписание с ID <white>$normalized<red> уже существует")
        }
        return ValidationResult.Ok
    }

    private fun validateInterval(input: String): ValidationResult {
        val duration = Config.parseDuration(input.trim())
        return if (duration == null || duration.isZero || duration.isNegative) {
            ValidationResult.Error("Интервал: 30m, 6h, 1d (минимум 1 секунда)")
        } else {
            ValidationResult.Ok
        }
    }

    private fun validateTimes(input: String): ValidationResult {
        val parts =
            input
                .split(',', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ValidationResult.Error("Укажите время: 09:00 или 09:00,21:00")
        for (part in parts) {
            if (!TIME_PATTERN.matches(part)) {
                return ValidationResult.Error("Неверный формат времени: $part (ожидается HH:mm)")
            }
            try {
                LocalTime.parse(part)
            } catch (_: DateTimeParseException) {
                return ValidationResult.Error("Неверное время: $part")
            }
        }
        return ValidationResult.Ok
    }

    private fun validateCron(input: String): ValidationResult =
        runCatching {
            ScheduleSpecParser.parseCron(input.trim())
            ValidationResult.Ok
        }.getOrElse {
            ValidationResult.Error("Cron: 5 полей, например 0 8 * * *")
        }
}
