package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.config.Config

/** Small player-facing alias; EliteMobs remains the owner of dungeon state. */
class EMDungeonCommand(
    private val config: Config,
    private val available: () -> Boolean = {
        Bukkit.getPluginManager().isPluginEnabled("EliteMobs")
    },
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(config.component("dungeon-qol.messages.player-only", "<red>Эта команда доступна только игроку.</red>"))
            return true
        }
        val action = args.firstOrNull()?.lowercase()
        val native = when (action) {
            null, "help", "помощь" -> {
                player.sendMessage(help())
                return true
            }
            "start", "начать" -> "elitemobs:elitemobs start"
            "quit", "leave", "выйти" -> "elitemobs:elitemobs quit"
            else -> {
                player.sendMessage(help())
                return true
            }
        }
        if (!available()) {
            player.sendMessage(config.component("dungeon-qol.messages.unavailable", "<red>Данжи сейчас недоступны.</red>"))
            return true
        }
        player.performCommand(native)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> =
        if (args.size == 1) {
            listOf("start", "quit", "help", "начать", "выйти", "помощь").filter { it.startsWith(args[0], ignoreCase = true) }
        } else emptyList()

    private fun help(): Component = config.component(
        "dungeon-qol.messages.help",
        "<gold>Данжи:</gold> <click:run_command:'/dungeon start'><green>[Начать]</green></click> " +
            "<click:run_command:'/dungeon quit'><white>[Выйти]</white></click>",
    )
}
