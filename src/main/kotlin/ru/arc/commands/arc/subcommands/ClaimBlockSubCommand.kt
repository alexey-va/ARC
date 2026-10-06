package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.inventory.ItemStack
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.landsui.LandsUiModule
import ru.arc.ops.OpsItemHandlers

object ClaimBlockSubCommand : SubCommand {
    override val configKey = "claimblock"
    override val defaultName = "claimblock"
    override val defaultPermission = "arc.give-claim-block"
    override val defaultDescription = "Выдать блок привата Lands"
    override val defaultUsage = "/arc claimblock give <player> [amount]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val request = parseClaimBlockRequest(args)
        if (request == null) {
            sendUsage(sender)
            return true
        }
        val target = getOnlinePlayer(sender, request.playerName) ?: return true
        val item = LandsUiModule.createClaimBlockItem(target)
        if (item == null) {
            sender.sendMessage(
                CommandConfig.get(
                    "claimblock.unavailable",
                    "<red>Не удалось создать блок привата для <white>%player%<red>.",
                    "%player%",
                    target.name,
                ),
            )
            return true
        }

        val stacks = splitStacks(item, request.amount)
        OpsItemHandlers.giveStacks(target, stacks, dropOverflow = true)
        sender.sendMessage(
            CommandConfig.get(
                "claimblock.given",
                "<green>Выдано <white>%amount%<green> блок(ов) привата игроку <white>%player%.",
                "%amount%",
                request.amount.toString(),
                "%player%",
                target.name,
            ),
        )
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? = when (args.size) {
        1 -> listOf("give").tabComplete(args[0])
        2 -> if (args[0].equals("give", ignoreCase = true)) tabCompletePlayers(args[1]) else null
        3 -> if (args[0].equals("give", ignoreCase = true)) listOf("1", "2", "4", "8", "16").tabComplete(args[2]) else null
        else -> null
    }
}

internal data class ClaimBlockRequest(val playerName: String, val amount: Int)

internal fun parseClaimBlockRequest(args: Array<String>): ClaimBlockRequest? {
    if (args.size !in 2..3 || !args[0].equals("give", ignoreCase = true)) return null
    val playerName = args[1].takeIf(String::isNotBlank) ?: return null
    val amount = when (args.size) {
        2 -> 1
        3 -> args[2].toIntOrNull() ?: return null
        else -> return null
    }
    if (amount !in 1..MAX_CLAIM_BLOCK_AMOUNT) return null
    return ClaimBlockRequest(playerName, amount)
}

internal fun splitStacks(item: ItemStack, amount: Int): List<ItemStack> {
    require(amount in 1..MAX_CLAIM_BLOCK_AMOUNT)
    val maxStackSize = item.type.maxStackSize.coerceAtLeast(1)
    return buildList {
        var remaining = amount
        while (remaining > 0) {
            val stack = item.clone()
            stack.amount = minOf(remaining, maxStackSize)
            add(stack)
            remaining -= stack.amount
        }
    }
}

private const val MAX_CLAIM_BLOCK_AMOUNT = 2_304
