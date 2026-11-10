package ru.arc.invsnapshots

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.plugin.Plugin
import ru.arc.commands.arc.LegacySubCommandExecutor
import ru.arc.commands.arc.subcommands.InvlistSubCommand

/** Installed only on HuskSync servers. Both public spellings delegate to the same handler. */
internal class InvlistCommand(private val owner: Plugin) :
    Command("invlist", "Посмотреть сохранённые инвентари игрока", "/invlist <игрок>", emptyList()),
    PluginIdentifiableCommand {
    private val bridge = LegacySubCommandExecutor(InvlistSubCommand)

    init { permission = SNAPSHOT_VIEW_PERMISSION }

    override fun getPlugin(): Plugin = owner

    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean =
        bridge.onCommand(sender, this, commandLabel, args)

    override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> =
        bridge.onTabComplete(sender, this, alias, args)
}
