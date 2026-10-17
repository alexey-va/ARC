package ru.arc.scheduled.guis

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.paper.menu.PaperMenuSession
import ru.arc.scheduled.ScheduleEditorType
import ru.arc.scheduled.ScheduledCommandDraft
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandInputValidator
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.scheduled.ValidationResult
import ru.arc.util.TextUtil
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

class EditScheduledCommandGui private constructor(
    private val player: Player,
    entry: ScheduledCommandEntry,
) : Inputable {
    private val plugin = ARC.instance
    private val nativeWork = plugin.nativeWork
    private val menuGeneration = ArcMenus.current()
    private val draft = ScheduledCommandDraft.from(entry)
    private var lastValidationError = "Некорректное значение"
    private var session: PaperMenuSession? = null
    private var actionPending = false

    fun open() {
        if (!current() || actionPending) return
        val weekly = draft.scheduleType == ScheduleEditorType.WEEKLY
        val valueLabel = if (weekly) "${draft.weeklyDays} @ ${draft.scheduleValue}" else draft.scheduleValue.ifBlank { "не задано" }
        val valueTemplate = when (draft.scheduleType) {
            ScheduleEditorType.CRON -> "scheduled-edit-value-cron"
            ScheduleEditorType.INTERVAL -> "scheduled-edit-value-interval"
            ScheduleEditorType.DAILY, ScheduleEditorType.WEEKLY -> "scheduled-edit-value-time"
        }
        val elements = buildMap {
            put("command", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.SCHEDULED_EDIT,
                "command",
                values("command" to draft.command.ifBlank { "не задана" }),
            )) { startInput(0) })
            put("id", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.SCHEDULED_EDIT,
                "id",
                values("id" to draft.id.ifBlank { "не задан" }),
            )) { startInput(3) })
            put("schedule-value", ArcMenus.entryWithContext(ArcMenus.item(
                valueTemplate,
                PaperMenuItemRenderContext(
                    values = mapOf(
                        "value" to Component.text(valueLabel),
                        "hint" to Component.text(scheduleValuePrompt()),
                    ),
                    flags = if (weekly) setOf("weekly") else emptySet(),
                ),
            )) { click -> startInput(if (weekly && click.event.isShiftClick) 2 else 1) })
            put("schedule-type", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.SCHEDULED_EDIT,
                "schedule-type",
                values("type" to draft.scheduleType.label),
            )) {
                if (!actionPending) {
                    draft.scheduleType = draft.scheduleType.next()
                    open()
                }
            })
            put("servers", ArcMenus.entry(ArcMenus.item(
                ArcMenuSchema.SCHEDULED_EDIT,
                "servers",
                values("servers" to draft.serverMode.label),
            )) {
                if (!actionPending) {
                    draft.serverMode = draft.serverMode.next()
                    open()
                }
            })
            put("enabled", ArcMenus.entry(ArcMenus.item(if (draft.enabled) "scheduled-edit-enabled-on" else "scheduled-edit-enabled-off")) {
                if (!actionPending) {
                    draft.enabled = !draft.enabled
                    open()
                }
            })
            put("back", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.SCHEDULED_EDIT, "back")) {
                ScheduledCommandsGuiFactory.openList(it)
            })
            if (player.hasPermission(guiConfig().string("permission.run-now", "arc.schedules.run"))) {
                put("run-now", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.SCHEDULED_EDIT, "run-now")) { runNow() })
            }
            put("save", ArcMenus.entry(ArcMenus.item(ArcMenuSchema.SCHEDULED_EDIT, "save")) { save() })
        }
        session = ArcMenus.open(
            player,
            ArcMenuSchema.SCHEDULED_EDIT,
            TextUtil.mm(buildTitle(draft.id), true),
            elements = elements,
        )
    }

    private fun startInput(id: Int) {
        if (actionPending || !current()) return
        TitleInput(player, this, id)
        player.closeInventory()
    }

    override fun setParameter(n: Int, s: String) {
        when (n) {
            0 -> draft.command = s.trim()
            1 -> draft.scheduleValue = s.trim()
            2 -> draft.weeklyDays = s.trim().uppercase()
            3 -> draft.id = s.trim().lowercase()
        }
    }

    override fun proceed() = open()

    override fun isCancelInput(input: String, id: Int): Boolean = ScheduledCommandInputValidator.isCancel(input)

    override fun onInputCancel(id: Int) {
        player.sendMessage(TextUtil.mm(guiConfig().string("edit-menu.input-cancelled", "<gray>Ввод отменён"), true))
        open()
    }

    override fun satisfy(input: String, id: Int): Boolean {
        val existingIds = if (id == 3) {
            ScheduledCommandsManager.entry(input.trim().lowercase())?.let { setOf(it.id) }.orEmpty()
        } else emptySet()
        return when (val result = ScheduledCommandInputValidator.validate(
            inputId = id,
            input = input,
            scheduleType = draft.scheduleType,
            existingIds = existingIds,
            currentId = draft.originalId,
        )) {
            ValidationResult.Ok -> true
            is ValidationResult.Error -> {
                lastValidationError = result.message
                false
            }
        }
    }

    override fun denyMessage(input: String, id: Int): Component = TextUtil.mm("<red>$lastValidationError", true)

    override fun startMessage(id: Int): Component = TextUtil.mm(
        when (id) {
            0 -> "<gray>> <green>Введите консольную команду"
            1 -> "<gray>> <green>${scheduleValuePrompt()}"
            2 -> "<gray>> <green>Дни недели через запятую (MONDAY,FRIDAY)"
            else -> "<gray>> <green>Новый ID расписания (латиница, цифры, _-)"
        } + " <dark_gray>(<white>${ScheduledCommandInputValidator.CANCEL_INPUT}<dark_gray> — отмена)",
        true,
    )

    private fun scheduleValuePrompt(): String = when (draft.scheduleType) {
        ScheduleEditorType.INTERVAL -> "Интервал (30m, 6h, 1d)"
        ScheduleEditorType.DAILY -> "Время через запятую (09:00,21:00)"
        ScheduleEditorType.WEEKLY -> "Время через запятую (18:00)"
        ScheduleEditorType.CRON -> "Cron-выражение (0 8 * * *)"
    }

    private fun save() {
        completeAction({ ScheduledCommandsManager.saveEntryAsync(draft) }) { result ->
            when (result) {
                is ValidationResult.Error -> player.sendMessage(TextUtil.mm("<red>${result.message}", true))
                ValidationResult.Ok -> {
                    player.sendMessage(TextUtil.mm(guiConfig().string("edit-menu.saved", "<green>Изменения сохранены"), true))
                    ScheduledCommandsGuiFactory.openList(player)
                }
            }
        }
    }

    private fun runNow() {
        completeAction({ ScheduledCommandsManager.runNowAsync(draft.id) }) { ok ->
            val key = if (ok) "edit-menu.run-now-success" else "edit-menu.run-now-fail"
            val fallback = if (ok) "<green>Команда выполнена" else "<red>Не удалось выполнить"
            player.sendMessage(TextUtil.mm(guiConfig().string(key, fallback), true))
        }
    }

    private fun <T : Any> completeAction(operation: () -> CompletableFuture<T>, acknowledge: (T) -> Unit) {
        val origin = session ?: return
        if (actionPending || !current() || !origin.isOpen) return
        actionPending = true
        val accepted = runCatching(operation).getOrElse { CompletableFuture.failedFuture(it) }
        accepted.whenComplete { _, failure ->
            if (failure != null) plugin.logger.log(Level.WARNING, "Scheduled command action failed for ${draft.originalId}", failure)
        }
        plugin.whenCompleteOnMain(accepted) { result, failure ->
            actionPending = false
            if (!current() || session !== origin || !origin.isOpen) return@whenCompleteOnMain
            if (failure != null || result == null) {
                player.sendMessage(TextUtil.mm(guiConfig().string("edit-menu.run-now-fail", "<red>Не удалось выполнить"), true))
            } else acknowledge(result)
        }
    }

    private fun current(): Boolean = player.isOnline && ARC.plugin === plugin && plugin.isEnabled &&
        plugin.nativeWork === nativeWork && runCatching { ArcMenus.current() === menuGeneration }.getOrDefault(false)

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to Component.text(value) },
    )

    companion object {
        fun open(player: Player, entry: ScheduledCommandEntry) = EditScheduledCommandGui(player, entry).open()

        private fun guiConfig(): Config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/scheduled-commands.yml")

        private fun buildTitle(id: String): String = guiConfig()
            .string("edit-menu.title", "<gold>Расписание: <white><id>")
            .replace("<id>", id)
    }
}
