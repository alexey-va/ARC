package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.repository.CachedRepository

object RepoSubCommand : SubCommand {

    override val configKey = "repo"
    override val defaultName = "repo"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Show repository stats and force-save"
    override val defaultUsage = "/arc repo [save]"

    private val mm = MiniMessage.miniMessage()
    private fun CommandSender.send(text: String) = sendMessage(mm.deserialize(text))
    private fun CommandSender.sep() = send("<dark_gray>${"─".repeat(46)}</dark_gray>")

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "save" -> {
                sender.sendMessage(CommandConfig.repoSaved())
            }
            "size", "status", null -> showStats(sender)
            else -> showStats(sender)
        }
        return true
    }

    private fun showStats(sender: CommandSender) {
        val repos = CachedRepository.allStats()
        val nameW = (repos.maxOfOrNull { it.repoId.length } ?: 8).coerceIn(6, 18)

        sender.sep()
        sender.send("<gold><bold>Repositories</bold></gold>  <dark_gray>${repos.size} total</dark_gray>")
        sender.sep()

        if (repos.isNotEmpty()) {
            for (s in repos) {
                val name = s.repoId.padEnd(nameW)
                val dirty = if (s.dirtyCount > 0) "<yellow>${s.dirtyCount}d</yellow>" else "<dark_gray>${s.dirtyCount}d</dark_gray>"
                sender.send(
                    "  <aqua>▸</aqua> <white>$name</white>  " +
                        "<white>${s.cacheSize}</white><dark_gray>e</dark_gray>  $dirty  " +
                        "<dark_gray>${s.contextCount}ctx</dark_gray>",
                )
            }
        } else {
            sender.send("  <gray>No repositories registered.</gray>")
        }

        val totalEntries = repos.sumOf { it.cacheSize }
        val totalDirty = repos.sumOf { it.dirtyCount }
        val dirtyText = if (totalDirty > 0) "<yellow>$totalDirty dirty</yellow>" else "<dark_gray>$totalDirty dirty</dark_gray>"

        sender.sep()
        sender.send("<dark_gray>  $totalEntries entries  ·  $dirtyText  ·  <white>/arc repo save</white></dark_gray>")
        sender.sep()
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> listOf("save", "status", "size").tabComplete(args[0])
            else -> null
        }
    }
}
