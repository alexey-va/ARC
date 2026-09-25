package ru.arc.itemlore

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogBody

/** Builds the framed, read-only before/after table shown in the lore preview. */
internal object ItemLorePreview {
    private val plain = PlainTextComponentSerializer.plainText()

    fun table(
        before: List<Component>,
        after: List<Component>,
        text: (String) -> Component,
    ): PaperDialogBody {
        fun cell(rows: List<Component>, index: Int): Component = rows.getOrNull(index)?.let { row ->
            if (plain.serialize(row).isEmpty()) text("preview-blank-row") else row
        } ?: text("preview-missing-row")

        return DialogTables.body(
            rows = (0 until maxOf(before.size, after.size)).map { index ->
                cell(before, index) to cell(after, index)
            },
            headers = text("preview-before") to text("preview-after"),
            frame = DialogTables.Frame.EPIC,
            width = WIDTH,
            columns = DialogTables.Columns.BALANCED,
        )
    }

    private const val WIDTH = 320
}
