package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.invsnapshots.InventorySnapshotInspector
import ru.arc.invsnapshots.SNAPSHOT_VIEW_PERMISSION

object InvlistSubCommand : SubCommand {
    override val configKey = "invlist"
    override val defaultPermission = SNAPSHOT_VIEW_PERMISSION
    override val defaultDescription = "Посмотреть сохранённые инвентари игрока"
    override val defaultUsage = "/arc invlist <игрок> или /invlist <игрок>"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // Configured command metadata cannot relax this administrative boundary.
        if (!sender.hasPermission(SNAPSHOT_VIEW_PERMISSION)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }
        val player = requirePlayer(sender) ?: return true
        if (args.size != 1 || !args[0].matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            sendUsage(sender)
            return true
        }
        InventorySnapshotInspector.open(player, args[0])
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        if (sender.hasPermission(SNAPSHOT_VIEW_PERMISSION) && args.size == 1) tabCompletePlayers(args[0]) else emptyList()
}
