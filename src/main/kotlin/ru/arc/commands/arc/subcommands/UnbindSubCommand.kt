package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.survival.SoulbindUnbindResult
import ru.arc.survival.SurvivalGameplayModule
import ru.arc.survival.SurvivalSoulbind
import ru.arc.util.TextUtil

/** Administrative compatibility route for removing EliteMobs' native soulbind PDC. */
object UnbindSubCommand : SubCommand {
    override val configKey = "unbind"
    override val defaultName = "unbind"
    override val defaultPermission = SurvivalSoulbind.UNBIND_PERMISSION
    override val defaultDescription = "Снять привязку EliteMobs с предмета в руке"
    override val defaultUsage = "/arc unbind"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = SurvivalGameplayModule.available

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isNotEmpty()) {
            sendUsage(sender)
            return true
        }
        val player = requirePlayer(sender) ?: return true
        when (SurvivalGameplayModule.unbindHeldItem(player)) {
            SoulbindUnbindResult.DISABLED -> player.sendMessage(TextUtil.mm("<red>Функция отвязки сейчас недоступна."))
            SoulbindUnbindResult.DENIED -> player.sendMessage(CommandConfig.noPermission())
            SoulbindUnbindResult.NO_ITEM -> player.sendMessage(CommandConfig.get("unbind.no-item", "<red>В руке нет предмета."))
            SoulbindUnbindResult.NOT_SOULBOUND -> player.sendMessage(CommandConfig.get("unbind.not-soulbound", "<red>Этот предмет не привязан."))
            SoulbindUnbindResult.UNBOUND -> player.sendMessage(CommandConfig.get("unbind.success", "<green>Привязка с предмета снята."))
        }
        return true
    }
}
