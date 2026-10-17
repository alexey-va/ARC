package ru.arc.hooks.elitemobs

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.dungeons.api.ArcDungeonNavigation
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.config.Config

/** Scoped shortcuts: no native dungeon command executes outside its owning context. */
class EMDungeonCommand(
    private val config: Config,
    private val dispatch: (Player, String, List<String>) -> Unit = { player, action, args ->
        ru.arc.dungeons.ArcDungeonsPlugin.instance.dungeonQol?.action(player, action, args)
            ?: player.sendMessage(config.component("dungeon-qol.messages.unavailable", "<red>Данжи сейчас недоступны.</red>"))
    },
    private val available: () -> Boolean = { Bukkit.getPluginManager().isPluginEnabled("EliteMobs") },
    private val browse: (Player, String) -> Boolean = { player, action ->
        if (action in setOf("tp", "тп", "порталы")) player.performCommand("pw aguild")
        else player.server.servicesManager.load(ArcDungeonNavigation::class.java)
            ?.openGuide(player, Runnable {}) == true
    },
    private val beginFlow: (Player) -> Unit = DungeonsMenus::beginDialogFlow,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(config.component("dungeon-qol.messages.player-only", "<red>Эта команда доступна только игроку.</red>"))
            return true
        }
        val shortcut = when (command.name) {
            "dungeonstart" -> "start"
            "dungeonsave" -> "save"
            "dungeonsaves" -> "saves"
            else -> null
        }
        val action = shortcut ?: args.firstOrNull()?.lowercase() ?: "menu"
        if (!available()) {
            if (action !in BROWSE_ACTIONS || !browse(player, action))
                player.sendMessage(config.component("dungeon-qol.messages.unavailable", "<red>Данжи сейчас недоступны.</red>"))
            return true
        }
        beginFlow(player)
        dispatch(player, action, if (shortcut != null) args.toList() else args.drop(1))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (sender !is Player || command.name != "dungeon" || args.size != 1) return emptyList()
        val actions = if (available()) {
            listOf("меню", "menu", "общее", "список", "статистика", "снаряжение", "навыки", "боссы", "классы", "задания", "телепорты", "начать", "выйти", "вход", "вернуться", "продолжить", "магазины", "сохраниться", "сохранения", "тп", "помощь", "global", "stats", "gear", "skills", "bosses", "classes", "quests", "travel", "start", "quit", "entry", "return", "resume", "shops", "save", "saves", "tp", "list", "help")
        } else BROWSE_ACTIONS.toList()
        return actions.filter { it.startsWith(args[0], ignoreCase = true) }
    }

    private companion object {
        val BROWSE_ACTIONS = setOf("menu", "меню", "tp", "тп", "порталы")
    }
}
