package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Location
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class DungeonSaveMenus(
    private val dungeon: EMDungeonQol,
    private val show: (Player, PaperDialogScreen) -> Unit = { player, screen -> ArcMenus.openDialog(player, screen, if (screen.exitButton?.id?.value == "back")
        PaperDialogButton(PaperDialogActionId.of("close"), dungeon.text("saves.dialog.close-label", "Закрыть"), width = 200, closeDialogBeforeAction = true) { } else null) },
) {
    private val nameInput = PaperDialogInputId.of("name")
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

    internal fun open(player: Player, feedback: Component? = null) {
        val view = dungeon.view(player) ?: run {
            show(player, PaperDialogScreen(
                id = "dungeon.saves.unavailable",
                title = text("saves.dialog.title", "Сохранения данжа"),
                body = listOf(PaperDialogBody(text("saves.messages.changed", "<red>Данж или сохранение изменились. Откройте /сохранения заново."), 468)),
                buttons = listOf(action("close", "saves.dialog.close-label", "Закрыть", "saves.dialog.close-tooltip", "Закрыть меню", close = true) { }),
            ))
            return
        }
        val points = view.points.mapIndexed { index, point ->
            pointButton("point_$index", pointLabel(point), pointTooltip(point)) {
                detail(player, point, view)
            }
        }
        val body = mutableListOf(
            PaperDialogBody(text("saves.dialog.body", "Ваши места в этом данже. Сохраняется только позиция. Добыча и монстры не откатываются."), 468),
            PaperDialogBody(text("saves.dialog.limits", "До 5 ручных и 3 автосохранений. Смерть и завершение данжа очищают точки."), 468),
        )
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.saves",
            title = text("saves.dialog.title", "Сохранения данжа"),
            body = body,
            buttons = listOf(
                action("save", "saves.dialog.save-label", "Сохранить здесь", "saves.dialog.save-tooltip", "Сохранить текущую позицию") { saveForm(player, view) },
                view.entry?.let { locationButton(player, "entry", "saves.dialog.entry-label", "К началу данжа", "saves.dialog.entry-tooltip", it, view) },
                view.exit?.let { locationButton(player, "exit", "saves.dialog.exit-label", "Место прошлого выхода", "saves.dialog.exit-tooltip", it, view) },
                action("quit", "saves.dialog.quit-label", "Выйти из данжа", "saves.dialog.quit-tooltip", "Покинуть данж обычным способом", close = true) { dungeon.quit(player) },
            ).filterNotNull() + points,
            exitButton = action("close", "saves.dialog.close-label", "Закрыть", "saves.dialog.close-tooltip", "Закрыть меню", close = true) { },
            columns = 2,
        ))
    }

    private fun saveForm(player: Player, expected: DungeonSaveView, feedback: Component? = null) {
        val body = mutableListOf(PaperDialogBody(text("saves.dialog.save-body", "Введите имя точки. Такое же имя заменит вашу ручную точку."), 468))
        feedback?.let { body += PaperDialogBody(plain(it), 468) }
        show(player, PaperDialogScreen(
            id = "dungeon.saves.save",
            title = text("saves.dialog.save-title", "Сохранить точку"),
            body = body,
            inputs = listOf(PaperDialogTextInput(nameInput, text("saves.dialog.name", "Имя точки"), maxLength = 32, width = 300)),
            buttons = listOf(
                action("save", "saves.dialog.confirm-save-label", "Сохранить", "saves.dialog.confirm-save-tooltip", "Сохранить точку") { context ->
                    val result = dungeon.save(player, context.text(nameInput)?.trim().orEmpty(), expected)
                    if (result.success) open(player, result.message) else saveForm(player, expected, result.message)
                },
            ),
            exitButton = back { open(player) },
            columns = 1,
        ))
    }

    private fun detail(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.point",
            title = plain(Component.text(point.name)),
            body = listOf(PaperDialogBody(pointTooltip(point), 468)),
            buttons = listOf(
                action("travel", "saves.dialog.travel-label", "Перейти", "saves.dialog.travel-tooltip", "Телепортироваться к точке", close = true) { dungeon.travel(player, expected, point.id) },
                action("remove", "saves.dialog.remove-label", "Удалить", "saves.dialog.remove-tooltip", "Удалить эту точку") { confirmRemove(player, point, expected) },
            ),
            exitButton = back { open(player) },
            columns = 2,
        ))
    }

    private fun confirmRemove(player: Player, point: DungeonSavePoint, expected: DungeonSaveView) {
        show(player, PaperDialogScreen(
            id = "dungeon.saves.remove",
            title = text("saves.dialog.remove-title", "Удалить точку?"),
            body = listOf(PaperDialogBody(text("saves.dialog.remove-body", "Точка «<name>» будет удалена.", "name" to plain(Component.text(point.name))), 468)),
            buttons = listOf(
                action("confirm", "saves.dialog.confirm-remove-label", "Удалить", "saves.dialog.confirm-remove-tooltip", "Удалить точку") {
                    val result = dungeon.remove(player, point, expected)
                    open(player, result.message)
                },
            ),
            exitButton = back { detail(player, point, expected) },
            columns = 1,
        ))
    }

    private fun locationButton(player: Player, id: String, labelKey: String, labelFallback: String, tooltipKey: String, location: Location, expected: DungeonSaveView) =
        PaperDialogButton(PaperDialogActionId.of(id), text(labelKey, labelFallback), text(tooltipKey, "Координаты: <coords>", "coords" to locationText(location)), width = 230, closeDialogBeforeAction = true) { dungeon.travel(player, expected, id) }

    private fun pointLabel(point: DungeonSavePoint): Component =
        if (point.kind == DungeonSaveKind.AUTO) text("saves.dialog.auto-label", "Авто · <time>", "name" to plain(Component.text(point.name)), "time" to plain(Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt)))))
        else text("saves.dialog.manual-label", "<name>", "name" to plain(Component.text(point.name)))

    private fun pointTooltip(point: DungeonSavePoint): Component =
        text("saves.dialog.point-tooltip", "<kind> · <time> · <coords>",
            "kind" to text(if (point.kind == DungeonSaveKind.MANUAL) "saves.dialog.manual" else "saves.dialog.auto", if (point.kind == DungeonSaveKind.MANUAL) "ручная" else "авто"),
            "time" to plain(Component.text(timeFormat.format(Instant.ofEpochMilli(point.savedAt)))),
            "coords" to locationText(point.location),
        )

    private fun locationText(location: Location): Component = plain(Component.text("${location.blockX}, ${location.blockY}, ${location.blockZ}"))
    private fun text(key: String, fallback: String, vararg values: Pair<String, Component>): Component = plain(dungeon.text(key, fallback, *values))
    private fun plain(value: Component): Component = value.decoration(TextDecoration.ITALIC, false)
    private fun action(id: String, labelKey: String, labelFallback: String, tooltipKey: String, tooltipFallback: String, close: Boolean = false, onClick: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), text(labelKey, labelFallback), text(tooltipKey, tooltipFallback), width = 230, closeDialogBeforeAction = close, onClick = onClick)
    private fun pointButton(id: String, label: Component, tooltip: Component, onClick: () -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, width = 230, onClick = { onClick() })
    private fun back(action: () -> Unit) = PaperDialogButton(PaperDialogActionId.of("back"), text("saves.dialog.back-label", "‹ Назад"), width = 200, onClick = { action() })
}
