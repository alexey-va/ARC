package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import kotlin.system.measureTimeMillis

/**
 * /arc reload - Reloads plugin configuration.
 *
 * Shows reload time and what was reloaded.
 */
object ReloadSubCommand : SubCommand {

    override val configKey = "reload"
    override val defaultName = "reload"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Перезагрузить конфигурацию плагина"
    override val defaultUsage = "/arc reload"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        sender.sendMessage(CommandConfig.get("reload.starting", "<gray>Перезагрузка конфигурации..."))

        val timeMs = measureTimeMillis {
            ARC.plugin.reloadConfig()
            ARC.plugin.reload()
        }

        sender.sendMessage(
            CommandConfig.get(
                "reload.success-detailed",
                "<gold>✔ <gray>Перезагружено за <white>%time%мс<gray>: конфиги, модули, хуки",
                "%time%", timeMs.toString()
            )
        )

        return true
    }
}
