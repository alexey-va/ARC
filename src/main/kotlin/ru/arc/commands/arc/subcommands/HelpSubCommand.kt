package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.checkPermission
import ru.arc.commands.arc.tabComplete

/**
 * /arc help - показывает список всех доступных субкоманд с описаниями.
 *
 * Клик по команде вставляет её в чат.
 */
object HelpSubCommand : SubCommand {

    override val configKey = "help"
    override val defaultName = "help"
    override val defaultPermission = null // Доступно всем
    override val defaultDescription = "Показать справку по командам"
    override val defaultUsage = "/arc help [команда]"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.isNotEmpty()) {
            // Справка по конкретной команде
            showCommandHelp(sender, args[0])
            return true
        }

        // Список всех команд
        showAllCommands(sender)
        return true
    }

    private fun showAllCommands(sender: CommandSender) {
        sender.sendMessage(Component.empty())
        sender.sendMessage(
            Component.text("═══ ", NamedTextColor.GOLD)
                .append(Component.text("ARC Команды", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" ═══", NamedTextColor.GOLD))
        )
        sender.sendMessage(Component.empty())

        // Получаем все уникальные субкоманды
        val commands = getAvailableCommands(sender)

        commands.sortedBy { it.name }.forEach { cmd ->
            val line = Component.text("  ")
                .append(Component.text("▸ ", NamedTextColor.GOLD))
                .append(
                    Component.text("/arc ${cmd.name}", NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.suggestCommand("/arc ${cmd.name} "))
                        .hoverEvent(
                            HoverEvent.showText(
                                Component.text("Клик для вставки\n", NamedTextColor.GRAY)
                                    .append(Component.text(cmd.usage, NamedTextColor.YELLOW))
                            )
                        )
                )
                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                .append(Component.text(cmd.description.ifEmpty { "Нет описания" }, NamedTextColor.GRAY))

            sender.sendMessage(line)
        }

        sender.sendMessage(Component.empty())
        sender.sendMessage(
            Component.text("  Подробнее: ", NamedTextColor.GRAY)
                .append(
                    Component.text("/arc help <команда>", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/arc help "))
                )
        )
        sender.sendMessage(Component.empty())
    }

    private fun showCommandHelp(sender: CommandSender, commandName: String) {
        val cmd = getAvailableCommands(sender).find {
            it.name.equals(commandName, ignoreCase = true) ||
                it.aliases.any { alias -> alias.equals(commandName, ignoreCase = true) }
        }

        if (cmd == null) {
            sender.sendMessage(CommandConfig.arcUnknownCommand(commandName))
            return
        }

        sender.sendMessage(Component.empty())
        sender.sendMessage(
            Component.text("═══ ", NamedTextColor.GOLD)
                .append(Component.text("/arc ${cmd.name}", NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(" ═══", NamedTextColor.GOLD))
        )
        sender.sendMessage(Component.empty())

        // Описание
        sender.sendMessage(
            Component.text("  Описание: ", NamedTextColor.GRAY)
                .append(Component.text(cmd.description.ifEmpty { "Нет описания" }, NamedTextColor.WHITE))
        )

        // Использование
        sender.sendMessage(
            Component.text("  Синтаксис: ", NamedTextColor.GRAY)
                .append(
                    Component.text(cmd.usage, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand(cmd.usage.split(" ").take(3).joinToString(" ") + " "))
                )
        )

        // Право доступа
        val permission = cmd.permission ?: "нет"
        sender.sendMessage(
            Component.text("  Право: ", NamedTextColor.GRAY)
                .append(Component.text(permission, NamedTextColor.AQUA))
        )

        // Алиасы
        if (cmd.aliases.isNotEmpty()) {
            sender.sendMessage(
                Component.text("  Алиасы: ", NamedTextColor.GRAY)
                    .append(Component.text(cmd.aliases.joinToString(", "), NamedTextColor.WHITE))
            )
        }

        // Только для игрока?
        if (cmd.playerOnly) {
            sender.sendMessage(
                Component.text("  ⚠ ", NamedTextColor.YELLOW)
                    .append(Component.text("Только для игроков", NamedTextColor.GRAY))
            )
        }

        sender.sendMessage(Component.empty())
    }

    /**
     * Returns list of commands available to the sender.
     */
    private fun getAvailableCommands(sender: CommandSender): List<SubCommand> {
        return listOf(
            HelpSubCommand,
            ReloadSubCommand,
            BoardSubCommand,
            BaltopSubCommand,
            AuditSubCommand,
            RepoSubCommand,
            EmshopSubCommand,
            JobsboostsSubCommand,
            LoggerSubCommand,
            JoinMessageSubCommand,
            QuitMessageSubCommand,
            RespawnOnRtpSubCommand,
            LocpoolSubCommand,
            HuntSubCommand,
            TreasuresSubCommand,
            TestSubCommand,
            BuildBookSubCommand,
            EliteLootSubCommand,
            InvestSubCommand,
            StoreSubCommand,
            GiveBoostSubCommand,
            SoundFollowSubCommand
        ).filter { sender.checkPermission(it.permission) }
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> getAvailableCommands(sender).map { it.name }.tabComplete(args[0])
            else -> null
        }
    }
}


