package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.itemcatalog.ItemsCatalogModule

object ItemsCatalogSubCommand : SubCommand {
    override val configKey = "items"
    override val defaultName = "items"
    override val defaultPermission = "arc.items.catalog.use"
    override val defaultDescription = "Открыть каталог предметов"
    override val defaultUsage = "/arc items"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = ItemsCatalogModule.isAvailable()

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        ItemsCatalogModule.open(player)
        return true
    }
}
