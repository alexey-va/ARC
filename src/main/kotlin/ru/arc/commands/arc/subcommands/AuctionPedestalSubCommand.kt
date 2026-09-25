package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.origin.OriginSpawnModule

/** `/arc auctionpedestal add` places a durable showcase stand on the targeted block. */
object AuctionPedestalSubCommand : SubCommand {
    override val configKey = "auctionpedestal"
    override val defaultName = "auctionpedestal"
    override val defaultPermission = "arc.origin.auction.admin"
    override val defaultDescription = "Поставить пьедестал аукциона в Origin"
    override val defaultUsage = "/arc auctionpedestal add"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        if (args.size != 1 || !args[0].equals("add", ignoreCase = true)) {
            sendUsage(sender)
            return true
        }

        OriginSpawnModule.addAuctionPedestal(player)?.let { key ->
            sender.sendMessage(OriginSpawnModule.auctionPedestalMessage(key))
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        if (args.size == 1) listOf("add").tabComplete(args[0]) else null
}
