package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Navigation and readable chapters before opening EliteMobs' own inventories. */
internal class HelpCenterDungeonsController(
    private val settings: HelpCenterSettings,
    private val navigation: HelpCenterNavigation,
    private val show: (Player, PaperDialogScreen) -> Unit,
    private val executeInventory: (Player, String) -> Boolean,
    private val execute: (Player, String) -> Boolean,
) {
    private val miniMessage = MiniMessage.miniMessage()

    fun open(player: Player, returnTo: () -> Unit) {
        navigation.visit(player) { open(player, returnTo) }
        show(player, PaperDialogScreen(
            id = "help.dungeons", title = text("dungeons-title"),
            body = listOf(PaperDialogBody(text("dungeons-body"), 468)),
            buttons = listOf(
                portals(player),
                button("dungeons_guide", "dungeons-guide-label", "dungeons-guide-tooltip") { openGuide(player, returnTo) },
                eliteMenu(player),
            ),
            exitButton = back(returnTo), columns = 2,
        ))
    }

    private fun openGuide(player: Player, returnTo: () -> Unit) {
        navigation.visit(player) { openGuide(player, returnTo) }
        show(player, PaperDialogScreen(
            id = "help.dungeons.guide", title = text("dungeons-guide-title"),
            body = listOf(PaperDialogBody(text("dungeons-guide-body"), 468)),
            buttons = TOPICS.map { topic ->
                button("dungeons_guide_$topic", "dungeons-guide-$topic-label", "dungeons-guide-$topic-tooltip") {
                    openTopic(player, topic, returnTo)
                }
            },
            exitButton = back { open(player, returnTo) }, columns = 2,
        ))
    }

    private fun openTopic(player: Player, topic: String, returnTo: () -> Unit) {
        navigation.visit(player) { openTopic(player, topic, returnTo) }
        show(player, PaperDialogScreen(
            id = "help.dungeons.guide.$topic", title = text("dungeons-guide-$topic-label"),
            body = listOf("intro", "mechanics", "commands").map {
                PaperDialogBody(text("dungeons-guide-$topic-$it"), 468)
            },
            buttons = listOf(portals(player), eliteMenu(player)),
            exitButton = back { openGuide(player, returnTo) }, columns = 2,
        ))
    }

    private fun portals(player: Player) = button("dungeons_portals", "dungeons-portals-label", "dungeons-portals-tooltip") {
        execute(player, "pw aguild")
    }.copy(closeDialogBeforeAction = true)

    private fun eliteMenu(player: Player) = button("dungeons_menu", "dungeons-menu-label", "dungeons-menu-tooltip") {
        executeInventory(player, "elitemobs:em")
    }

    private fun back(action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of("back"), text("back-label"), width = 200, onClick = { action() },
    )

    private fun button(id: String, label: String, tooltip: String, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), text(label), text(tooltip), width = 230, onClick = { action() },
    )

    private fun text(key: String): Component = miniMessage.deserialize(settings.text(key)).decoration(TextDecoration.ITALIC, false)

    companion object {
        val TOPICS = listOf("start", "equipment", "combat", "dungeons", "rewards", "guild", "crafting", "quests", "groups", "commands")
    }
}
