package ru.arc.itemcatalog

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn

internal fun interface CaseRewardIssuer {
    fun issue(player: Player, categoryId: String, entryId: String): CaseRewardIssueResult
}

/** Narrow console bridge used by ExcellentCrates command rewards. */
internal class CaseRewardIssueCommand(
    private val playerLookup: (String) -> Player? = { Bukkit.getPlayerExact(it) },
    private val issuer: CaseRewardIssuer = CaseRewardIssuer(ItemsCatalogModule::issueCaseReward),
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is ConsoleCommandSender) {
            sender.sendMessage("This command is console-only.")
            return true
        }
        if (args.size != 3) {
            sender.sendMessage("Usage: /$label <player> <case> <entry>")
            return true
        }
        val player = playerLookup(args[0])
        if (player == null || !player.isOnline) {
            warn("Case reward rejected: player={} reason=offline", args[0])
            return true
        }
        val result = issuer.issue(player, args[1], args[2])
        if (result == CaseRewardIssueResult.INVENTORY || result == CaseRewardIssueResult.OWNED_DROP) {
            info(
                "Case reward issued: player={} case={} entry={} delivery={}",
                player.name,
                args[1],
                args[2],
                result.name.lowercase(),
            )
        } else {
            warn(
                "Case reward rejected: player={} case={} entry={} reason={}",
                player.name,
                args[1],
                args[2],
                result.name.lowercase(),
            )
        }
        return true
    }
}
