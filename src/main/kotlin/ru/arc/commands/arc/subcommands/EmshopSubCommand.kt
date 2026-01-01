package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.onlinePlayerNames
import ru.arc.commands.arc.tabComplete
import ru.arc.hooks.HookRegistry

/**
 * /arc emshop - управление магазином EliteMobs.
 *
 * Использование:
 * - reset - сбросить магазин
 * - <player> [gear|trinket] - открыть магазин для игрока
 */
object EmshopSubCommand : SubCommand {

    override val configKey = "emshop"
    override val defaultName = "emshop"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Открыть магазин EliteMobs"
    override val defaultUsage = "/arc emshop <player|reset> [gear|trinket]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val emHook = HookRegistry.emHook ?: run {
            sender.sendMessage(CommandConfig.hookNotLoaded("EMHook"))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val firstArg = args[0]

        // /arc emshop reset
        if (firstArg.equals("reset", ignoreCase = true)) {
            emHook.resetShop()
            sender.sendMessage(CommandConfig.emshopReset())
            return true
        }

        // /arc emshop <player> [gear|trinket]
        val player = ARC.plugin.server.getPlayer(firstArg) ?: run {
            sender.sendMessage(CommandConfig.playerNotFound(firstArg))
            return true
        }

        val isGear = args.getOrNull(1)?.lowercase() != "trinket"
        emHook.openShopGui(player, isGear)

        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> (listOf("reset") + onlinePlayerNames()).tabComplete(args[0])
            2 -> listOf("gear", "trinket").tabComplete(args[1])
            else -> null
        }
    }
}
