package ru.arc.itemlore

import net.kyori.adventure.text.Component
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogBody

/** Lore stays one text block; only the formatting reference uses columns. */
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
                text("color-black") to text("color-dark-gray"),
                text("color-dark-blue") to text("color-blue"),
                text("color-dark-green") to text("color-green"),
                text("color-dark-aqua") to text("color-aqua"),
                text("color-dark-red") to text("color-red"),
                text("color-dark-purple") to text("color-light-purple"),
                text("color-gold") to text("color-yellow"),
                text("color-gray") to text("color-white"),
                text("color-obfuscated") to text("color-bold"),
                text("color-strikethrough") to text("color-underlined"),
                text("color-italic") to text("color-reset"),
                text("color-hex") to text("color-ampersand"),
            ),
            headers = null,
            frame = DialogTables.Frame.EPIC,
            width = WIDTH,
            columns = DialogTables.Columns.BALANCED,
        ),
        DialogTables.framedBody(text("format-example"), DialogTables.Frame.EPIC, WIDTH),
    )

    private const val WIDTH = 320
}
