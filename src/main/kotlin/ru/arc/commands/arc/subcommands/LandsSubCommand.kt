package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.player
import ru.arc.landsui.LandsUiModule

object LandsSubCommand : SubCommand {
    override val configKey = "lands"
    override val defaultName = "lands"
    override val defaultPermission = "arc.lands.ui.use"
    override val defaultDescription = "Открыть меню поселений"
    override val defaultUsage = "/arc lands"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = LandsUiModule.isAvailable()

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        sender.player?.let(LandsUiModule::open)
        return true
    }
}
