package ru.arc.commands.arc.subcommands

import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabCompletePlayers
import ru.arc.util.TextUtil

object ChargesSubCommand : SubCommand {
    override val configKey = "charges"
    override val defaultName = "charges"
    override val defaultPermission = "arc.reset-charges"
    override val defaultDescription = "Заполнить заряды игрока"
    override val defaultUsage = "/arc charges <player>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size != 1) {
            sendUsage(sender)
            return true
        }
        val target = getOnlinePlayer(sender, args[0]) ?: return true
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ||
            !Bukkit.getPluginManager().isPluginEnabled("CMI")
        ) {
            sender.sendMessage(TextUtil.mm("<red>Сервис зарядов сейчас недоступен."))
            return true
        }

        val action =
            chargeAction(
                PlaceholderAPI.setPlaceholders(target, MAX_CHARGES_PLACEHOLDER),
                PlaceholderAPI.setPlaceholders(target, REMAINING_CHARGES_PLACEHOLDER),
            )
        if (action == null) {
            sender.sendMessage(TextUtil.mm("<red>Не удалось определить число зарядов игрока."))
            return true
        }

        val command = when (action) {
            is ChargesAction.Refill -> "cmi charges ${target.name} add ${action.amount}"
            ChargesAction.AlreadyFull -> fullChargeReturnCommand(target.name)
        }
        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
            sender.sendMessage(TextUtil.mm("<red>Не удалось выполнить операцию с зарядами."))
            return true
        }

        when (action) {
            is ChargesAction.Refill -> {
                target.sendMessage(TextUtil.mm("<green>Заряды полностью восстановлены."))
                sender.sendMessage(
                    CommandConfig.get(
                        "charges.refilled",
                        "<green>Заряды игрока <white>%player%<green> восстановлены.",
                        "%player%",
                        target.name,
                    ),
                )
            }

            ChargesAction.AlreadyFull -> {
                target.sendMessage(
                    CommandConfig.get(
                        "charges.already-full-player",
                        "<gold>Заряды уже полные. Возвращено <white>%amount%<gold>.",
                        "%amount%",
                        FULL_CHARGE_DISPLAY,
                    ),
                )
                sender.sendMessage(
                    CommandConfig.get(
                        "charges.already-full",
                        "<gold>У игрока <white>%player%<gold> уже полные заряды; возвращено <white>%amount%<gold>.",
                        "%player%",
                        target.name,
                        "%amount%",
                        FULL_CHARGE_DISPLAY,
                    ),
                )
            }
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        if (args.size == 1) tabCompletePlayers(args[0]) else null
}

internal sealed interface ChargesAction {
    data class Refill(val amount: Int) : ChargesAction
    data object AlreadyFull : ChargesAction
}

internal fun chargeAction(maxChargesText: String, remainingChargesText: String): ChargesAction? {
    val maxCharges = maxChargesText.trim().toIntOrNull() ?: return null
    val remainingCharges = remainingChargesText.trim().toIntOrNull() ?: return null
    if (maxCharges < 0 || remainingCharges !in 0..maxCharges) return null
    return if (remainingCharges == maxCharges) {
        ChargesAction.AlreadyFull
    } else {
        ChargesAction.Refill(maxCharges - remainingCharges)
    }
}

internal fun fullChargeReturnCommand(playerName: String): String =
    "money $playerName vault give $FULL_CHARGE_REWARD"

private const val MAX_CHARGES_PLACEHOLDER = "%cmi_user_charges_max%"
private const val REMAINING_CHARGES_PLACEHOLDER = "%cmi_user_charges_left%"
private const val FULL_CHARGE_REWARD = 50_000
private const val FULL_CHARGE_DISPLAY = "50 000 <white>💰</white>"
