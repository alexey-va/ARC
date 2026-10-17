package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand

/**
 * /arc reload - Reloads plugin configuration.
 *
 * Shows reload time and what was reloaded.
 */
object ReloadSubCommand : SubCommand {

    override val configKey = "reload"
    override val defaultName = "reload"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Перезагрузить конфигурацию всех модулей плагина"
    override val defaultUsage = "/arc reload"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        sender.sendMessage(CommandConfig.get("reload.starting", "<gray>Перезагрузка конфигурации..."))

        val started = System.nanoTime()
        ARC.instance.whenCompleteOnMain(ARC.instance.reload()) { _, failure ->
            if (failure != null) {
                sender.sendMessage(CommandConfig.get("reload.failed", "<red>Не удалось перезагрузить конфигурацию. Проверьте журнал ARC."))
                return@whenCompleteOnMain
            }
            val timeMs = (System.nanoTime() - started) / 1_000_000L
            sender.sendMessage(
            CommandConfig.get(
                "reload.success-detailed",
                "<gold>✔ <gray>Перезагружено за <white>%time%мс<gray>: конфиги, модули, хуки",
                "%time%", timeMs.toString()
            )
        )
        }

        return true
    }
}
