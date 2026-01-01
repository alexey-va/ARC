package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.network.repos.RedisRepo

/**
 * /arc repo - управление Redis репозиториями.
 *
 * Использование:
 * - save - сохранить все репозитории
 * - size - показать размеры репозиториев
 */
object RepoSubCommand : SubCommand {

    override val configKey = "repo"
    override val defaultName = "repo"
    override val defaultPermission = "arc.admin"
    override val defaultDescription = "Управление Redis репозиториями"
    override val defaultUsage = "/arc repo <save|size>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        // Без аргументов - показать статус
        if (args.isEmpty()) {
            showStatus(sender)
            return true
        }

        when (args[0].lowercase()) {
            "save" -> {
                RedisRepo.saveAll()
                sender.sendMessage(CommandConfig.repoSaved())
            }

            "size" -> showSizes(sender)
            "status" -> showStatus(sender)
            else -> sendUnknownAction(sender, args[0])
        }

        return true
    }

    private fun showStatus(sender: CommandSender) {
        sender.sendMessage(CommandConfig.get("repo.status-header", "<gold>═══ Redis репозитории ═══"))
        showSizes(sender)
        sender.sendMessage(CommandConfig.get("repo.commands", "<gray>Команды: <white>save, size"))
    }

    private fun showSizes(sender: CommandSender) {
        try {
            val sizes = RedisRepo.bytesTotal()
            if (sizes.isEmpty()) {
                sender.sendMessage(CommandConfig.repoTotal(0))
            } else {
                sizes.forEach { (name, bytes) ->
                    sender.sendMessage(CommandConfig.repoSize(name, bytes))
                }
                sender.sendMessage(CommandConfig.repoTotal(sizes.values.sum()))
            }
        } catch (e: Exception) {
            sender.sendMessage(CommandConfig.repoTotal(0))
        }
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? {
        return when (args.size) {
            1 -> listOf("status", "save", "size").tabComplete(args[0])
            else -> null
        }
    }
}
