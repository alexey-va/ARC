package ru.arc.commands.arc.subcommands

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.network.repos.RedisRepo
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
                RedisRepo.saveAll()
                sender.sendMessage(CommandConfig.repoSaved())
            }
            "size", "status", null -> showStats(sender)
            else -> showStats(sender)
        }
        return true
    }

    private fun showStats(sender: CommandSender) {
        val redis = RedisRepo.allStats()
        val cached = CachedRepository.allStats()
        val allNames = (redis.map { it.id } + cached.map { it.repoId })
        val nameW = (allNames.maxOfOrNull { it.length } ?: 8).coerceIn(6, 18)

        sender.sep()
        sender.send("<gold><bold>Repositories</bold></gold>  <dark_gray>${redis.size + cached.size} total</dark_gray>")
        sender.sep()

        if (redis.isNotEmpty()) {
            sender.send("<dark_gray>  Redis (${redis.size})</dark_gray>")
            for (s in redis) {
                val name = s.id.padEnd(nameW)
                val dirty = if (s.dirtyCount > 0) "<yellow>${s.dirtyCount}d</yellow>" else "<dark_gray>${s.dirtyCount}d</dark_gray>"
                val flags = buildList {
                    if (s.loadAll) add("<green>all</green>")
                    if (s.saveBackups) add("<aqua>bak</aqua>")
                }.joinToString(" ")
                sender.send("  <aqua>▸</aqua> <white>$name</white>  " +
                    "<white>${s.entries}</white><dark_gray>e</dark_gray>  $dirty  " +
                    "<dark_gray>${s.contextCount}ctx</dark_gray>" +
                    (if (flags.isNotEmpty()) "  $flags" else ""))
            }
        }

        if (cached.isNotEmpty()) {
            if (redis.isNotEmpty()) sender.send("")
            sender.send("<dark_gray>  Cached (${cached.size})</dark_gray>")
            for (s in cached) {
                val name = s.repoId.padEnd(nameW)
                val dirty = if (s.dirtyCount > 0) "<yellow>${s.dirtyCount}d</yellow>" else "<dark_gray>${s.dirtyCount}d</dark_gray>"
                sender.send("  <aqua>▸</aqua> <white>$name</white>  " +
                    "<white>${s.cacheSize}</white><dark_gray>e</dark_gray>  $dirty  " +
                    "<dark_gray>${s.contextCount}ctx</dark_gray>")
            }
        }

        if (redis.isEmpty() && cached.isEmpty()) {
            sender.send("  <gray>No repositories registered.</gray>")
        }

        val totalEntries = redis.sumOf { it.entries } + cached.sumOf { it.cacheSize }
        val totalDirty = redis.sumOf { it.dirtyCount } + cached.sumOf { it.dirtyCount }
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
