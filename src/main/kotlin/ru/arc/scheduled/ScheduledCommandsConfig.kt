package ru.arc.scheduled

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.config.ConfigSection
import java.nio.file.Path
import java.time.ZoneId

open class ScheduledCommandsConfig(
    private val config: Config,
) : ScheduledCommandsSettings {
    override val enabled: Boolean
        get() = config.bool("enabled", true)

    override val checkIntervalTicks: Long
        get() = config.durationTicks("check-interval", 60)

    override val timezone: ZoneId
        get() =
            runCatching {
                ZoneId.of(config.string("timezone", "Europe/Moscow"))
            }.getOrDefault(ZoneId.systemDefault())

    override val guiTitle: String
        get() = config.string("gui.title", "<gold>Расписание команд")

    override val detailGuiTitle: String
        get() = config.string("gui.detail-title", "<gold>Команда: <white><id>")

    val permissionGui: String
        get() = config.string("permission.gui", "arc.schedules.gui")

    override val permissionRunNow: String
        get() = config.string("permission.run-now", "arc.schedules.run")

    fun commandIds(): List<String> = config.keys("commands").sorted()

    override fun entry(id: String): ScheduledCommandEntry? {
        if (!config.exists("commands.$id")) return null
        return loadEntry(id, config.section("commands.$id"))
    }

    override fun entries(): List<ScheduledCommandEntry> = commandIds().mapNotNull { entry(it) }

    override fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        config.setBoolean("commands.$id.enabled", enabled)
        config.saveStrict()
    }

    fun isEnabled(id: String): Boolean = config.bool("commands.$id.enabled", true)

    fun saveEntry(draft: ScheduledCommandDraft) {
        val normalizedId = draft.id.trim().lowercase()
        if (draft.originalId != normalizedId) {
            config.removeKey("commands.${draft.originalId}")
        }
        val base = "commands.$normalizedId"
        draft.id = normalizedId
        config.setBoolean("$base.enabled", draft.enabled)
        config.setString("$base.command", draft.command)
        config.setStringList("$base.servers", draft.serverMode.toYaml())

        val sched = "$base.schedule"
        config.removeKey(sched)
        config.setString("$sched.type", draft.scheduleType.configKey)
        when (draft.scheduleType) {
            ScheduleEditorType.INTERVAL -> {
                config.setString("$sched.every", draft.scheduleValue)
                config.setBoolean("$sched.run-on-start", draft.runOnStart)
            }

            ScheduleEditorType.DAILY -> {
                config.setStringList("$sched.times", splitList(draft.scheduleValue))
                config.setStringList("$sched.days", listOf("all"))
            }

            ScheduleEditorType.WEEKLY -> {
                config.setStringList("$sched.times", splitList(draft.scheduleValue))
                config.setStringList("$sched.days", splitList(draft.weeklyDays))
            }

            ScheduleEditorType.CRON -> {
                config.setString("$sched.expression", draft.scheduleValue.trim())
            }
        }
        config.saveStrict()
    }

    fun deleteEntry(id: String): Boolean {
        val normalizedId = id.trim().lowercase()
        val path = "commands.$normalizedId"
        if (!config.exists(path)) return false
        config.removeKey(path)
        config.saveStrict()
        return true
    }

    fun reloadConfig() {
        config.reload()
    }

    /** Worker-only parse. Published readers never share a mutable YAML tree or its locks. */
    internal fun snapshot(): ScheduledCommandsSnapshot {
        val parsed = entries()
        return ScheduledCommandsSnapshot(enabled, checkIntervalTicks, timezone, guiTitle,
            detailGuiTitle, permissionRunNow, parsed, parsed.associateBy { it.id })
    }

    private fun splitList(raw: String): List<String> =
        raw
            .split(',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        fun load(dataPath: Path): ScheduledCommandsConfig =
            ScheduledCommandsConfig(Config(dataPath, ConfigManager.moduleYamlRelative(dataPath, "scheduled-commands.yml")))

        internal fun loadEntry(
            id: String,
            section: ru.arc.config.ConfigSection,
        ): ScheduledCommandEntry {
            val scheduleSection = section.section("schedule")
            return ScheduledCommandEntry(
                id = id,
                enabled = section.boolean("enabled", true),
                command = section.string("command"),
                servers = parseServers(section.stringList("servers", listOf("all"))),
                schedule = ScheduleSpecParser.parse(scheduleSection),
            )
        }

        private fun parseServers(raw: List<String>): Set<String>? {
            if (raw.isEmpty() || raw.any { it.equals("all", ignoreCase = true) }) return null
            return raw.map { it.lowercase() }.toSet()
        }
    }
}

data class ScheduledCommandEntry(
    val id: String,
    val enabled: Boolean,
    val command: String,
    /** null = all servers */
    val servers: Set<String>?,
    val schedule: ScheduleSpec,
) {
    fun runsOn(serverName: String?): Boolean {
        if (servers == null) return true
        if (serverName.isNullOrBlank()) return false
        return servers.contains(serverName.lowercase())
    }

    fun serversLabel(): String =
        when (servers) {
            null -> "все серверы"
            else -> servers.joinToString(", ")
        }
}

class MutableScheduledCommandsSettings(
    override val enabled: Boolean = true,
    override val checkIntervalTicks: Long = 1200L,
    override val timezone: ZoneId = ZoneId.of("Europe/Moscow"),
    override val guiTitle: String = "test",
    override val detailGuiTitle: String = "test <id>",
    override val permissionRunNow: String = "arc.schedules.run",
    private val entriesById: MutableMap<String, ScheduledCommandEntry> = mutableMapOf(),
) : ScheduledCommandsSettings {
    override fun entries(): List<ScheduledCommandEntry> = entriesById.values.sortedBy { it.id }

    override fun entry(id: String): ScheduledCommandEntry? = entriesById[id]

    override fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val current = entriesById[id] ?: return
        entriesById[id] = current.copy(enabled = enabled)
    }

    fun put(entry: ScheduledCommandEntry) {
        entriesById[entry.id] = entry
    }
}

/** Fully parsed immutable view; mutation belongs to the worker-owned configuration candidate. */
internal data class ScheduledCommandsSnapshot(
    override val enabled: Boolean,
    override val checkIntervalTicks: Long,
    override val timezone: ZoneId,
    override val guiTitle: String,
    override val detailGuiTitle: String,
    override val permissionRunNow: String,
    private val ordered: List<ScheduledCommandEntry>,
    private val byId: Map<String, ScheduledCommandEntry>,
) : ScheduledCommandsSettings {
    private val ids = java.util.List.copyOf(ordered.map { it.id }.sortedWith(String.CASE_INSENSITIVE_ORDER))

    /** Worker-prepared index; native tab completion only finds two bounds and returns a view. */
    fun commandIds(prefix: String): List<String> {
        fun bound(upper: Boolean): Int {
            var low = 0
            var high = ids.size
            while (low < high) {
                val middle = (low + high).ushr(1)
                val id = ids[middle]
                val comparison = if (id.startsWith(prefix, ignoreCase = true)) 0 else id.compareTo(prefix, ignoreCase = true)
                if (comparison < 0 || (upper && comparison == 0)) low = middle + 1 else high = middle
            }
            return low
        }
        return ids.subList(bound(false), bound(true))
    }

    override fun entries(): List<ScheduledCommandEntry> = ordered
    override fun entry(id: String): ScheduledCommandEntry? = byId[id]
    override fun setEnabled(id: String, enabled: Boolean): Unit =
        error("Use the asynchronous schedule mutation API")
}
