package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.ops.ItemPresets
import ru.arc.ops.OpsItemHandlers
import ru.arc.util.TextUtil

/**
 * /arc give — выдать готовый предмет по пресету (лутбоксы, токены, наборы).
 *
 * Использование:
 * - /arc give list
 * - /arc give <player> <preset> [amount]
 *
 * Примеры:
 * - /arc give Steve sf_lootbox 3
 * - /arc give Steve lootbox_bundle
 * - /arc give Steve lootbox_bundle_large 5
 */
object GiveSubCommand : SubCommand {
    override val configKey = "give"
    override val defaultName = "give"
    override val defaultPermission = "arc.give"
    override val defaultDescription = "Выдать предмет по пресету (лутбоксы, токены, наборы)"
    override val defaultUsage = "/arc give <player|list> <preset> [amount]"

    override fun execute(
        sender: CommandSender,
        args: Array<String>,
    ): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        if (args[0].equals("list", ignoreCase = true)) {
            sendPresetList(sender)
            return true
        }

        if (args.size < 2) {
            sendUsage(sender)
            return true
        }

        val player = getOnlinePlayer(sender, args[0]) ?: return true
        val presetName = args[1]
        val amount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 64) ?: 1

        val stacks =
            ItemPresets.resolveStacks(presetName, amount).getOrElse {
                sender.sendMessage(
                    CommandConfig.get(
                        "give.unknown-preset",
                        "<red>Неизвестный пресет: <white>%preset%<gray>. Список: <white>/arc give list",
                        "%preset%",
                        presetName,
                    ),
                )
                sender.sendMessage(
                    CommandConfig.get(
                        "give.hint",
                        "<gray>Примеры: sf_lootbox, ae_lootbox, enchant_token, money_bag, lootbox_bundle",
                    ),
                )
                return true
            }

        val givenCount = OpsItemHandlers.giveStacks(player, stacks, dropOverflow = true)
        sender.sendMessage(
            CommandConfig.get(
                "give.success",
                "<green>Выдано <white>%count%<green> предмет(ов) пресета <white>%preset%<green> игроку <white>%player%",
                "%count%",
                givenCount.toString(),
                "%preset%",
                ItemPresets.normalize(presetName),
                "%player%",
                player.name,
            ),
        )
        return true
    }

    private fun sendPresetList(sender: CommandSender) {
        sender.sendMessage(TextUtil.mm("<gold>Пресеты <gray>/arc give"))
        ItemPresets.allNames().forEach { name ->
            val desc = ItemPresets.describe(name)
            if (desc != null) {
                sender.sendMessage(TextUtil.mm("<gray>• <white>$name <dark_gray>— $desc"))
            } else {
                sender.sendMessage(TextUtil.mm("<gray>• <white>$name"))
            }
        }
        sender.sendMessage(
            TextUtil.mm("<gray>Использование: <white>/arc give <игрок> <preset> [кол-во]"),
        )
    }

    override fun tabComplete(
        sender: CommandSender,
        args: Array<String>,
    ): List<String>? =
        when (args.size) {
            1 -> (listOf("list") + tabCompletePlayers(args[0])).distinct().tabComplete(args[0])
            2 ->
                if (args[0].equals("list", ignoreCase = true)) {
                    null
                } else {
                    ItemPresets.allNames().tabComplete(args[1])
                }
            3 -> listOf("1", "2", "3", "5", "10", "64").tabComplete(args[2])
            else -> null
        }
}
