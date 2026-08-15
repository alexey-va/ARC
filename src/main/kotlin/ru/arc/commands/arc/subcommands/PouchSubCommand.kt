package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.ops.OpsItemHandlers
import ru.arc.treasure.pouch.Pouches
import ru.arc.util.TextUtil

/** Gives YAML-defined multi-pool pouches. Opening is handled by BlockListener. */
object PouchSubCommand : SubCommand {
    override val configKey = "pouch"
    override val defaultName = "pouch"
    override val defaultPermission = "arc.pouch"
    override val defaultDescription = "Выдать большой мешочек с серией наград"
    override val defaultUsage = "/arc pouch <list|player> [pouch] [amount]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
            sendList(sender)
            return true
        }
        if (args.size < 2) {
            sendUsage(sender)
            return true
        }

        val player = getOnlinePlayer(sender, args[0]) ?: return true
        val pouchId = args[1]
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 64) ?: 1
        val definition = Pouches.get(pouchId)
        if (definition == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "pouch.unknown",
                    "<red>Неизвестный мешочек: <white>%pouch%<gray>. Список: <white>/arc pouch list",
                    "%pouch%",
                    pouchId,
                ),
            )
            return true
        }
        val stacks = runCatching {
            List(amount) { Pouches.createStack(definition.id).getOrThrow() }
        }.getOrElse {
            sender.sendMessage(
                CommandConfig.get(
                    "pouch.invalid",
                    "<red>Мешочек <white>%pouch%<red> временно недоступен из-за ошибки настройки.",
                    "%pouch%",
                    definition.id,
                ),
            )
            return true
        }

        val given = OpsItemHandlers.giveStacks(player, stacks, dropOverflow = true)
        sender.sendMessage(
            CommandConfig.get(
                "pouch.success",
                "<green>Выдано <white>%count%<green> мешочков <white>%pouch%<green> игроку <white>%player%",
                "%count%",
                given.toString(),
                "%pouch%",
                definition.id,
                "%player%",
                player.name,
            ),
        )
        return true
    }

    private fun sendList(sender: CommandSender) {
        sender.sendMessage(TextUtil.mm("<gold>Мешочки <gray>/arc pouch"))
        Pouches.all().forEach { definition ->
            val suffix = definition.description?.let { " <dark_gray>— $it" }.orEmpty()
            sender.sendMessage(TextUtil.mm("<gray>• <white>${definition.id}$suffix"))
        }
        sender.sendMessage(TextUtil.mm("<gray>Выдача: <white>/arc pouch <игрок> <мешочек> [кол-во]"))
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> (listOf("list") + tabCompletePlayers(args[0])).distinct().tabComplete(args[0])
            2 -> if (args[0].equals("list", true)) null else Pouches.all().map { it.id }.tabComplete(args[1])
            3 -> listOf("1", "2", "3", "5", "10", "64").tabComplete(args[2])
            else -> null
        }
}
