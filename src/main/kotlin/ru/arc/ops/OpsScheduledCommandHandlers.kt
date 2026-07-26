package ru.arc.ops

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import ru.arc.scheduled.CronSchedule
import ru.arc.scheduled.DailySchedule
import ru.arc.scheduled.IntervalSchedule
import ru.arc.scheduled.ScheduleEditorType
import ru.arc.scheduled.ScheduledCommandDraft
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandInputValidator
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.scheduled.ServerEditorMode
import ru.arc.scheduled.ValidationResult

/**
 * Structured content-management boundary for ARC scheduled commands.
 *
 * MCP callers exchange a complete JSON definition. ARC validates it through
 * the same model used by the in-game schedule editor before persisting through
 * [ScheduledCommandsManager].
 */
object OpsScheduledCommandHandlers {
    fun list(id: String? = null): Map<String, Any?> {
        val normalizedId = id?.takeIf { it.isNotBlank() }?.let(::validateIdentifier)
        return OpsBukkitSync.call {
            val selected =
                if (normalizedId == null) {
                    ScheduledCommandsManager.entries()
                } else {
                    listOfNotNull(ScheduledCommandsManager.entry(normalizedId))
                }
            if (normalizedId != null && selected.isEmpty()) {
                throw NoSuchElementException("Scheduled command not found: $normalizedId")
            }
            mapOf(
                "source" to "arc-scheduled-commands",
                "timezone" to ScheduledCommandsManager.settings().timezone.id,
                "count" to selected.size,
                "entries" to selected.map(::entryToMap),
            )
        }
    }

    fun preview(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val draft = parseDraft(id, body)
        validate(draft)
        return OpsBukkitSync.call {
            draftToMap(draft) +
                mapOf(
                    "preview" to true,
                    "persisted" to false,
                    "exists" to (ScheduledCommandsManager.entry(draft.id) != null),
                    "source" to "arc-scheduled-commands",
                )
        }
    }

    fun upsert(
        id: String,
        body: JsonObject,
    ): Map<String, Any?> {
        val draft = parseDraft(id, body)
        validate(draft)
        return OpsBukkitSync.call {
            val existed = ScheduledCommandsManager.entry(draft.id) != null
            when (val result = ScheduledCommandsManager.saveEntry(draft)) {
                ValidationResult.Ok -> Unit
                is ValidationResult.Error -> throw IllegalArgumentException(result.message)
            }
            val saved =
                ScheduledCommandsManager.entry(draft.id)
                    ?: throw IllegalStateException("Scheduled command was not available after save: ${draft.id}")
            mapOf(
                "source" to "arc-scheduled-commands",
                "created" to !existed,
                "saved" to true,
                "entry" to entryToMap(saved),
            )
        }
    }

    fun delete(id: String): Map<String, Any?> {
        val normalized = validateIdentifier(id)
        return OpsBukkitSync.call {
            if (!ScheduledCommandsManager.deleteEntry(normalized)) {
                throw NoSuchElementException("Scheduled command not found: $normalized")
            }
            mapOf(
                "source" to "arc-scheduled-commands",
                "deleted" to true,
                "id" to normalized,
            )
        }
    }

