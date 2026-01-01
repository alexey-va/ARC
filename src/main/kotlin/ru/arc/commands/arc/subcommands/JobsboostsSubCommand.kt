package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.onlinePlayerNames
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.hooks.HookRegistry

/**
 * /arc jobsboosts - управление бустами работ.
 *
 * Использование:
 * - (без аргументов) - открыть GUI для себя
 * - <player> - открыть GUI для игрока
 * - reset <player> - сбросить бусты игрока (требует arc.admin)
 */
object JobsboostsSubCommand : SubCommand {

    override val configKey = "jobsboosts"
    override val defaultName = "jobsboosts"
    override val defaultPermission = "arc.jobsboosts"
    override val defaultDescription = "Управление бустами работ"
    override val defaultUsage = "/arc jobsboosts [player|reset <player>]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val jobsHook = HookRegistry.jobsHook ?: run {
            sender.sendMessage(CommandConfig.hookNotLoaded("JobsHook"))
            return true
        }

        // /arc jobsboosts reset <player>
        if (args.isNotEmpty() && args[0].equals("reset", ignoreCase = true)) {
            if (!sender.hasPermission("arc.admin")) {
                sender.sendMessage(CommandConfig.noPermission())
                return true
            }

            val playerName = args.getOrNull(1) ?: run {
                sender.sendMessage(CommandConfig.jobsboostsSpecifyPlayer())
                return true
            }

            val player = getOnlinePlayer(sender, playerName) ?: return true

            jobsHook.resetBoosts(player)
            sender.sendMessage(CommandConfig.jobsboostsReset(player.name))
            return true
        }

        // Определяем целевого игрока
        val target: Player? = args.getOrNull(0)?.let {
            ARC.plugin.server.getPlayer(it)
        } ?: (sender as? Player)

        if (target == null) {
            sender.sendMessage(CommandConfig.jobsboostsPlayerNotFoundOrConsole())
            return true
        }

        jobsHook.openBoostGui(target)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> {
                val suggestions = mutableListOf<String>()
                if (sender.hasPermission("arc.admin")) {
                    suggestions.add("reset")
                }
                suggestions.addAll(onlinePlayerNames())
                suggestions.tabComplete(args[0])
            }

            2 -> if (args[0].equals("reset", ignoreCase = true)) {
                tabCompletePlayers(args[1])
            } else null

            else -> null
        }
    }
}
