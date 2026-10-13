package ru.arc.commands.arc.subcommands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import ru.arc.ARC
import ru.arc.commands.arc.SubCommand
import ru.arc.commands.arc.tabComplete
import ru.arc.config.ConfigManager
import ru.arc.dungeons.dungeonService

object StaffTestSubCommand : SubCommand {
    override val configKey = "stafftest"
    override val defaultPermission = "arc.test"
    override val defaultDescription = "Выдать посохи для пробы новых атак"
    override val defaultUsage get() = "/arc stafftest [all|${dungeonService()?.staffSpellIds().orEmpty().joinToString("|")}] [игрок]"
    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size > 2) { sendUsage(sender); return true }
        val service = dungeonService()
        if (service == null || !service.staffSpellsReady()) {
            sender.sendMessage(ConfigManager.of(ARC.instance.dataPath, "modules/staff-spells.yml")
                .component("messages.unavailable", "<red>Посохи сейчас недоступны."))
            return true
        }
        val selection = args.firstOrNull()?.lowercase() ?: "all"
        val spells = service.staffSpellIds().filter { selection == "all" || it == selection }
        if (spells.isEmpty()) { sendUsage(sender); return true }
        val player = args.getOrNull(1)?.let { getOnlinePlayer(sender, it) }
            ?: if (args.size < 2) requirePlayer(sender) else null
        if (player != null) service.giveStaffSpells(player, spells)
        return true
    }
    override fun tabComplete(sender: CommandSender, args: Array<String>) = when (args.size) {
        1 -> (listOf("all") + dungeonService()?.staffSpellIds().orEmpty()).tabComplete(args[0])
        2 -> Bukkit.getOnlinePlayers().map { it.name }.tabComplete(args[1])
        else -> emptyList()
    }
}
