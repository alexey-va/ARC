package ru.arc.scheduled.guis

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import ru.arc.ARC
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.configs.Config
import ru.arc.configs.ConfigManager
import ru.arc.scheduled.ScheduleEditorType
import ru.arc.scheduled.ScheduledCommandDraft
import ru.arc.scheduled.ScheduledCommandEntry
import ru.arc.scheduled.ScheduledCommandInputValidator
import ru.arc.scheduled.ScheduledCommandsManager
import ru.arc.scheduled.ValidationResult
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil
import ru.arc.util.fromConfig
import ru.arc.util.guiItem

/**
 * Editor GUI for a single scheduled command — same layout pattern as [ru.arc.board.guis.AddBoardGui].
 */
class EditScheduledCommandGui(
    private val player: Player,
    entry: ScheduledCommandEntry,
) : ChestGui(2, TextHolder.deserialize(TextUtil.toLegacy(buildTitle(entry.id)))),
    Inputable {
    private val draft: ScheduledCommandDraft = ScheduledCommandDraft.from(entry)
    private var lastValidationError: String = "Некорректное значение"

    private lateinit var idItem: GuiItem
    private lateinit var commandItem: GuiItem
    private lateinit var scheduleValueItem: GuiItem
    private lateinit var scheduleTypeItem: GuiItem
    private lateinit var serversItem: GuiItem
    private lateinit var enabledItem: GuiItem
    private lateinit var saveItem: GuiItem

    init {
        setupBackground()
        val pane = StaticPane(9, 2)

        idItem = idItem()
        commandItem = commandItem()
        scheduleValueItem = scheduleValueItem()
        scheduleTypeItem = scheduleTypeItem()
        serversItem = serversItem()
        enabledItem = enabledItem()
        saveItem = saveItem()

        pane.addItem(commandItem, 1, 0)
        pane.addItem(idItem, 2, 0)
        pane.addItem(scheduleValueItem, 3, 0)
        pane.addItem(scheduleTypeItem, 4, 0)
        pane.addItem(serversItem, 5, 0)
        pane.addItem(enabledItem, 7, 0)
        pane.addItem(backItem(), 0, 1)
        if (player.hasPermission(guiConfig().string("permission.run-now", "arc.schedules.run"))) {
            pane.addItem(runNowItem(), 4, 1)
        }
        pane.addItem(saveItem, 8, 1)

        addPane(Slot.fromXY(0, 0), pane)
    }

    override fun setParameter(
        n: Int,
        s: String,
    ) {
        when (n) {
            0 -> draft.command = s.trim()
            1 -> draft.scheduleValue = s.trim()
            2 -> draft.weeklyDays = s.trim().uppercase()
            3 -> draft.id = s.trim().lowercase()
        }
    }

    override fun proceed() {
        idItem.setItem(idItem().item)
        commandItem.setItem(commandItem().item)
        scheduleValueItem.setItem(scheduleValueItem().item)
        update()
        show(player)
    }

    override fun isCancelInput(
        input: String,
        id: Int,
    ): Boolean = ScheduledCommandInputValidator.isCancel(input)

    override fun onInputCancel(id: Int) {
        player.sendMessage(
            TextUtil.mm(guiConfig().string("edit-menu.input-cancelled", "<gray>Ввод отменён"), true),
        )
        show(player)
    }

    override fun satisfy(
        input: String,
        id: Int,
    ): Boolean {
        val existingIds =
            ScheduledCommandsManager.settings().let { settings ->
                settings.entries().map { it.id }.toSet()
            }
        return when (
            val result =
                ScheduledCommandInputValidator.validate(
                    inputId = id,
                    input = input,
                    scheduleType = draft.scheduleType,
                    existingIds = existingIds,
                    currentId = draft.originalId,
                )
        ) {
            is ValidationResult.Ok -> {
                true
            }

            is ValidationResult.Error -> {
                lastValidationError = result.message
                false
            }
        }
    }

    override fun denyMessage(
        input: String,
        id: Int,
    ): Component = TextUtil.mm("<red>$lastValidationError", true)

    override fun startMessage(id: Int): Component =
        TextUtil.mm(
            when (id) {
                0 -> "<gray>> <green>Введите консольную команду"
                1 -> "<gray>> <green>${scheduleValuePrompt()}"
                2 -> "<gray>> <green>Дни недели через запятую (MONDAY,FRIDAY)"
                else -> "<gray>> <green>Новый ID расписания (латиница, цифры, _-)"
            } + cancelHint(),
            true,
        )

    private fun cancelHint(): String = " <dark_gray>(<white>${ScheduledCommandInputValidator.CANCEL_INPUT}<dark_gray> — отмена)"

    private fun scheduleValuePrompt(): String =
        when (draft.scheduleType) {
            ScheduleEditorType.INTERVAL -> "Интервал (30m, 6h, 1d)"
            ScheduleEditorType.DAILY -> "Время через запятую (09:00,21:00)"
            ScheduleEditorType.WEEKLY -> "Время через запятую (18:00)"
            ScheduleEditorType.CRON -> "Cron-выражение (0 8 * * *)"
        }

    private fun idItem(): GuiItem {
        val resolver =
            TagResolver.resolver("id", Tag.inserting(Component.text(draft.id.ifBlank { "не задан" })))
        return guiItem(Material.NAME_TAG) {
            onClick { click ->
                click.isCancelled = true
                TitleInput(player, this@EditScheduledCommandGui, 3)
                click.whoClicked.closeInventory()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(resolver)
            display("<green>ID расписания")
            lore(
                listOf(
                    "<white><id>",
                    "",
                    "<gray>Нажмите, чтобы переименовать",
                ),
            )
            fromConfig(guiConfig(), "edit-menu.id")
        }
    }

    private fun commandItem(): GuiItem {
        val resolver =
            TagResolver.resolver(
                "command",
                Tag.inserting(Component.text(draft.command.ifBlank { "не задана" })),
            )
        return guiItem(Material.COMMAND_BLOCK) {
            onClick { click ->
                click.isCancelled = true
                TitleInput(player, this@EditScheduledCommandGui, 0)
                click.whoClicked.closeInventory()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(resolver)
            display("<green>Команда")
            lore(
                listOf(
                    "<white><command>",
                    "",
                    "<gray>Нажмите, чтобы изменить",
                ),
            )
            fromConfig(guiConfig(), "edit-menu.command")
        }
    }

    private fun scheduleTypeItem(): GuiItem =
        guiItem(Material.REPEATER) {
            onClick { click ->
                click.isCancelled = true
                draft.scheduleType = draft.scheduleType.next()
                scheduleTypeItem.setItem(scheduleTypeItem().item)
                scheduleValueItem.setItem(scheduleValueItem().item)
                update()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            display("<green>Тип расписания")
            lore(
                listOf(
                    "<yellow>${draft.scheduleType.label}",
                    "",
                    "<gray>Нажмите, чтобы сменить",
                ),
            )
            fromConfig(guiConfig(), "edit-menu.schedule-type")
        }

    private fun scheduleValueItem(): GuiItem {
        val material =
            when (draft.scheduleType) {
                ScheduleEditorType.CRON -> Material.CLOCK
                ScheduleEditorType.INTERVAL -> Material.HOPPER
                else -> Material.PAPER
            }
        val valueLabel =
            when (draft.scheduleType) {
                ScheduleEditorType.WEEKLY -> "${draft.weeklyDays} @ ${draft.scheduleValue}"
                else -> draft.scheduleValue.ifBlank { "не задано" }
            }
        val resolver = TagResolver.resolver("value", Tag.inserting(Component.text(valueLabel)))
        return guiItem(material) {
            onClick { click ->
                click.isCancelled = true
                val inputId =
                    when {
                        draft.scheduleType == ScheduleEditorType.WEEKLY && click.isShiftClick -> 2
                        else -> 1
                    }
                TitleInput(player, this@EditScheduledCommandGui, inputId)
                click.whoClicked.closeInventory()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            tagResolver(resolver)
            display("<green>Расписание")
            lore(
                listOf(
                    "<white><value>",
                    "",
                    "<gray>ЛКМ — изменить значение",
                    if (draft.scheduleType == ScheduleEditorType.WEEKLY) {
                        "<gray>Shift+ЛКМ — дни недели"
                    } else {
                        "<dark_gray>${scheduleValuePrompt()}"
                    },
                ),
            )
            fromConfig(guiConfig(), "edit-menu.schedule-value")
        }
    }

    private fun serversItem(): GuiItem =
        guiItem(Material.COMPASS) {
            onClick { click ->
                click.isCancelled = true
                draft.serverMode = draft.serverMode.next()
                serversItem.setItem(serversItem().item)
                update()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            display("<green>Серверы")
            lore(
                listOf(
                    "<yellow>${draft.serverMode.label}",
                    "",
                    "<gray>Нажмите, чтобы сменить",
                ),
            )
            fromConfig(guiConfig(), "edit-menu.servers")
        }

    private fun enabledItem(): GuiItem {
        val material = if (draft.enabled) Material.LIME_DYE else Material.GRAY_DYE
        return guiItem(material) {
            onClick { click ->
                click.isCancelled = true
                draft.enabled = !draft.enabled
                enabledItem.setItem(enabledItem().item)
                update()
            }
            flags(ItemFlag.HIDE_ATTRIBUTES)
            display(if (draft.enabled) "<green>Включено" else "<red>Выключено")
            lore(listOf("<gray>Нажмите, чтобы переключить"))
            fromConfig(guiConfig(), "edit-menu.enabled")
        }
    }

    private fun saveItem(): GuiItem =
        guiItem(Material.GREEN_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                when (val result = ScheduledCommandsManager.saveEntry(draft)) {
                    is ValidationResult.Error -> {
                        player.sendMessage(TextUtil.mm("<red>${result.message}", true))
                        return@onClick
                    }

                    ValidationResult.Ok -> {
                        player.sendMessage(
                            TextUtil.mm(guiConfig().string("edit-menu.saved", "<green>Изменения сохранены"), true),
                        )
                        GuiUtils.constructAndShowAsync(
                            { ScheduledCommandsGuiFactory.buildListGui(player) },
                            click.whoClicked,
                        )
                    }
                }
            }
            modelData(11007)
            display("<green>Сохранить")
            lore(listOf("<gray>Записать в scheduled-commands.yml"))
            fromConfig(guiConfig(), "edit-menu.save")
        }

    private fun runNowItem(): GuiItem =
        guiItem(Material.REDSTONE_BLOCK) {
            onClick { click ->
                click.isCancelled = true
                val ok = ScheduledCommandsManager.runNow(draft.id)
                val msg =
                    if (ok) {
                        guiConfig().string("edit-menu.run-now-success", "<green>Команда выполнена")
                    } else {
                        guiConfig().string("edit-menu.run-now-fail", "<red>Не удалось выполнить")
                    }
                player.sendMessage(TextUtil.mm(msg, true))
            }
            display("<gold>Запустить сейчас")
            lore(listOf("<gray>Выполнить без ожидания расписания"))
            fromConfig(guiConfig(), "edit-menu.run-now")
        }

    private fun backItem(): GuiItem =
        guiItem(Material.BLUE_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ ScheduledCommandsGuiFactory.buildListGui(player) }, click.whoClicked)
            }
            display("<gray>« Назад")
            modelData(11013)
            fromConfig(guiConfig(), "list-menu.back")
        }

    private fun setupBackground() {
        val pane = OutlinePane(9, 2, Pane.Priority.LOWEST)
        pane.addItem(GuiUtils.background())
        pane.setRepeat(true)
        addPane(Slot.fromXY(0, 0), pane)
    }

    companion object {
        private fun guiConfig(): Config = ConfigManager.of(ARC.instance.dataFolder.toPath(), "guis/scheduled-commands.yml")

        private fun buildTitle(id: String): String =
            guiConfig()
                .string("edit-menu.title", "<gold>Расписание: <white><id>")
                .replace("<id>", id)
    }
}
