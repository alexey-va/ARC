package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.integrations.origin.originService
import ru.arc.origin.api.ArcOriginCommands.CommandId
import ru.arc.util.TextUtil

object AuctionPedestalSubCommand : SubCommand {
    override val configKey = "auctionpedestal"
    override val defaultName = "auctionpedestal"
    override val defaultPermission = "arc.origin.auction.admin"
    override val defaultDescription = "Поставить пьедестал аукциона в Origin"
    override val defaultUsage = "/arc auctionpedestal add"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = originService() != null

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val service = originService()
        if (service == null) {
            sender.sendMessage(TextUtil.mm("<red>Сервисы Origin сейчас недоступны."))
            return true
        }
        return service.execute(CommandId.AUCTION_PEDESTAL, sender, args)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        originService()?.tabComplete(CommandId.AUCTION_PEDESTAL, sender, args)
}