    internal fun parseDraft(
        id: String,
        body: JsonObject,
    ): ScheduledCommandDraft {
        val normalizedId = validateIdentifier(id)
        rejectUnknownFields(body, TOP_LEVEL_FIELDS)
        body.get("id")?.takeUnless(JsonElement::isJsonNull)?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "id must be a string" }
            require(it.asString.trim().lowercase() == normalizedId) {
                "body id must match route id"
            }
        }
        val command = requiredString(body, "command", maxLength = 512)
        require(command.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "command must be one line without NUL"
        }
        val schedule =
            body.get("schedule")?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("schedule object is required")
        require(schedule.isJsonObject) { "schedule must be an object" }
        val scheduleObject = schedule.asJsonObject
        val type =
            when (requiredString(scheduleObject, "type", maxLength = 16).lowercase()) {
                "interval" -> ScheduleEditorType.INTERVAL
                "daily" -> ScheduleEditorType.DAILY
                "weekly" -> ScheduleEditorType.WEEKLY
                "cron" -> ScheduleEditorType.CRON
                else -> throw IllegalArgumentException("schedule.type must be interval, daily, weekly, or cron")
            }
        rejectUnknownFields(scheduleObject, scheduleFields(type))

        val (scheduleValue, weeklyDays, runOnStart) =
            when (type) {
                ScheduleEditorType.INTERVAL ->
                    Triple(
                        requiredString(scheduleObject, "every", maxLength = 32),
                        "MONDAY",
                        optionalBoolean(scheduleObject, "runOnStart", false),
                    )

                ScheduleEditorType.DAILY ->
                    Triple(
                        requiredStringList(scheduleObject, "times", maxEntries = 32).joinToString(","),
                        "MONDAY",
                        false,
                    )

                ScheduleEditorType.WEEKLY ->
                    Triple(
                        requiredStringList(scheduleObject, "times", maxEntries = 32).joinToString(","),
                        requiredStringList(scheduleObject, "days", maxEntries = 7).joinToString(","),
                        false,
                    )

                ScheduleEditorType.CRON ->
                    Triple(
                        requiredString(scheduleObject, "expression", maxLength = 96),
                        "MONDAY",
                        false,
                    )
            }

        return ScheduledCommandDraft(
            id = normalizedId,
            originalId = normalizedId,
            enabled = optionalBoolean(body, "enabled", true),
            command = command,
            serverMode = parseServers(body.get("servers")),
            scheduleType = type,
            scheduleValue = scheduleValue,
            weeklyDays = weeklyDays,
            runOnStart = runOnStart,
        )
    }

    private fun validate(draft: ScheduledCommandDraft) {
        // API upsert does not rename: route ID is both the current and target ID.
        // The native manager validates again against live IDs before persistence.
        when (val result = ScheduledCommandInputValidator.validateDraft(draft, emptySet())) {
            ValidationResult.Ok -> Unit
            is ValidationResult.Error -> throw IllegalArgumentException(result.message)
        }
    }

    private fun parseServers(element: JsonElement?): ServerEditorMode {
        if (element == null || element.isJsonNull) return ServerEditorMode.ALL
        val names =
            when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                    listOf(element.asString)
                element.isJsonArray ->
                    element.asJsonArray.mapIndexed { index, value ->
                        require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                            "servers[$index] must be a string"
                        }
                        value.asString
                    }
                else -> throw IllegalArgumentException("servers must be a string or an array")
            }.map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

        val allowed = setOf("all", "spawn", "survival", "both")
        val unsupported = names - allowed
        require(unsupported.isEmpty()) {
            "servers supports only all, both, spawn, and survival; unsupported: ${unsupported.sorted().joinToString()}"
        }
        require("all" !in names || names.size == 1) { "servers value all cannot be combined with other values" }
        require("both" !in names || names.size == 1) { "servers value both cannot be combined with other values" }

        return when {
            names.isEmpty() || names == setOf("all") -> ServerEditorMode.ALL
            names == setOf("spawn") -> ServerEditorMode.SPAWN
            names == setOf("survival") -> ServerEditorMode.SURVIVAL
            names == setOf("spawn", "survival") || names == setOf("both") -> ServerEditorMode.BOTH
            else -> throw IllegalArgumentException("servers supports only all, both, spawn, and survival")
        }
    }

    private fun entryToMap(entry: ScheduledCommandEntry): Map<String, Any?> =
        draftToMap(ScheduledCommandDraft.from(entry)) +
            mapOf("description" to entry.schedule.describe())

    private fun draftToMap(draft: ScheduledCommandDraft): Map<String, Any?> =
        mapOf(
            "id" to draft.id,
            "enabled" to draft.enabled,
            "command" to draft.command,
            "servers" to draft.serverMode.toYaml(),
            "schedule" to scheduleToMap(draft),
        )

    private fun scheduleToMap(draft: ScheduledCommandDraft): Map<String, Any?> =
        when (val schedule = draft.toScheduleSpec()) {
            is IntervalSchedule ->
                mapOf(
                    "type" to "interval",
                    "every" to draft.scheduleValue,
                    "runOnStart" to schedule.runOnStart,
                )
            is DailySchedule ->
                if (schedule.daysOfWeek == null) {
                    mapOf(
                        "type" to "daily",
                        "times" to schedule.times.map { it.toString().take(5) },
                    )
                } else {
                    mapOf(
                        "type" to "weekly",
                        "times" to schedule.times.map { it.toString().take(5) },
                        "days" to schedule.daysOfWeek.sortedBy { it.value }.map { it.name },
                    )
                }
            is CronSchedule ->
                mapOf(
                    "type" to "cron",
                    "expression" to schedule.expression,
                )
        }

    private fun requiredString(
        body: JsonObject,
        field: String,
        maxLength: Int,
    ): String {
        val element =
            body.get(field)?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("$field is required")
        require(element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "$field must be a string"
        }
        val value = element.asString.trim()
        require(value.isNotEmpty()) { "$field must not be empty" }
        require(value.length <= maxLength) { "$field is longer than $maxLength characters" }
        return value
    }

    private fun optionalBoolean(
        body: JsonObject,
        field: String,
        default: Boolean,
    ): Boolean {
        val element = body.get(field)?.takeUnless(JsonElement::isJsonNull) ?: return default
        require(element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
            "$field must be a boolean"
        }
        return element.asBoolean
    }

    private fun requiredStringList(
        body: JsonObject,
        field: String,
        maxEntries: Int,
    ): List<String> {
        val element =
            body.get(field)?.takeUnless(JsonElement::isJsonNull)
                ?: throw IllegalArgumentException("$field is required")
        require(element.isJsonArray) { "$field must be an array of strings" }
        require(element.asJsonArray.size() in 1..maxEntries) {
            "$field must have 1-$maxEntries entries"
        }
        return element.asJsonArray.mapIndexed { index, value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                "$field[$index] must be a string"
            }
            value.asString.trim().also {
                require(it.isNotEmpty()) { "$field[$index] must not be empty" }
                require(it.length <= 64) { "$field[$index] is longer than 64 characters" }
            }
        }
    }

    private fun validateIdentifier(id: String): String {
        val normalized = id.trim().lowercase()
        when (
            val result =
                ScheduledCommandInputValidator.validateId(
                    normalized,
                    emptySet(),
                    normalized,
                )
        ) {
            ValidationResult.Ok -> return normalized
            is ValidationResult.Error -> throw IllegalArgumentException(result.message)
        }
    }

    private fun rejectUnknownFields(
        body: JsonObject,
        allowed: Set<String>,
    ) {
        val unknown = body.keySet() - allowed
        require(unknown.isEmpty()) { "Unknown fields: ${unknown.sorted().joinToString()}" }
    }

    private fun scheduleFields(type: ScheduleEditorType): Set<String> =
        when (type) {
            ScheduleEditorType.INTERVAL -> setOf("type", "every", "runOnStart")
            ScheduleEditorType.DAILY -> setOf("type", "times")
            ScheduleEditorType.WEEKLY -> setOf("type", "times", "days")
            ScheduleEditorType.CRON -> setOf("type", "expression")
        }

    private val TOP_LEVEL_FIELDS = setOf("id", "enabled", "command", "servers", "schedule")
}
