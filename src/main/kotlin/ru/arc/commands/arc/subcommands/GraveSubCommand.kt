package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.graves.GRAVES_ADMIN_PERMISSION
import ru.arc.graves.GravesModule
import ru.arc.util.TextUtil
import java.util.Locale

object GraveSubCommand : SubCommand {
    override val configKey = "grave"
    override val defaultPermission = GRAVES_ADMIN_PERMISSION
    override val defaultDescription = "Создать, изменить или удалить декоративную могилу"
    override val defaultUsage = "/arc grave <create [random]|edit|remove>"
    override val defaultPlayerOnly = true

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        // Metadata can be overridden in config; the service's fixed permission remains authoritative.
        if (!player.hasPermission(GRAVES_ADMIN_PERMISSION)) {
            player.sendMessage(TextUtil.mm("<#ff6b61>Нет доступа к управлению декоративными могилами."))
            return true
        }
        if (!GravesModule.decorativeCommand(player, args.map { it.lowercase(Locale.ROOT) }))
            player.sendMessage(TextUtil.mm("<#f4bd6a>Могилы сейчас недоступны. Попробуйте позже."))
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> {
        if (!sender.hasPermission(GRAVES_ADMIN_PERMISSION)) return emptyList()
        return when {
            args.size == 1 -> listOf("create", "edit", "remove")
            args.size == 2 && args[0].equals("create", true) -> listOf("random")
            else -> emptyList()
        }
    }
}
