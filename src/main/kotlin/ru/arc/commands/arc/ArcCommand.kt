package ru.arc.commands.arc

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import ru.arc.commands.arc.subcommands.*

/**
 * Main /arc command that routes to subcommands.
 * All subcommand logic is delegated to individual [SubCommand] implementations.
 */
class ArcCommand : CommandExecutor, TabCompleter {

    private val subcommands = mutableMapOf<String, SubCommand>()

    // Legacy command is no longer needed - all subcommands are now in Kotlin

    init {
        // Register all subcommands
        register(
            HelpSubCommand, // Help first for discoverability
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
            // New subcommands
            TestSubCommand,
            BuildBookSubCommand,
            EliteLootSubCommand,
            InvestSubCommand,
            StoreSubCommand,
            GiveBoostSubCommand,
            SoundFollowSubCommand
        )
    }

    private fun register(vararg commands: SubCommand) {
        for (cmd in commands) {
            subcommands[cmd.name.lowercase()] = cmd
            cmd.aliases.forEach { alias ->
                subcommands[alias.lowercase()] = cmd
            }
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(CommandConfig.arcUsage())
            sender.sendMessage(
                CommandConfig.get(
                    "arc.help-hint",
                    "<gray>Справка: <white>/arc help"
                )
            )
            return true
        }

        val subName = args[0].lowercase()
        val subCommand = subcommands[subName]

        if (subCommand == null) {
            sender.sendMessage(CommandConfig.arcUnknownCommand(subName))
            sender.sendMessage(CommandConfig.arcAvailable(getAvailableSubcommandNames().sorted().joinToString(", ")))
            return true
        }

        // Check permission
        if (!sender.checkPermission(subCommand.permission)) {
            sender.sendMessage(CommandConfig.noPermission())
            return true
        }

        // Check player-only
        if (subCommand.playerOnly && sender.player == null) {
            sender.sendMessage(CommandConfig.playerOnly())
            return true
        }

        // Execute subcommand with remaining args
        val subArgs = args.drop(1).toTypedArray()
        return subCommand.execute(sender, subArgs)
    }

    /**
     * Returns unique subcommand names (excludes aliases to avoid duplicates).
     */
    private fun getAvailableSubcommandNames(): List<String> {
        return subcommands.values.map { it.name }.distinct()
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String>? {
        if (args.isEmpty()) return null

        // First arg - show subcommand names with smart sorting
        if (args.size == 1) {
            val input = args[0]
            val available = subcommands.entries
                .filter { sender.checkPermission(it.value.permission) }
                .map { it.key }
                .distinct()

            return available.tabComplete(input)
        }

        val subName = args[0].lowercase()

        // Delegate to subcommand
        val subCommand = subcommands[subName] ?: return null

        if (!sender.checkPermission(subCommand.permission)) return null

        val subArgs = args.drop(1).toTypedArray()
        return subCommand.tabComplete(sender, subArgs)
    }

    companion object {
        /** Singleton instance for registration */
        val INSTANCE = ArcCommand()
    }
}
