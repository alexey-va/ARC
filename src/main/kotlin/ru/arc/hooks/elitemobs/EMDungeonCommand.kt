package ru.arc.hooks.elitemobs

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.Config

/** Scoped shortcuts: no native dungeon command executes outside its owning context. */
class EMDungeonCommand(
    private val config: Config,
    private val dispatch: (Player, String, List<String>) -> Unit = { player, action, args ->
        ARC.hookRegistry?.dungeonQol?.action(player, action, args)
            ?: player.sendMessage(config.component("dungeon-qol.messages.unavailable", "<red>Данжи сейчас недоступны.</red>"))
    },
    private val available: () -> Boolean = { Bukkit.getPluginManager().isPluginEnabled("EliteMobs") },
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(config.component("dungeon-qol.messages.player-only", "<red>Эта команда доступна только игроку.</red>"))
            return true
        }
        if (!available()) {
            player.sendMessage(config.component("dungeon-qol.messages.unavailable", "<red>Данжи сейчас недоступны.</red>"))
            return true
        }
        val shortcut = when (command.name) {
            "dungeonstart" -> "start"
            "dungeonsave" -> "save"
            "dungeonsaves" -> "saves"
            else -> null
        }
        val action = shortcut ?: args.firstOrNull()?.lowercase() ?: "help"
        dispatch(player, action, if (shortcut != null) args.toList() else args.drop(1))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> =
        if (command.name == "dungeon" && args.size == 1) {
            listOf("начать", "выйти", "вход", "сохраниться", "сохранения", "помощь", "start", "quit", "entry", "save", "saves", "help")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        } else emptyList()
}
