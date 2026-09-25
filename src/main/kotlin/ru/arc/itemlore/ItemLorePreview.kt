package ru.arc.itemlore

import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogBody

/** Lore stays one text block; only the short formatting reference uses columns. */
internal object ItemLorePreview {
    fun framed(
        lore: List<Component>,
        text: (String) -> Component,
    ): PaperDialogBody = DialogTables.framedBody(
        content = if (lore.isEmpty()) text("preview-empty") else Component.empty().children(
            lore.flatMapIndexed { index, row ->
                if (index == 0) listOf(row) else listOf(Component.newline(), row)
            },
        ),
        frame = DialogTables.Frame.EPIC,
        width = WIDTH,
    )

    fun editorHelp(instructions: Component, text: (String) -> Component): List<PaperDialogBody> = listOf(
        DialogTables.framedBody(instructions, DialogTables.Frame.EPIC, WIDTH),
        DialogTables.body(
            rows = listOf(
                text("color-green") to text("color-red"),
                text("color-hex") to text("color-bold"),
                text("color-reset") to text("color-ampersand"),
            ),
            headers = null,
            frame = DialogTables.Frame.EPIC,
            width = WIDTH,
            columns = DialogTables.Columns.BALANCED,
        ),
    )

    private const val WIDTH = 320
}
