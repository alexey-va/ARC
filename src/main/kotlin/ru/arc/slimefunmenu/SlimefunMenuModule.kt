package ru.arc.slimefunmenu

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.util.TextUtil

/** Small, local navigation surface for the Slimefun backend. */
object SlimefunMenuModule : PluginModule {
    override val name = "SlimefunMenu"
    override val priority = 85

    private var config: Config? = null

    override fun init() = reload()

    override fun reload() {
        config = ConfigManager.of(ARC.instance.dataPath, RESOURCE).also { it.mergeMissingFromBundled(RESOURCE) }
    }

    override fun shutdown() {
        config = null
    }

    fun open(player: Player) {
        if (!player.isOnline || !ARC.instance.isEnabled || ARC.instance.runtimeProfile != ArcRuntimeProfile.SLIMEFUN) return
        if (config == null) {
            player.sendMessage(textOrFallback(null, "unavailable"))
            return
        }
        ArcMenus.beginDialogFlow(player)
        ArcMenus.openDialog(player, screen(), reopen = { open(player) })
    }

    internal fun screen(): PaperDialogScreen = PaperDialogScreen(
        id = "slimefun.player_menu",
        title = text("title"),
        body = listOf(PaperDialogBody(text("intro"), width = 420)),
        buttons = ROUTES.map { route ->
            PaperDialogButton(
                id = PaperDialogActionId.of(route.id),
                label = text("buttons.${route.id}.label"),
                tooltip = text("buttons.${route.id}.tooltip"),
                width = 205,
                closeDialogBeforeAction = true,
                onClick = { context -> runLocalRoute(context.player, route) },
            )
        },
        columns = 2,
    )

    private fun runLocalRoute(player: Player, route: MenuRoute) {
        if (!player.isOnline || !ARC.instance.isEnabled) return
        val provider = Bukkit.getPluginManager().getPlugin(route.providerName)
        val command = ARC.instance.server.commandMap.getCommand(route.namespacedLabel)
        val namespace = route.namespacedLabel.substringBefore(':')
        val ownerMatches = when (command) {
            is PluginCommand -> command.plugin === provider
            null -> false
            else -> provider?.name?.lowercase() == namespace
        }
        if (provider == null || !provider.isEnabled || command == null || !ownerMatches) {
            player.sendMessage(text("unavailable"))
            return
        }

        // Dispatch on this Bukkit server. The provider namespace keeps /rtp and
        // other shared labels from being interpreted by a proxy or another plugin.
        if (!Bukkit.dispatchCommand(player, route.invocation)) {
            player.sendMessage(text("unavailable"))
        }
    }

    private fun text(key: String): Component =
        textOrFallback(config, key)

    private fun textOrFallback(source: Config?, key: String): Component {
        val value = source?.string(key, DEFAULT_TEXT[key] ?: key) ?: DEFAULT_TEXT[key] ?: key
        return TextUtil.mm(value).decoration(TextDecoration.ITALIC, false)
    }

    internal data class MenuRoute(
        val id: String,
        val providerName: String,
        val namespacedLabel: String,
        val arguments: List<String> = emptyList(),
    ) {
        val invocation: String get() = (listOf(namespacedLabel) + arguments).joinToString(" ")
    }

    internal val ROUTES = listOf(
        MenuRoute("guide", "Slimefun", "slimefun:slimefun", listOf("guide")),
        MenuRoute("rtp", "RTP", "rtp:rtp"),
        MenuRoute("homes", "HuskHomes", "huskhomes:homes"),
        MenuRoute("lands", "Lands", "lands:lands"),
        MenuRoute("team", "justTeams", "justteams:team"),
        MenuRoute("shop", "EconomyShopGUI-Premium", "economyshopgui-premium:shop", listOf("slimefun_resources")),
    )

    private val DEFAULT_TEXT = mapOf(
        "title" to "<color:#E6BC76>Навигация Slimefun</color>",
        "intro" to "<color:#E8DFD2>Гайд, исследование мира, дома и общие сервисы сервера.</color>",
        "unavailable" to "<color:#E8DFD2>Сервис временно недоступен. Попробуйте позже.</color>",
        "buttons.guide.label" to "<color:#F1CD8C>Гайд Slimefun</color>",
        "buttons.guide.tooltip" to "<color:#E8DFD2>Открыть руководство по предметам и механизмам.</color>",
        "buttons.rtp.label" to "<color:#F1CD8C>Случайная телепортация</color>",
        "buttons.rtp.tooltip" to "<color:#E8DFD2>Найти безопасное место в мире Slimefun.</color>",
        "buttons.homes.label" to "<color:#F1CD8C>Мои дома</color>",
        "buttons.homes.tooltip" to "<color:#E8DFD2>Посмотреть и выбрать сохранённый дом.</color>",
        "buttons.lands.label" to "<color:#F1CD8C>Мои земли</color>",
        "buttons.lands.tooltip" to "<color:#E8DFD2>Управлять территориями и участками.</color>",
        "buttons.team.label" to "<color:#F1CD8C>Команда</color>",
        "buttons.team.tooltip" to "<color:#E8DFD2>Открыть общее меню команды.</color>",
        "buttons.shop.label" to "<color:#F1CD8C>Магазин ресурсов</color>",
        "buttons.shop.tooltip" to "<color:#E8DFD2>Покупать ресурсы для Slimefun.</color>",
    )

    private const val RESOURCE = "modules/slimefun-menu.yml"
}

object SlimefunMenuCommand : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isNotEmpty()) return false
        val player = sender as? Player ?: run {
            sender.sendMessage("Эта команда доступна только игроку.")
            return true
        }
        SlimefunMenuModule.open(player)
        return true
    }
}

internal class SlimefunMenuAliasCommand : Command("mm") {
    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean =
        SlimefunMenuCommand.onCommand(sender, this, label, args)
}
