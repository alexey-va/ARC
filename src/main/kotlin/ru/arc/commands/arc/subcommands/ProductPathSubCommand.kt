package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.metrics.MetricsModule
import ru.arc.metrics.ProductPath

internal class ProductPathChoiceHandler(
    private val recordChoice: (Player, ProductPath) -> Unit,
    private val sendSelected: (CommandSender, ProductPath) -> Unit = ::sendDefaultSelected,
    private val sendInvalid: (CommandSender) -> Unit = ::sendDefaultInvalid,
) {
    fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(CommandConfig.playerOnly())
            return true
        }
        val path = args.singleOrNull()?.lowercase()?.let(PATHS::get)
        if (path == null) {
            sendInvalid(sender)
            return true
        }
        recordChoice(player, path)
        sendSelected(sender, path)
        return true
    }

    fun complete(args: Array<String>): List<String>? =
        if (args.size == 1) PATHS.keys.toList().tabComplete(args[0]) else null

    private companion object {
        fun sendDefaultSelected(sender: CommandSender, path: ProductPath) {
            sender.sendMessage(CommandConfig.get("path.${path.label}", DEFAULT_MESSAGES.getValue(path)))
        }

        fun sendDefaultInvalid(sender: CommandSender) {
            sender.sendMessage(CommandConfig.usage("/arc path <explorer|engineer|settler>"))
        }

        val PATHS =
            linkedMapOf(
                "explorer" to ProductPath.EXPLORER,
                "engineer" to ProductPath.ENGINEER,
                "settler" to ProductPath.SETTLER,
            )
        val DEFAULT_MESSAGES =
            mapOf(
                ProductPath.EXPLORER to
                    "<gold>Путь: <white>Исследователь<gray>. Первый шаг — откройте <white>/quest<gray>. " +
                    "Успех: выбрана первая цель. Если список пуст, вернитесь в <white>/mm<gray>. Обычно 10–15 минут.",
                ProductPath.ENGINEER to
                    "<gold>Путь: <white>Инженер<gray>. Первый шаг — откройте <white>/jobs browse<gray>. " +
                    "Успех: выбрана профессия. Если выбор не открывается, вернитесь в <white>/mm<gray>. Обычно 10–15 минут.",
                ProductPath.SETTLER to
                    "<gold>Путь: <white>Поселенец<gray>. Первый шаг — выполните <white>/rtp<gray>. " +
                    "Успех: вы нашли место для дома. Если перенос не начался, повторите из <white>/mm<gray>. Обычно 10–15 минут.",
            )
    }
}

object ProductPathSubCommand : SubCommand {
    override val configKey = "path"
    override val defaultPermission = "arc.product.path"
    override val defaultDescription = "Выбрать рекомендуемый первый путь"
    override val defaultUsage = "/arc path <explorer|engineer|settler>"
    override val defaultPlayerOnly = true

    private val handler = ProductPathChoiceHandler(MetricsModule::recordProductPathChoice)

    override fun execute(sender: CommandSender, args: Array<String>): Boolean = handler.execute(sender, args)

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? = handler.complete(args)
}
