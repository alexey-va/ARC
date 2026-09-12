package ru.arc.worldcontent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Native read-only furniture guide and fixed-room gallery entry points. */
object FurnitureDialogs {
    internal const val ORIGIN_WORLD = "rc_origin_spawn"
    internal const val GALLERY_WORLD = "rc_atelier_furniture_gallery"

    private val titleColor = TextColor.color(0x92BED8)
    private val bodyColor = TextColor.color(0xE8DFD2)
    private val actionColor = TextColor.color(0x92BED8)

    internal data class GalleryRoom(
        val id: String,
        val label: String,
        val count: Int,
    )

    // Reviewed against origin_furniture_gallery.dsc; commands stay fixed so
    // no dialog value can become a player-command argument.
    internal val rooms = listOf(
        GalleryRoom("room_01", "FurniturePlus · студия", 86),
        GalleryRoom("room_02", "Королевский салон · школа", 86),
        GalleryRoom("room_03", "Пути · реликвии", 86),
        GalleryRoom("room_04", "Сад · ресторан · зверята", 86),
        GalleryRoom("room_05", "Itemshop · подземелье", 86),
        GalleryRoom("room_06", "Япония · Китай · алхимия", 86),
        GalleryRoom("room_07", "Парк · сезоны · зелья", 86),
        GalleryRoom("room_08", "Япония · Египет · подземелья", 86),
        GalleryRoom("room_09", "Рынок · ферма · кузница", 86),
    )

    private val visitCommands = rooms.associate { it.id to "rcfurniturevisit ${it.id}" }

    fun openGuide(player: Player) {
        if (!canOpen(player)) return
        ArcMenus.beginDialogFlow(player)
        showGuide(player)
    }

    fun openGallery(player: Player) {
        if (!canOpen(player)) return
        ArcMenus.beginDialogFlow(player)
        showGallery(player)
    }

    internal fun guideScreen(player: Player): PaperDialogScreen = PaperDialogScreen(
        id = "furniture.guide",
        title = styled("Памятка по мебели", titleColor),
        body = listOf(
            prose("Лея поможет обставить дом. Начните с этих пяти правил."),
            DialogTables.body(
                rows = listOf(
                    component("Установить") to component("ПКМ по поверхности с мебелью в руке."),
                    component("Убрать") to component("ЛКМ по модели мебели."),
                    component("Предел") to component("До 20 предметов в одном чанке."),
                    component("Приват") to component("Работайте только в своём привате."),
                    component("Границы") to component("F3+G показывает границы чанков."),
                ),
                frame = DialogTables.Frame.EPIC,
                width = 320,
                columns = DialogTables.Columns.LABEL_WIDE,
            ),
        ),
        buttons = listOf(
            PaperDialogButton(
                id = PaperDialogActionId.of("gallery"),
                label = styled("Выставка мебели ›", actionColor),
                tooltip = component("Открыть девять залов с мебелью."),
                onClick = { showGallery(player) },
            ),
        ),
        columns = 1,
    )

    internal fun galleryScreen(player: Player): PaperDialogScreen = PaperDialogScreen(
        id = "furniture.gallery",
        title = styled("Выставка мебели", titleColor),
        body = listOf(
            prose("Выберите зал для перехода. На выставке собраны 774 модели мебели."),
            prose("Портал в конце зала вернёт вас в Origin."),
        ),
        buttons = rooms.map { room ->
            PaperDialogButton(
                id = PaperDialogActionId.of(room.id.replace('-', '_')),
                label = styled("${room.label} ›", actionColor),
                tooltip = component("Перейти в зал · ${room.count} моделей."),
                width = 320,
                closeDialogBeforeAction = true,
                onClick = { context -> visitIfCurrent(context.player, room.id) },
            )
        },
        columns = 1,
    )

    private fun showGuide(player: Player) {
        if (canOpen(player)) ArcMenus.openDialog(player, guideScreen(player), reopen = { showGuide(player) })
    }

    private fun showGallery(player: Player) {
        if (canOpen(player)) ArcMenus.openDialog(player, galleryScreen(player), reopen = { showGallery(player) })
    }

    private fun visitIfCurrent(player: Player, roomId: String) {
        if (!canOpen(player)) return
        val command = visitCommands[roomId] ?: return
        if (!player.performCommand(command)) {
            player.sendMessage(component("Выставка временно недоступна."))
        }
    }

    private fun canOpen(player: Player): Boolean {
        if (!player.isOnline || player.world.name !in setOf(ORIGIN_WORLD, GALLERY_WORLD)) {
            player.sendMessage(component("Памятка и выставка доступны в Origin и в мебельной галерее."))
            return false
        }
        return true
    }

    private fun prose(text: String): PaperDialogBody = PaperDialogBody(component(text), width = 320)

    private fun styled(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun component(text: String): Component =
        Component.text(text, bodyColor).decoration(TextDecoration.ITALIC, false)
}
