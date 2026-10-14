package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.integrations.origin.originService
import ru.arc.origin.api.ArcOriginCommands.CommandId
import ru.arc.util.TextUtil

object NpcCycleSubCommand : SubCommand {
    override val configKey = "npccycle"
    override val defaultName = "npccycle"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Запустить настроенный цикл NPC"
    override val defaultUsage = "/arc npccycle <list|status [сцена] [цикл]|цикл|сцена цикл>"

    override fun isAvailable(): Boolean = originService() != null

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val service = originService()
        if (service == null) {
            sender.sendMessage(TextUtil.mm("<red>Сервисы Origin сейчас недоступны."))
            return true
        }
        return service.execute(CommandId.NPC_CYCLE, sender, args)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        originService()?.tabComplete(CommandId.NPC_CYCLE, sender, args)
}
