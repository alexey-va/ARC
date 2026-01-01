package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.configs.ConfigManager
import ru.arc.misc.BaltopGui
import ru.arc.util.GuiUtils

/**
 * /arc baltop - Opens the balance top GUI.
 */
object BaltopSubCommand : SubCommand {

    override val configKey = "baltop"
    override val defaultName = "baltop"
    override val defaultPermission = "arc.baltop"
    override val defaultDescription = "Открыть таблицу лидеров по балансу"
    override val defaultUsage = "/arc baltop"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender.player ?: return true
        val config = ConfigManager.of(ARC.plugin.dataFolder.toPath(), "baltop.yml")
        GuiUtils.constructAndShowAsync({ BaltopGui(config, player) }, player)
        return true
    }
}
