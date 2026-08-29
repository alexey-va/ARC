package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.investigation.InvestigationModule
import ru.arc.investigation.InvestigationVerdict
import java.util.UUID

/** Player flow plus a console-only bridge for authoritative Citizens clicks. */
object InvestigationSubCommand : SubCommand {
    override val configKey = "investigation"
    override val defaultName = "investigation"
    override val defaultPermission: String? = null
    override val defaultDescription = "Открыть бюро расследований"
    override val defaultUsage = "/arc investigation [open|clue <свидетель>|verdict <версия>]"
    override val defaultPlayerOnly = false

    override fun isAvailable(): Boolean = InvestigationModule.available

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val action = args.firstOrNull()?.lowercase() ?: "open"
        if (action == TRUSTED_NPC_ACTION) {
            collectFromTrustedNpcBridge(sender, args)
            return true
        }

        val player = requirePlayer(sender) ?: return true
        when (action) {
            "open", "menu" -> InvestigationModule.open(player)
            "clue" -> {
                val witness = args.getOrNull(1)?.lowercase()?.takeIf(WITNESS_KEY::matches)
                if (witness == null) sendUsage(sender) else InvestigationModule.collect(player, witness)
            }
            "verdict" -> {
                val verdict = InvestigationVerdict.parse(args.getOrNull(1))
                if (verdict == null) sendUsage(sender) else InvestigationModule.submit(player, verdict)
            }
            else -> sendUsage(sender)
        }
        return true
    }

    private fun collectFromTrustedNpcBridge(sender: CommandSender, args: Array<String>) {
        if (sender is Player) {
            sender.sendMessage(CommandConfig.noPermission())
            return
        }
        val rawPlayerId = args.getOrNull(1) ?: return
        val playerId = runCatching { UUID.fromString(rawPlayerId) }.getOrNull() ?: return
        val witness = args.getOrNull(2)?.lowercase()?.takeIf(WITNESS_KEY::matches) ?: return
        val player = Bukkit.getPlayer(playerId) ?: return
        if (!player.isOnline) return
        InvestigationModule.collectFromNpcClick(player, witness)
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("open", "clue", "verdict").tabComplete(args[0])
            2 ->
                when (args[0].lowercase()) {
                    "clue" -> InvestigationModule.witnessKeys().tabComplete(args[1])
                    "verdict" -> InvestigationVerdict.entries.map(InvestigationVerdict::commandValue).tabComplete(args[1])
                    else -> null
                }
            else -> null
        }

    private val WITNESS_KEY = Regex("[a-z][a-z0-9_-]{2,31}")
    private const val TRUSTED_NPC_ACTION = "witness-click"
}
