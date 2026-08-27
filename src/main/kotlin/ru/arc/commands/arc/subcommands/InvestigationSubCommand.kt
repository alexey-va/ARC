package ru.arc.commands.arc.subcommands

import org.bukkit.command.CommandSender
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.investigation.InvestigationModule
import ru.arc.investigation.InvestigationVerdict
import ru.arc.investigation.InvestigationWitness

/** Thin, proximity-checked bridge used by the Denizen-owned bureau NPCs. */
object InvestigationSubCommand : SubCommand {
    override val configKey = "investigation"
    override val defaultName = "investigation"
    override val defaultPermission: String? = null
    override val defaultDescription = "Открыть бюро расследований"
    override val defaultUsage = "/arc investigation [open|clue <stavr|prokhor|gordey|agata|tikhon>|verdict <amount|seal|cargo|duplicate|clean>]"
    override val defaultPlayerOnly = true

    override fun isAvailable(): Boolean = InvestigationModule.available

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = requirePlayer(sender) ?: return true
        when (args.firstOrNull()?.lowercase() ?: "open") {
            "open", "menu" -> InvestigationModule.open(player)
            "clue" -> {
                val witness = InvestigationWitness.parse(args.getOrNull(1))
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

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String>? =
        when (args.size) {
            1 -> listOf("open", "clue", "verdict").tabComplete(args[0])
            2 ->
                when (args[0].lowercase()) {
                    "clue" -> InvestigationWitness.entries.map(InvestigationWitness::commandValue).tabComplete(args[1])
                    "verdict" -> InvestigationVerdict.entries.map(InvestigationVerdict::commandValue).tabComplete(args[1])
                    else -> null
                }
            else -> null
        }
}
