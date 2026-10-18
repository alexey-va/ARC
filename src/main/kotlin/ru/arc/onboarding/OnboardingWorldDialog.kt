package ru.arc.onboarding

import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Home destinations only; the service keeps ownership of progress checks and the existing RTP action. */
internal class OnboardingWorldDialog(private val travel: (Player, String) -> Unit) {
    fun open(player: Player) {
        ArcMenus.openDialog(player, screen(), reopen = { open(player) })
    }

    internal fun screen() = PaperDialogScreen(
        id = "onboarding.world",
        title = text("title"),
        body = listOf(
            PaperDialogBody(text("intro"), WIDTH),
            DialogTables.body(
                rows = listOf(
                    text("vanilla-label") to text("vanilla-description"),
                    text("survival-label") to text("survival-description"),
                ),
                spec = DialogTables.Spec(rowSeparators = true),
                frame = DialogTables.Frame.EPIC,
                width = WIDTH,
                columns = DialogTables.Columns.BALANCED,
            ),
        ),
        buttons = listOf(destination("vanilla"), destination("survival")),
        exitButton = PaperDialogButton(
            id = PaperDialogActionId.of("close"), label = text("close"), width = 200, onClick = {},
        ),
        columns = 2,
    )

    private fun destination(world: String) = PaperDialogButton(
        id = PaperDialogActionId.of(world),
        label = text("$world-label"),
        tooltip = text("$world-tooltip"),
        width = 150,
        closeDialogBeforeAction = true,
        onClick = { context -> travel(context.player, world) },
    )

    private fun text(key: String) = MiniMessage.miniMessage().deserialize(DEFAULT_TEXT.getValue(key))
        .colorIfAbsent(TextColor.color(0xE8DFD2)).decoration(TextDecoration.ITALIC, false)

    companion object {
        private const val WIDTH = 310

        internal val DEFAULT_TEXT = linkedMapOf(
            "title" to "<#92bed8>Мир для дома",
            "intro" to "<#e8dfd2>Выберите мир. Найдём безопасное место.",
            "vanilla-label" to "<#77ee94>Обычный мир",
            "vanilla-description" to "<#e8dfd2>обычное выживание",
            "vanilla-tooltip" to "<#e8dfd2>Найти безопасное место в обычном мире.",
            "survival-label" to "<#6de4f2>Новые биомы",
            "survival-description" to "<#e8dfd2>необычные ландшафты",
            "survival-tooltip" to "<#e8dfd2>Найти безопасное место среди новых биомов.",
            "close" to "<white>Закрыть",
        )
    }
}
