package ru.arc.invsnapshots

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.commands.arc.CommandConfig
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object SnapshotText {
    fun get(key: String, fallback: String, vararg values: Pair<String, String>): Component = CommandConfig.get(
        "invlist.$key", fallback,
        TagResolver.resolver(values.map { (name, value) -> Placeholder.unparsed(name, value) }),
    ).decoration(TextDecoration.ITALIC, false)
}

internal object InventorySnapshotScreens {
    const val PAGE_SIZE = 8
    val SLOT_ORDER: List<Int> = listOf(39, 38, 37, 36, 40) + (9..35) + (0..8)
    private val time = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.of("Europe/Moscow"))
    private val compactTime = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow"))
    fun timestamp(snapshot: InventorySnapshot): String = time.format(snapshot.timestamp)
    fun compactTimestamp(snapshot: InventorySnapshot): String = compactTime.format(snapshot.timestamp)

    fun notice(key: String, fallback: String): PaperDialogScreen = PaperDialogScreen(
        id = "inventory_snapshots",
        title = SnapshotText.get("title", "<white>Сохранённые инвентари"),
        body = listOf(DialogTextLayout.modelBody(SnapshotText.get(key, fallback))),
        buttons = emptyList(),
    )

    fun list(
        history: SnapshotHistory,
        requestedPage: Int,
        select: (InventorySnapshot) -> Unit,
        navigate: (Int) -> Unit,
    ): PaperDialogScreen {
        val pages = maxOf(1, (history.snapshots.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val buttons = history.snapshots.drop(page * PAGE_SIZE).take(PAGE_SIZE).mapIndexed { index, snapshot ->
            PaperDialogButton(
                id = PaperDialogActionId.of("snapshot_$index"),
                label = if (snapshot.pinned) SnapshotText.get("pinned-entry", "<#e6cf91>★ <white><date> · <server> <gray>· <short_id>",
                    "date" to timestamp(snapshot), "server" to snapshot.server, "short_id" to snapshot.id.toString().take(8))
                else SnapshotText.get("entry", "<white><date> · <server> ›", "date" to timestamp(snapshot), "server" to snapshot.server),
                tooltip = SnapshotText.get("entry-tip", "<gray>Причина: <white><cause>\n<gray>ID: <white><id>\n<gray><inventory>",
                    "cause" to snapshot.cause, "id" to snapshot.id.toString(),
                    "inventory" to when {
                        !snapshot.hasInventory -> "В снимке нет инвентаря"
                        !snapshot.exactSlotOrder -> "Предметы после обновления версии · исходные позиции неизвестны"
                        else -> "Открыть исходные слоты"
                    }),
                width = 310,
                closeDialogBeforeAction = true,
                onClick = { select(snapshot) },
            )
        }.toMutableList()
        if (page > 0) buttons += PaperDialogButton(
            PaperDialogActionId.of("previous"),
            SnapshotText.get("previous", "<#92bed8>‹ Предыдущая страница"), width = 310,
            onClick = { navigate(page - 1) },
        )
        if (page + 1 < pages) buttons += PaperDialogButton(
            PaperDialogActionId.of("next"),
            SnapshotText.get("next", "<#92bed8>Следующая страница ›"), width = 310,
            onClick = { navigate(page + 1) },
        )
        return PaperDialogScreen(
            id = "inventory_snapshots",
            title = SnapshotText.get("title", "<white>Сохранённые инвентари"),
            body = listOf(DialogTextLayout.modelBody(SnapshotText.get("list-summary",
                "<gray>Игрок: <white><player>\n<gray>Сохранений: <white><total><gray> · Страница <white><page>/<pages>\n<gray>Время — московское. Новые сохранения сверху.",
                "player" to history.playerName, "total" to history.snapshots.size.toString(),
                "page" to (page + 1).toString(), "pages" to pages.toString()))),
            buttons = buttons,
        )
    }

    private val emptyEquipment = mapOf(
        39 to Material.CHAINMAIL_HELMET,
        38 to Material.CHAINMAIL_CHESTPLATE,
        37 to Material.CHAINMAIL_LEGGINGS,
        36 to Material.CHAINMAIL_BOOTS,
        40 to Material.SHIELD,
    )

    fun emptySlot(slot: Int, storagePlaceholder: () -> ItemStack): ItemStack {
        val material = emptyEquipment[slot] ?: return storagePlaceholder().apply {
            // A menu entry must be non-air. Vanilla's air model keeps empty storage cells visually empty.
            editMeta { it.isHideTooltip = true }
            setData(DataComponentTypes.ITEM_MODEL, Key.key("minecraft:air"))
        }
        return ItemStack.of(material).apply {
            editMeta { meta ->
                meta.displayName(SnapshotText.get("equipment-empty", "<#aab8c0><slot> <dark_gray>· <gray>пусто",
                    "slot" to slotLabel(slot).substringBefore(" (")))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
            }
        }
    }

    fun preview(snapshot: InventorySnapshot, slot: Int, canCopy: Boolean): ItemStack? = snapshot.item(slot)?.also { item ->
        item.editMeta { meta ->
            meta.displayName()?.let { meta.displayName(it.decoration(TextDecoration.ITALIC, false)) }
            val originalLore = meta.lore().orEmpty().map { it.decoration(TextDecoration.ITALIC, false) }
            meta.lore(originalLore + listOf(
                Component.empty().decoration(TextDecoration.ITALIC, false),
                if (snapshot.exactSlotOrder) SnapshotText.get("slot", "<gray>Исходный слот: <white><slot>", "slot" to slotLabel(slot))
                else SnapshotText.get("legacy-slot", "<gray>Исходная позиция неизвестна"),
                Component.empty().decoration(TextDecoration.ITALIC, false),
                if (canCopy) SnapshotText.get("copy-hint", "<green>[▶] ЛКМ <gray>— скопировать стак себе")
                else SnapshotText.get("view-only", "<gray>Только просмотр"),
            ))
        }
    }

    fun slotLabel(slot: Int): String = when (slot) {
        39 -> "Шлем (39)"
        38 -> "Нагрудник (38)"
        37 -> "Поножи (37)"
        36 -> "Ботинки (36)"
        40 -> "Вторая рука (40)"
        41 -> "Снаряжение тела (41)"
        42 -> "Седло (42)"
        in 0..8 -> "Хотбар ${slot + 1} ($slot)"
        else -> "Инвентарь ($slot)"
    }
}
