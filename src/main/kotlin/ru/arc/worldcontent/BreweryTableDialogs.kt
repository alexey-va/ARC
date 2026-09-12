package ru.arc.worldcontent

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Native food choices for the fixed brewery table in Origin. */
object BreweryTableDialogs {
    internal const val ORIGIN_WORLD = "rc_origin_spawn"
    internal const val TABLE_X = -5.5
    internal const val TABLE_Y = 70.0
    internal const val TABLE_Z = 37.5
    internal const val TABLE_RADIUS = 6.0
    internal const val SCREEN_ID = "brewery.table.order"

    private val titleColor = TextColor.color(0x92BED8)
    private val bodyColor = TextColor.color(0xE8DFD2)
    private val eggColor = TextColor.color(0xF4D87A)
    private val fishColor = TextColor.color(0x92BED8)
    private val steakColor = TextColor.color(0xD98A75)

    internal data class Dish(
        val id: String,
        val label: String,
        val command: String,
        val color: TextColor,
        val tooltip: String,
    )

    internal val dishes = listOf(
        Dish("brewery_egg", "Яичница с травами", "rcbreweryorder egg", eggColor, "Тёплый простой выбор для вечернего стола."),
        Dish("brewery_fish", "Запечённая рыба", "rcbreweryorder fish", fishColor, "Нежное блюдо к спокойному разговору."),
        Dish("brewery_steak", "Стейк с перцем", "rcbreweryorder steak", steakColor, "Сытный вариант для долгого вечера."),
    )

    fun openOrder(player: Player) {
        if (!canOpen(player)) return
        ArcMenus.beginDialogFlow(player)
        showOrder(player)
    }

    private fun showOrder(player: Player) {
        if (canOpen(player)) ArcMenus.openDialog(player, orderScreen(player), reopen = { showOrder(player) })
    }

    internal fun orderScreen(player: Player): PaperDialogScreen = PaperDialogScreen(
        id = SCREEN_ID,
        title = styled("Заказ за столиком", titleColor),
        body = listOf(
            prose("Устройтесь за столиком. Луи подаст выбранное блюдо."),
            prose("ПКМ по стулу — сесть. После подачи нажмите на блюдо, чтобы попробовать."),
        ),
        buttons = dishes.map { dish ->
            PaperDialogButton(
                id = PaperDialogActionId.of(dish.id),
                label = styled(dish.label, dish.color),
                tooltip = component(dish.tooltip),
                width = 320,
                closeDialogBeforeAction = true,
                onClick = { context -> orderIfCurrent(context.player, dish) },
            )
        },
        columns = 1,
    )

    private fun orderIfCurrent(player: Player, dish: Dish) {
        if (canOpen(player)) player.performCommand(dish.command)
    }

    private fun canOpen(player: Player): Boolean {
        if (!player.isOnline) {
            player.sendMessage(component("Меню доступно у столика Луи в Origin."))
            return false
        }
        val location = player.location
        val world = location.world
        val dx = location.x - TABLE_X
        val dy = location.y - TABLE_Y
        val dz = location.z - TABLE_Z
        if (world?.name != ORIGIN_WORLD || dx * dx + dy * dy + dz * dz > TABLE_RADIUS * TABLE_RADIUS) {
            player.sendMessage(component("Меню доступно у столика Луи в Origin."))
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
