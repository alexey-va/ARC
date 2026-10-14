package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.integrations.contracts.contractsService
import ru.arc.util.TextUtil

/** Public contract status plus NPC-owned grouped resource menus. */
object ContractsSubCommand : SubCommand {
    override val configKey = "contracts"
    override val defaultName = "contracts"
    override val defaultPermission: String? = null
    override val defaultDescription = "Открыть доску ресурсных контрактов"
    override val defaultUsage =
        "/arc contracts [status|donate <этап> <сумма>|pass <данж>|launch <данж>|trophy <количество>]"
    override val defaultPlayerOnly = false

    override fun isAvailable(): Boolean = contractsService()?.isAvailable == true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val service = contractsService()
        if (service == null) sender.sendMessage(TextUtil.mm("<red>Контракты сейчас недоступны."))
        else service.execute(sender, args)
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        contractsService()?.complete(sender, args)
}
